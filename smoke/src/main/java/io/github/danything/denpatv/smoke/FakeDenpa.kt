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
import kotlin.concurrent.thread

/**
 * エミュレータの中 (テストのプロセス) で動かす偽の denpa (`SmokeTest`)。**HTTP/1.1 を ServerSocket で数十行だけ話す** — Android に JDK の
 * `com.sun.net.httpserver` は無く、MockWebServer (OkHttp) を足すほどのことはしない (docs/libraries.md)。
 *
 * 答えるのは denpa の docs/api.md のうちアプリが叩く口だけ。映像はテストの APK の assets (`scripts/smoke-media.sh` で作る)。
 * 1つの要求ごとに繋ぎを閉じる (`Connection: close`)。映像は Range に答える。`api/events` (SSE) は閉じるまで開けておく。
 * 届いた要求は `requests` に `GET /api/…` の形で溜める
 */
class FakeDenpa(private val assets: AssetManager) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool()
    val requests = ConcurrentLinkedQueue<String>()

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
                pool.execute { socket.use(::handle) }
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
        val recording = Regex("/api/recordings/(\\d+)/(detail|resume|file)").matchEntire(path)
        when {
            path == "/api/health" -> json(out, """{"ok":true}""")
            path == "/api/services" -> json(out, services())
            path == "/api/services/$SERVICE_ID/live" -> media(out, "live.mp4", "video/mp4", range)
            path == "/api/recordings" -> json(out, recordings())
            path == "/api/events" -> events(out)
            recording != null && method == "POST" -> respond(out, 204, "text/plain", ByteArray(0))
            recording?.groupValues?.get(2) == "detail" -> json(out, """{"description":"偽の denpa の録画","extended":{}}""")
            path == "/api/recordings/$RECORDING_ID/file" -> media(out, "recording.mkv", "video/x-matroska", range)
            else -> respond(out, 404, "text/plain", "not found".toByteArray())
        }
    }

    private fun services(): String {
        val now = System.currentTimeMillis()
        return """[{"id":$SERVICE_ID,"type":"GR","name":"$SERVICE_NAME","remoteControlKey":1,
            "live":"api/services/$SERVICE_ID/live",
            "now":{"title":"偽の番組","startAt":${now - 600_000},"endAt":${now + 3_000_000}}}]"""
    }

    private fun recordings(): String {
        val start = System.currentTimeMillis() - 86_400_000
        return """[{"id":$RECORDING_ID,"title":"$RECORDING_TITLE","serviceName":"$SERVICE_NAME","startAt":$start,"durationMs":10000,
            "files":[{"source":"encoded","codec":"h264","url":"api/recordings/$RECORDING_ID/file"}]}]"""
    }

    /** 知らせ (SSE)。頭に書き添えを1行送り、閉じられるまで 5 秒おきに `ping` を送る */
    private fun events(out: OutputStream) {
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n: connected\n\n".toByteArray())
        out.flush()
        try {
            while (!server.isClosed) {
                Thread.sleep(5_000)
                out.write("event: ping\ndata: {}\n\n".toByteArray())
                out.flush()
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun media(out: OutputStream, asset: String, type: String, range: String?) {
        val bytes = assets.open(asset).use(InputStream::readBytes)
        val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it.trim()) }
        if (match == null) return respond(out, 200, type, bytes, "Accept-Ranges: bytes")
        val from = match.groupValues[1].toInt()
        if (from >= bytes.size) return respond(out, 416, type, ByteArray(0), "Content-Range: bytes */${bytes.size}")
        val to = match.groupValues[2].toIntOrNull()?.coerceAtMost(bytes.size - 1) ?: (bytes.size - 1)
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
        const val RECORDING_ID = 1L
        const val RECORDING_TITLE = "偽の録画"
        private val REASONS = mapOf(200 to "OK", 204 to "No Content", 206 to "Partial Content", 404 to "Not Found", 416 to "Range Not Satisfiable")
    }
}
