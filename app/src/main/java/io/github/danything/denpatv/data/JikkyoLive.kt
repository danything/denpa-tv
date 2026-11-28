package io.github.danything.denpatv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * **ライブの実況のコメントを denpa のサーバから受ける** (ライブ・追っかけ。ブラウザの denpa の `live.ts` と同じ決まり)。
 * サーバが denpa-hub から局ごとに 1 本で受けたものを、`POST api/jikkyo/feed` で続きから受ける (ロングポーリング)。テレビは外へ繋がない。
 *
 * - 前に受けた番号 (`seq`) と印 (`epoch`) を渡す。サーバは続きが来るか 20 秒たつまで返事を待つので、受けたらすぐ次を呼ぶ
 *   (コメントの無い返事がすぐ返ってきたときだけ `GAP_MS` 置く)
 * - 初めての呼び出しは直近 15 分を受ける (録画中の追っかけの、過去ログに入る前の 10 分もここで埋まる)
 * - 届かない・断られたら `RETRY_MS` 置く。3 回続けて届かなければ「hub に繋がっていない」にする。返事の `hub` が `down` のときも同じ
 * - 受け口が無い (古い denpa の 404) ならやめる (`Events.tooOld`)。401 もやめる (`Events.unauthorized`)
 * - `official` なら公式のニコニコ実況のコメントも同じ呼び出しで受ける (`JikkyoOfficial`)。公式から受けている間は、
 *   境より後に書かれた NX-Jikkyo の取り込み (`src: "nicolive"`) を捨てる (同じコメントを 2 度流さない)
 *
 * **出来事はどれも `scope` (1 本のスレッドで回すもの) の上で呼ぶ**。HTTP は `io` の上で待つ
 */
class JikkyoLive(
    private val channel: String,
    private val server: JikkyoServer,
    private val events: Events,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val now: () -> Long,
    official: Boolean,
) {
    interface Events {
        /** コメントが来た (形式のずれたもの・流さないもの・公式と重なる NX-Jikkyo の取り込みは来ない) */
        fun comments(comments: List<JikkyoComment>)

        /** denpa のサーバが hub に繋がっているか。最初は繋がっている扱い */
        fun hub(ok: Boolean)

        /** 公式の境が変わった (null なら公式を受けていない) */
        fun boundary(at: Long?)

        /** 形式のずれた返事を受けた (呼ぶ側が1回だけ警告する) */
        fun odd(what: String)

        /** 受け口が無い (古い denpa)。もう呼ばない */
        fun tooOld()

        /** 401。もう呼ばない */
        fun unauthorized()
    }

    /** 公式のコメントの受け手 (連携していない世帯は、サーバが `unlinked` を返して境は無いまま) */
    val official: JikkyoOfficial? = if (official) JikkyoOfficial(events::boundary) else null

    private val abort = Abort()
    private var job: Job? = null
    private var stopped = false
    private var seq: Long? = null
    private var epoch: String? = null
    private var failures = 0
    private var hub = true

    fun start() {
        job = scope.launch { loop() }
    }

    /** 止める (局を替えた・画面を閉じた)。待っている返事は切り、このあとに読み終えた返事は捨てる */
    fun stop() {
        stopped = true
        official?.stop()
        job?.cancel()
        abort.abort()
    }

    private suspend fun loop() {
        while (!stopped) {
            val started = now()
            var ok = false
            var got = 0
            try {
                val res = withContext(io) { server.feed(body(), abort) }
                when {
                    missingEndpoint(res.code) -> return events.tooOld()
                    res.ok -> {
                        got = take(res.text())
                        ok = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Unauthorized) {
                return events.unauthorized()
            } catch (_: Exception) {
                // 届かない
            }
            if (stopped) return
            if (ok) {
                failures = 0
                if (got == 0 && now() - started < GAP_MS) delay(GAP_MS)
                continue
            }
            official?.fail()
            failures++
            if (failures >= FAILURES_TO_DOWN) setHub(false)
            delay(RETRY_MS)
        }
    }

    private fun body(): String = buildJsonObject {
        put("channel", channel)
        seq?.let { put("after", it) }
        epoch?.let { put("epoch", it) }
        official?.let { put("official", it.body()) }
    }.toString()

    /** 返事を読む。受けたコメントの数 (公式のぶんも) を返す */
    private fun take(text: String): Int {
        if (stopped) return 0
        val body = runCatching { lenientJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if (body == null) {
            events.odd("実況の返事の形式が違います")
            return 0
        }
        body["epoch"].string()?.let { epoch = it }
        body["seq"].number()?.let { seq = it.toLong() }
        setHub(body["hub"].string() != "down")
        val fromOfficial = official?.let { reader ->
            reader.apply(body["official"]) ?: emptyList<JikkyoComment>().also { events.odd("公式の実況の返事の形式が違います") }
        }.orEmpty()
        val list = body["comments"] as? JsonArray
        val (read, odd) = list?.let(::readComments) ?: (emptyList<CommentRead.Ok>() to 0)
        if (odd > 0) events.odd("実況のコメントの形式が一部違います")
        // 公式から受けている間は、境より後に書かれた NX-Jikkyo の取り込みを捨てる
        val boundary = official?.boundary
        val comments = fromOfficial + read.filterNot { it.src == "nicolive" && boundary != null && it.comment.at >= boundary }.map { it.comment }
        if (comments.isNotEmpty()) events.comments(comments)
        return comments.size
    }

    private fun setHub(ok: Boolean) {
        if (ok == hub) return
        hub = ok
        events.hub(ok)
    }

    companion object {
        /** 届かなかったあと、次に呼ぶまで (ms) */
        const val RETRY_MS = 10_000L
        /** コメントの無い返事がすぐ返ってきたあと、次に呼ぶまで (ms)。続けてすぐ返ってきても詰めて呼ばない */
        const val GAP_MS = 1_000L
        /** 続けて何回届かなければ「繋がっていない」にするか */
        private const val FAILURES_TO_DOWN = 3
    }
}

/**
 * **公式のニコニコ実況のコメントを受ける** (連携した世帯のライブ。ブラウザの denpa の `official.ts` の `OfficialReader` と同じ決まり)。
 * 自分ではリクエストしない。`JikkyoLive` が `api/jikkyo/feed` の呼び出しに続きの番号を `official` として載せ (`body`)、返事の `official` を読む (`apply`)。
 * 端末から denpa のサーバへのロングポーリングを 1 本にするため
 *
 * - **境 (`boundary`) より後は公式、前は NX-Jikkyo が取り込んだもの**を流す。境は max(サーバが受け続けている始まり, 最初の返事の時刻)。
 *   サーバが受けられていなければ (連携していない世帯の `unlinked` も) 境は無く、NX-Jikkyo の取り込みを全部流す
 * - サーバが返す番号 (`seq`) の続きをリクエストする。サーバを起こし直した (`epoch` が違う) ら最初から
 * - 世帯のアカウントが書いたもの (`own`) もほかと同じに流す (テレビは書き込まないので、この端末の書き込みの戻りは無い)
 * - 受けられなければ (`fail`) 境を無くす
 */
class JikkyoOfficial(private val changed: (Long?) -> Unit) {
    /** 境 (unix ms。サーバの時計)。公式を受けていなければ null */
    var boundary: Long? = null
        private set
    private var after: Long? = null
    private var epoch: String? = null
    /** 最初の返事の時刻 (サーバの時計) */
    private var first: Long? = null
    private var stopped = false

    /** 次の呼び出しに載せるもの。最初は `{}` */
    fun body(): JsonObject = buildJsonObject {
        after?.let { put("after", it) }
        epoch?.let { put("epoch", it) }
    }

    /** 止めた。このあとに読み終えた返事は捨てる */
    fun stop() {
        stopped = true
    }

    /** 届かない・断られた。境を無くす */
    fun fail() {
        if (stopped) return
        setBoundary(null)
    }

    /** 返事の `official` を読む。流すものを返す (形式が違えば境を無くして null) */
    fun apply(reply: JsonElement?): List<JikkyoComment>? {
        if (stopped) return emptyList()
        val r = reply as? JsonObject
        val epoch = r?.get("epoch").string()
        val seq = r?.get("seq").number()
        val now = r?.get("now").number()
        val comments = r?.get("comments") as? JsonArray
        if (r == null || epoch == null || seq == null || now == null || comments == null) {
            fail()
            return null
        }
        if (epoch != this.epoch || first == null) {
            this.epoch = epoch
            first = now.toLong()
        }
        after = seq.toLong()
        val boundary = r["since"].number()?.let { maxOf(it.toLong(), first!!) }
        setBoundary(boundary)
        if (boundary == null) return emptyList()
        return comments.mapNotNull { item ->
            val c = item as? JsonObject ?: return@mapNotNull null
            val at = c["at"].number()?.toLong() ?: return@mapNotNull null
            val text = c["text"].string() ?: return@mapNotNull null
            if (at < boundary) null else JikkyoComment(at, text, readStyle(c["style"]))
        }
    }

    private fun setBoundary(at: Long?) {
        if (at == boundary) return
        boundary = at
        changed(at)
    }
}
