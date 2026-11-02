package io.github.danything.denpatv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URI
import kotlin.concurrent.thread

/**
 * 実況のコメントの取得先 (ブラウザの denpa の `endpoints.ts` と同じ宛先。ホストまで決めておく)。
 *
 * - NX-Jikkyo (tsukumijima さんが運営する非公式のコメントサーバ。公式のニコニコ実況のコメントも取り込んでいる): ライブ・追っかけの新しい所を
 *   テレビが WebSocket で直接読む
 * - ニコニコ実況 過去ログ API (同じく tsukumijima さん): 録画・追っかけの古い所をテレビが直接読む
 *
 * smoke (縮めた APK) だけ、ビルドのときに偽物へ差し替えられる (`DENPA_TV_JIKKYO_NX`・`DENPA_TV_JIKKYO_KAKOLOG`。app/build.gradle.kts)
 */
class JikkyoEndpoints(
    /** NX-Jikkyo の WebSocket のオリジン (`wss://…`) */
    val nx: String,
    /** 過去ログ API のオリジン (`https://…`) */
    val kakolog: String,
) {
    /** 視聴の WebSocket。ここで `room` を受け取り、コメントの WebSocket の URL はそれに従う (こちらで組まない) */
    fun watch(channel: String): String = "$nx/api/v1/channels/$channel/ws/watch"

    /** 過去ログ。時刻は unix 秒。`endtime` は今より後にできず、1回に 3 日まで */
    fun kakolog(channel: String, from: Long, to: Long): String =
        "$kakolog/api/kakolog/$channel?starttime=$from&endtime=$to&format=json"

    /**
     * `room` が言うコメントの WebSocket の URL から、NX-Jikkyo の中のパスだけを取る。**NX-Jikkyo の外を指していたら null** —
     * 返事の言うまま別のホストへ繋がない。URL はこちらのオリジンに付け直して使う
     */
    fun commentPath(uri: String): String? {
        val url = runCatching { URI(uri) }.getOrNull() ?: return null
        val origin = runCatching { URI(nx) }.getOrNull() ?: return null
        if (url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port) return null
        return (url.rawPath ?: "") + (url.rawQuery?.let { "?$it" } ?: "")
    }

}

/** 実況の WebSocket の 1 本 (単体テストでは偽物と差し替える) */
interface JikkyoSocket {
    fun send(text: String)
    fun close()
}

/** 実況の WebSocket の出来事。どれも読むスレッドから呼ばれる */
interface JikkyoSocketListener {
    fun onOpen()
    fun onMessage(text: String)
    /** 閉じた・繋げなかった・切れた。コードは閉じたフレームのもの (切れたなら 1006) */
    fun onClose(code: Int)
}

fun interface JikkyoSocketOpener {
    fun open(url: String, listener: JikkyoSocketListener): JikkyoSocket
}

/**
 * 自前の WebSocket (`WebSocket`) を 1 本ずつのスレッドで読む。黙ったまま `IDLE_MS` 経てば ping を送り、もう `IDLE_MS` 黙っていれば切れたとみなす
 */
object WebSocketOpener : JikkyoSocketOpener {
    private const val IDLE_MS = 30_000

    override fun open(url: String, listener: JikkyoSocketListener): JikkyoSocket {
        val handle = object : JikkyoSocket {
            @Volatile
            var socket: WebSocket? = null
            @Volatile
            var closed = false

            override fun send(text: String) {
                runCatching { socket?.send(text) }
            }

            override fun close() {
                closed = true
                socket?.close()
            }
        }
        thread(name = "jikkyo-ws", isDaemon = true) {
            val socket = try {
                WebSocket.connect(URI(url), IDLE_MS)
            } catch (_: Exception) {
                if (!handle.closed) listener.onClose(WebSocket.ABNORMAL)
                return@thread
            }
            handle.socket = socket
            if (handle.closed) return@thread socket.close()
            listener.onOpen()
            try {
                while (true) {
                    when (val message = socket.receive()) {
                        is WebSocket.Message.Text -> if (!handle.closed) listener.onMessage(message.text)
                        is WebSocket.Message.Closed -> {
                            if (!handle.closed) listener.onClose(message.code)
                            return@thread
                        }
                    }
                }
            } catch (_: IOException) {
                socket.close()
                if (!handle.closed) listener.onClose(WebSocket.ABNORMAL)
            }
        }
        return handle
    }
}

/**
 * **NX-Jikkyo の WebSocket に繋いでコメントを受ける** (テレビから直接。denpa のサーバは通らない)。手順はブラウザの denpa の `nx.ts` と同じ
 * (テレビは書き込まないので、書き込みのぶんは持たない):
 *
 * 1. 視聴の WS (`watch`) に繋ぎ、`startWatching` を送る。`room` でコメントの WS の URL とスレッドを受け取る (URL はこちらで組まない。
 *    NX-Jikkyo の外を指していたら繋がない)。`seat` の間隔で `keepSeat`、`ping` には `pong` を返す
 * 2. コメントの WS に旧ニコ生と同じ形式の `thread` を送る (`res_from: -100`。局を替えた直後でもすぐ流せる)
 * 3. 切れたら 1 秒 → 2 秒 → … 60 秒で頭打ちで繋ぎ直す。5 回続けて失敗したら「いまは読めない」にし、60 秒おきに試し続ける。
 *    スレッドは毎日 4:00 に替わり、サーバが閉じる (`disconnect` か、コード 1000)。そのときは失敗に数えず 1 秒で視聴の WS から繋ぎ直す
 *
 * **出来事はどれも `scope` (1 本のスレッドで回すもの) の上で扱う** — WebSocket の読むスレッドから届いたものも、そこへ移してから触る
 */
class NxJikkyo(
    private val channel: String,
    private val events: Events,
    private val scope: CoroutineScope,
    private val endpoints: JikkyoEndpoints,
    private val opener: JikkyoSocketOpener = WebSocketOpener,
) {
    interface Events {
        /** コメントが来た (形式のずれたもの・流さないものは来ない) */
        fun comments(comments: List<JikkyoComment>)
        /** 読めているか。最初は読めている扱い。5 回続けて失敗したら false、戻れば true */
        fun state(ok: Boolean)
        /** 形式のずれた返事を受けた (呼ぶ側が1回だけ警告する) */
        fun odd(what: String)
    }

    /** 繋いだ1本。閉じたあとに届いた出来事は、`watch` / `comment` と違うので捨てる */
    private inner class Conn(url: String, private val onText: (Conn, String) -> Unit, private val onOpened: (Conn) -> Unit) : JikkyoSocketListener {
        var open = false
        val socket: JikkyoSocket = opener.open(url, this)

        override fun onOpen() {
            scope.launch {
                open = true
                onOpened(this@Conn)
            }
        }

        override fun onMessage(text: String) {
            scope.launch { onText(this@Conn, text) }
        }

        override fun onClose(code: Int) {
            scope.launch {
                open = false
                onClosed(this@Conn, code)
            }
        }
    }

    private var watch: Conn? = null
    private var comment: Conn? = null
    private var keep: Job? = null
    private var retry: Job? = null
    private var failures = 0
    private var ok = true
    /** いまの繋ぎでスレッドの返事を受けたか。受ける前にきれいに閉じられたら、失敗に数える */
    private var live = false
    private var closed = false
    /** いまのスレッド (`room`) */
    private var room: Pair<String, String>? = null
    /** 遡る起点の時刻 (unix ms)。コメントの WS が開いたら1回だけ送る */
    private var backfillBefore: Long? = null
    private var backfilled = false

    fun start() {
        closed = false
        connect()
    }

    /** 閉じる。もう繋ぎ直さない */
    fun close() {
        closed = true
        teardown()
        retry?.cancel()
        retry = null
    }

    /**
     * `before` より前のコメントを1回だけ遡って取る (録画中の追っかけで、過去ログにまだ入っていない 10 分以内のところ)。
     * 2度目からは何もしない — NX-Jikkyo の DB の読み出しは重いので、何度も遡らない
     */
    fun backfill(before: Long) {
        if (backfilled || backfillBefore != null) return
        backfillBefore = before
        sendBackfill()
    }

    private fun connect() {
        if (closed) return
        live = false
        watch = Conn(endpoints.watch(channel), ::onWatch) { conn ->
            if (conn === watch) conn.socket.send("""{"type":"startWatching","data":{"reconnect":false}}""")
        }
    }

    private fun onWatch(conn: Conn, text: String) {
        if (conn !== watch) return
        val message = runCatching { lenientJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if (message == null) return events.odd("視聴の WS の返事を読めません")
        val body = message["data"] as? JsonObject ?: JsonObject(emptyMap())
        when ((message["type"] as? JsonPrimitive)?.content) {
            "ping" -> conn.socket.send("""{"type":"pong"}""")
            "seat" -> {
                // ニコ生と同じ決まりなので送る (いまの NX-Jikkyo は来なくても切らない)
                val every = (body["keepIntervalSec"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it > 0 } ?: 30.0
                startKeep(conn, (every * 1000).toLong())
            }
            "room" -> {
                val uri = ((body["messageServer"] as? JsonObject)?.get("uri") as? JsonPrimitive)?.takeIf { it.isString }?.content
                val thread = (body["threadId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (uri == null || thread == null) return events.odd("room の形式が違います")
                val path = endpoints.commentPath(uri) ?: return events.odd("room のコメントの WS が NX-Jikkyo の外を指しています")
                val key = (body["yourPostKey"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
                room = thread to key
                openComment("${endpoints.nx}$path")
            }
            "disconnect" -> {
                // スレッドの終わり (`END_PROGRAM`) など。視聴の WS から繋ぎ直す
                teardown()
                schedule(live)
            }
        }
    }

    private fun openComment(url: String) {
        comment?.socket?.close()
        comment = Conn(url, ::onComment) { conn ->
            if (conn !== comment) return@Conn
            conn.socket.send(
                buildJsonArray {
                    add(ping("rs:0"))
                    add(ping("ps:0"))
                    add(thread(-100, null))
                    add(ping("pf:0"))
                    add(ping("rf:0"))
                }.toString(),
            )
            sendBackfill()
        }
    }

    private fun ping(content: String) = buildJsonObject { putJsonObject("ping") { put("content", content) } }

    private fun thread(resFrom: Int, `when`: Long?) = buildJsonObject {
        putJsonObject("thread") {
            put("version", "20061206")
            put("thread", room?.first ?: "")
            put("threadkey", room?.second ?: "")
            put("res_from", resFrom)
            if (`when` != null) put("when", `when`)
        }
    }

    private fun sendBackfill() {
        val conn = comment ?: return
        val before = backfillBefore ?: return
        if (!conn.open) return
        backfillBefore = null
        backfilled = true
        // `when` を付けると「その時刻より前の res_from 件」を返して止まる (新しいものの配信はそのまま続く)
        conn.socket.send(buildJsonArray { add(thread(-BACKFILL_LIMIT, before / 1000)) }.toString())
    }

    private fun onComment(conn: Conn, text: String) {
        if (conn !== comment) return
        val message = runCatching { lenientJson.parseToJsonElement(text) }.getOrNull()
        if (message == null) return events.odd("コメントの WS の返事を読めません")
        // 1回に1つのはずだが、並びで来ても読む
        val list: List<JsonElement> = message as? JsonArray ?: listOf(message)
        val comments = mutableListOf<JikkyoComment>()
        for (item in list) {
            val obj = item as? JsonObject ?: continue
            if ("thread" in obj) settle()
            val chat = obj["chat"] ?: continue
            when (val read = readChat(chat)) {
                is ChatRead.Ok -> comments += read.comment
                ChatRead.Odd -> events.odd("chat の形式が違います")
                ChatRead.Skip -> Unit
            }
        }
        if (comments.isNotEmpty()) events.comments(comments)
    }

    /** 読めるようになった。失敗の数を戻す */
    private fun settle() {
        failures = 0
        live = true
        if (!ok) {
            ok = true
            events.state(true)
        }
    }

    private fun onClosed(conn: Conn, code: Int) {
        if (conn !== watch && conn !== comment) return
        // どちらかが閉じたら両方やり直す (視聴の WS から)
        val clean = code == WebSocket.NORMAL && live
        teardown()
        schedule(clean)
    }

    /** 繋ぎ直しを仕掛ける。`clean` はサーバがきれいに閉じた (4:00 のスレッドの替わり目) ときで、失敗に数えず 1 秒後に繋ぎ直す */
    private fun schedule(clean: Boolean) {
        if (closed || retry != null) return
        if (!clean) failures++
        if (failures >= FAILURES_TO_DOWN && ok) {
            ok = false
            events.state(false)
        }
        val wait = if (clean) 1_000L else minOf(MAX_BACKOFF_MS, 1_000L shl (failures - 1).coerceIn(0, 16))
        retry = scope.launch {
            delay(wait)
            retry = null
            connect()
        }
    }

    private fun startKeep(conn: Conn, everyMs: Long) {
        keep?.cancel()
        keep = scope.launch {
            while (true) {
                delay(everyMs)
                if (conn !== watch || !conn.open) return@launch
                conn.socket.send("""{"type":"keepSeat"}""")
            }
        }
    }

    private fun teardown() {
        keep?.cancel()
        keep = null
        room = null
        val sockets = listOfNotNull(watch, comment)
        watch = null
        comment = null
        sockets.forEach { runCatching { it.socket.close() } }
        // 遡りは繋ぎ直しても取り直さない (1回だけ)。送る前だったものは次の WS で送る
    }

    companion object {
        /** 続けて何回失敗したら「いまは読めない」にするか */
        private const val FAILURES_TO_DOWN = 5
        /** 繋ぎ直しの間隔の頭打ち (ms) */
        private const val MAX_BACKOFF_MS = 60_000L
        /** 遡るときの件数の上限 */
        private const val BACKFILL_LIMIT = 1000
    }
}
