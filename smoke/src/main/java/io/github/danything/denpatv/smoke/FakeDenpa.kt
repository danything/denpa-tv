package io.github.danything.denpatv.smoke

import android.content.res.AssetManager
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * エミュレータの中 (テストのプロセス) で動かす偽の denpa (`SmokeTest`)。**HTTP/1.1 を ServerSocket で数十行だけ話す** — Android に JDK の
 * `com.sun.net.httpserver` は無く、MockWebServer (OkHttp) を足すほどのことはしない (docs/libraries.md)。
 *
 * 答えるのは denpa の docs/api.md のうちアプリが叩く口だけ。映像はテストの APK の assets (`scripts/smoke-media.sh` で作る)。
 * 1つの要求ごとに繋ぎを閉じる (`Connection: close`)。映像は Range に答える。`api/events` (SSE) は閉じるまで開けておく。
 * 届いた要求は `requests` に `GET /api/…` の形で溜める
 */
class FakeDenpa(
    private val assets: AssetManager,
    /** 画面の絵を撮るときの作り物の録画を返す (`Showcase`。`Screenshots`) */
    private val showcase: Boolean = false,
) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool()
    val requests = ConcurrentLinkedQueue<String>()

    /**
     * この数だけ、次のライブの要求に 503 を返す (denpa が入れ替わっている最中のつもり。アプリが待って頼み直すかを見る)。
     * ライブの映像 (live.mp4) は 10 秒で終わる — denpa が流れを閉じた (番組の境目での焼き直し・再起動) のと同じに見える
     */
    val failLive = AtomicInteger(0)

    /** この数だけ、次のライブの要求で映像を半分だけ送って黙る (チューナーのドライバが止まったつもり。アプリが見張りで気付くかを見る) */
    val stallLive = AtomicInteger(0)

    /**
     * この数だけ、次のライブの要求に 200 を返して何も送らずに閉じる (denpa が選局・焼くのに失敗したときと同じ。
     * Media3 は「形が分からない」と言うので、アプリがそれを見分けて言い換えるかを見る)
     */
    val emptyLive = AtomicInteger(0)

    /** アプリに覚えさせる繋ぐ先 */
    val url = "http://127.0.0.1:${server.localPort}/"

    init {
        thread(name = "FakeDenpa", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                try {
                    pool.execute { socket.use(::handle) }
                } catch (_: RejectedExecutionException) {
                    // 閉じている途中
                    socket.close()
                    break
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        try {
            val (method, target) = readLine(input)?.split(' ')?.takeIf { it.size >= 2 } ?: return
            val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }
                .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.take(it).trim().lowercase() to line.substring(it + 1).trim() } }
                .toMap()
            // 本文 (観た位置の POST) は読み捨てる (readNBytes は Android 13 からなので1つずつ)
            repeat(headers["content-length"]?.toIntOrNull() ?: 0) { if (input.read() < 0) return }
            val path = target.substringBefore('?')
            requests += "$method $path"
            route(method, path, headers["range"], out)
        } catch (_: SocketException) {
            // アプリが先に閉じた (局を替えた・画面を離れた)
        }
    }

    private fun route(method: String, path: String, range: String?, out: OutputStream) {
        val recording = Regex("/api/recordings/(\\d+)/(detail|resume|file|poster)").matchEntire(path)
        val live = Regex("/api/services/(\\d+)/live").matchEntire(path)?.groupValues?.get(1)?.toLong()
        when {
            path == "/api/health" -> json(out, """{"ok":true,"version":"v1.50.0"}""")
            path == "/api/services" -> json(out, services())
            // 局送りの行き先も同じ映像を流す
            live == SERVICE_ID || live == NEXT_SERVICE_ID ->
                // 黙るのが先 (黙らせてから 503 を返し続ける並びを作れるように)
                if (emptyLive.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) {
                    empty(out, "video/mp4")
                } else if (stallLive.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) {
                    stall(out, "live.mp4", "video/mp4")
                } else if (failLive.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) {
                    respond(out, 503, "text/plain", "restarting".toByteArray())
                } else {
                    media(out, "live.mp4", "video/mp4", range)
                }
            path == "/api/services/$SERVICE_ID/record" && method == "POST" ->
                json(out, """{"recorded":"$PROGRAM_TITLE","programId":1,"reserved":true}""")
            path == "/api/programs/$PROGRAM_ID" ->
                json(out, """{"name":"$PROGRAM_TITLE[字]","service_name":"$SERVICE_NAME","description":"$PROGRAM_DESCRIPTION","extended":{"出演者":"偽の人"},"genre_detail":[{"lv1":0,"lv2":0}],"audios":[{"componentType":3,"langs":["jpn"]}],"video_type":"mpeg2","video_resolution":"1080i","is_free":true}""")
            path == "/api/recordings" -> json(out, if (showcase) Showcase.recordings() else recordings())
            path == "/api/events" -> events(out)
            recording != null && method == "POST" -> respond(out, 204, "text/plain", ByteArray(0))
            showcase && recording?.groupValues?.get(2) == "poster" -> respond(out, 200, "image/jpeg", Showcase.poster(recording.groupValues[1].toLong()))
            showcase && recording?.groupValues?.get(2) == "detail" -> json(out, Showcase.detail(recording.groupValues[1].toLong()))
            recording?.groupValues?.get(2) == "detail" -> json(out, """{"description":"$RECORDING_DESCRIPTION","extended":{}}""")
            path == "/api/recordings/$RECORDING_ID/file" -> media(out, "recording.mkv", "video/x-matroska", range)
            // 焼いた録画の字幕 (文字の配置)
            path == "/api/recordings/$RECORDING_ID/captions.json" -> json(out, FakeCaptions.smoke)
            // 作り物の録画は、どれも同じ映像と字幕
            showcase && recording?.groupValues?.get(2) == "file" -> media(out, "recording.mkv", "video/x-matroska", range)
            showcase && Regex("/api/recordings/\\d+/captions\\.json").matches(path) -> json(out, FakeCaptions.showcase)
            // 追っかけはライブと同じ焼き方の fMP4 (10 秒で閉じる。録り終える前に閉じたので、アプリは居た場所から頼み直す)
            path == "/api/recordings/$CHASE_ID/chase" -> media(out, "live.mp4", "video/mp4", range)
            else -> respond(out, 404, "text/plain", "not found".toByteArray())
        }
    }

    /** 局は2つ (左右の局送りで行き来する)。どちらも放送中 (番組名がある。サブチャンネルとして飛ばされない) */
    private fun services(): String {
        val now = System.currentTimeMillis()
        fun service(id: Long, name: String, key: Int, title: String) =
            """{"id":$id,"type":"GR","name":"$name","remoteControlKey":$key,"live":"api/services/$id/live",
            "now":{"id":${id * 10},"title":"$title","startAt":${now - 600_000},"endAt":${now + 3_000_000}}}"""
        return "[${service(SERVICE_ID, SERVICE_NAME, 1, PROGRAM_TITLE)},${service(NEXT_SERVICE_ID, NEXT_SERVICE_NAME, 2, NEXT_PROGRAM_TITLE)}]"
    }

    /** 録り終えた録画と、録っている最中の録画 (追っかけ。1時間後まで録る) */
    private fun recordings(): String {
        val now = System.currentTimeMillis()
        val start = now - 86_400_000
        return """[{"id":$RECORDING_ID,"title":"$RECORDING_TITLE","serviceName":"$SERVICE_NAME","startAt":$start,"durationMs":10000,"watchedAt":null,
            "files":[{"source":"encoded","codec":"h264","url":"api/recordings/$RECORDING_ID/file"}]},
            {"id":$CHASE_ID,"title":"$CHASE_TITLE","serviceName":"$SERVICE_NAME","startAt":${now - 120_000},"endAt":${now + 3_600_000},
            "recording":true,"chase":"api/recordings/$CHASE_ID/chase","files":[]}]"""
    }

    /** 知らせ (SSE)。頭に書き添えを1行送り、閉じられるまで 5 秒おきに `ping` を送る。作り物の録画を返すときは、焼いている進み (`encode`) も一緒に */
    private fun events(out: OutputStream) {
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n: connected\n\n".toByteArray())
        out.flush()
        try {
            while (!server.isClosed) {
                if (showcase) out.write("event: encode\ndata: {\"recordingId\":${Showcase.ENCODING_ID},\"percent\":${Showcase.ENCODING_PERCENT}}\n\n".toByteArray())
                out.flush()
                Thread.sleep(5_000)
                out.write("event: ping\ndata: {}\n\n".toByteArray())
                out.flush()
            }
        } catch (_: InterruptedException) {
        }
    }

    /** 長さを言わずに (denpa のライブと同じ) 200 を返し、何も送らずに閉じる */
    private fun empty(out: OutputStream, type: String) {
        out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    /** 長さを言わずに (denpa のライブと同じ) 半分だけ送り、閉じずに黙る */
    private fun stall(out: OutputStream, asset: String, type: String) {
        val bytes = assets.open(asset).use(InputStream::readBytes)
        out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes, 0, bytes.size / 2)
        out.flush()
        try {
            Thread.sleep(STALL_HOLD_MS)
        } catch (_: InterruptedException) {
        }
    }

    private fun media(out: OutputStream, asset: String, type: String, range: String?) {
        val bytes = assets.open(asset).use(InputStream::readBytes)
        val match = range?.let { Regex("bytes=(\\d*)-(\\d*)").matchEntire(it.trim()) }
        val (first, last) = match?.destructured ?: return respond(out, 200, type, bytes, "Accept-Ranges: bytes")
        // bytes=N- / bytes=N-M / bytes=-N (後ろから N バイト)
        val from = if (first.isEmpty()) (bytes.size - (last.toIntOrNull() ?: 0)).coerceAtLeast(0) else first.toInt()
        val to = if (first.isEmpty()) bytes.size - 1 else last.toIntOrNull()?.coerceAtMost(bytes.size - 1) ?: (bytes.size - 1)
        if (from >= bytes.size || from > to) return respond(out, 416, type, ByteArray(0), "Content-Range: bytes */${bytes.size}")
        respond(out, 206, type, bytes.copyOfRange(from, to + 1), "Content-Range: bytes $from-$to/${bytes.size}")
    }

    private fun json(out: OutputStream, body: String) = respond(out, 200, "application/json", body.toByteArray())

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray, vararg extra: String) {
        val head = buildString {
            append("HTTP/1.1 $code ${REASONS[code] ?: "OK"}\r\n")
            append("Content-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
            extra.forEach { append(it).append("\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    /** 1行 (CRLF まで) を ISO-8859-1 で。繋ぎが閉じていれば null */
    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else line.toString("ISO-8859-1")
            if (b == '\n'.code) return line.toString("ISO-8859-1").trimEnd('\r')
            line.write(b)
        }
    }

    override fun close() {
        server.close()
        pool.shutdownNow()
    }

    companion object {
        const val SERVICE_ID = 3273601024L
        const val SERVICE_NAME = "偽の総合"
        const val PROGRAM_TITLE = "偽の番組"
        /** いまの番組の番組 ID (`now.id`。局の id × 10) と、その説明 (`api/programs/<id>`。ライブの詳しくに出る) */
        const val PROGRAM_ID = SERVICE_ID * 10
        const val PROGRAM_DESCRIPTION = "偽の番組の説明"
        /** 局送りで次に来る局 */
        const val NEXT_SERVICE_ID = 3273601032L
        const val NEXT_SERVICE_NAME = "偽の教育"
        const val NEXT_PROGRAM_TITLE = "偽の講座"
        const val RECORDING_ID = 1L
        const val RECORDING_TITLE = "偽の録画"
        /** 録画の説明 (`detail`)。詳しくに出る */
        const val RECORDING_DESCRIPTION = "偽の denpa の録画"
        /** 録っている最中の録画 (追っかけで観る) */
        const val CHASE_ID = 2L
        const val CHASE_TITLE = "偽の録画中"
        /** 黙っている長さ (アプリが見張りで気付いて頼み直すより十分長く) */
        private const val STALL_HOLD_MS = 60_000L
        private val REASONS = mapOf(200 to "OK", 204 to "No Content", 206 to "Partial Content", 404 to "Not Found", 503 to "Service Unavailable", 416 to "Range Not Satisfiable")
    }
}
