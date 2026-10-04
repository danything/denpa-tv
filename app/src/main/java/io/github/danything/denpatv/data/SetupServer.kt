package io.github.danything.denpatv.data

import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * テレビの中の小さな Web サーバ。**スマホから denpa の URL を入れてもらう**ためのもの
 * (テレビのリモコンで URL を打つのはつらい)。繋ぐ画面を開いている間だけ待ち受ける。
 *
 * - 依存は足さない (ServerSocket で HTTP/1.0 程度を話す)。扱うのは1枚のフォームの GET と POST だけ
 * - URL に**推測できない道** (`/<16 バイトの乱数>/`) を入れる。同じ LAN の誰かが当てずっぽうで叩いても 404。
 *   道は QR にしか出ない
 */
class SetupServer(
    /** フォームが送られたら呼ぶ。スマホに返す HTML を返す (テレビ側の確かめが済むまで待つ) */
    private val onSubmit: suspend (url: String) -> String,
) : AutoCloseable {
    val token: String = randomToken()
    private val socket = ServerSocket(0)
    val port: Int get() = socket.localPort

    init {
        thread(name = "denpa-setup", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                thread(isDaemon = true) { client.use { serve(it) } }
            }
        }
    }

    /** QR に出す URL。テレビの LAN の IP が分からなければ null */
    fun url(): String? = lanAddress()?.let { "http://$it:$port/$token/" }

    private fun serve(client: Socket) {
        client.soTimeout = 15_000
        val request = try {
            parseRequest(BufferedInputStream(client.getInputStream()))
        } catch (_: IOException) {
            null
        } ?: return
        val out = client.getOutputStream()
        val path = request.path.substringBefore('?')
        if (path != "/$token/" && path != "/$token") {
            out.write(response(404, "text/plain", "not found"))
            return
        }
        val body = when (request.method) {
            "GET" -> formPage(null)
            "POST" -> {
                val url = parseForm(request.body)["url"].orEmpty()
                runBlocking { onSubmit(url) }
            }
            else -> {
                out.write(response(405, "text/plain", "method not allowed"))
                return
            }
        }
        out.write(response(200, "text/html; charset=utf-8", body))
    }

    override fun close() = socket.close()

    companion object {
        private fun randomToken(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** テレビの LAN の IPv4 (192.168.x.x など)。Wi-Fi か有線の、上がっているもの */
        fun lanAddress(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }
}

data class HttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)

/**
 * HTTP の要求を1つ読む。**大きすぎるもの (64 KB 超) は読まない** — フォームに入るのは URL 1つだけ
 */
fun parseRequest(input: InputStream): HttpRequest? {
    val head = StringBuilder()
    while (!head.endsWith("\r\n\r\n")) {
        val b = input.read()
        if (b < 0) return null
        head.append(b.toChar())
        if (head.length > 16 * 1024) return null
    }
    val lines = head.toString().split("\r\n")
    val parts = lines.first().split(' ')
    if (parts.size < 2) return null
    val headers = lines.drop(1).filter { ':' in it }
        .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
    val length = headers["content-length"]?.toIntOrNull() ?: 0
    if (length > 64 * 1024) return null
    val body = ByteArray(length)
    var read = 0
    while (read < length) {
        val n = input.read(body, read, length - read)
        if (n < 0) break
        read += n
    }
    return HttpRequest(parts[0], parts[1], headers, body.copyOf(read).toString(Charsets.UTF_8))
}

/** `application/x-www-form-urlencoded` を読む */
fun parseForm(body: String): Map<String, String> = body.split('&').filter { '=' in it }.associate {
    URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
}

private fun response(code: Int, type: String, body: String): ByteArray {
    val bytes = body.toByteArray()
    val reason = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Error" }
    val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
        "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
    return head.toByteArray() + bytes
}

private fun escape(text: String) =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun page(body: String) = """<!doctype html><html lang="ja"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>denpa TV</title>
<style>body{font-family:system-ui,sans-serif;margin:24px;line-height:1.6;background:#13171f;color:#eee}
input,button{font-size:18px;padding:12px;width:100%;box-sizing:border-box;border-radius:8px;border:0;margin-top:8px}
button{background:#0172ad;color:#fff}a{color:#7cc4ff}.code{font-size:32px;letter-spacing:4px;font-weight:bold}
.error{color:#ff8a80}</style></head><body>$body</body></html>"""

/** スマホに出すフォーム */
fun formPage(error: String?) = page(
    """<h1>denpa TV を繋ぐ</h1>
<p>ブラウザで denpa を開いている URL を入れてください (例: http://192.168.1.10:3000/ や https://denpa.example.jp/)</p>
${error?.let { """<p class="error">${escape(it)}</p>""" } ?: ""}
<form method="post"><input name="url" type="url" inputmode="url" placeholder="https://" required autofocus>
<button type="submit">テレビに設定する</button></form>""",
)

/** 家の LAN から繋がったので、そのまま設定した */
fun donePage() = page("<h1>設定しました</h1><p>テレビの画面に戻ってください。このページは閉じてかまいません。</p>")

/**
 * denpa にテレビを登録してもらう。denpa の登録の画面へ送る (ログインが要ればそこで)。
 * テレビにも同じコードが出ているので見比べられる
 */
fun approvePage(verificationUrl: String, userCode: String) = page(
    """<h1>denpa にログインしてください</h1>
<p>テレビに出ているコードと同じか確かめてください。</p><p class="code">${escape(userCode)}</p>
<p><a href="${escape(verificationUrl)}">denpa を開く</a> (自動で移ります)</p>
<script>setTimeout(function(){location.href=${escapeJs(verificationUrl)}},1500)</script>""",
)

private fun escapeJs(text: String) =
    "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\""
