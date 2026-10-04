package io.github.danything.denpatv.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * denpa の API を叩く素の HTTP。**OS の HttpURLConnection で足りる** — 叩くのは家の LAN の
 * denpa の JSON だけで、HTTP/2 も QUIC も効かない (docs/libraries.md)。呼ぶ側が IO の上で呼ぶ
 */
object Http {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    class Response(val code: Int, val body: ByteArray) {
        val ok: Boolean get() = code in 200..299
        fun text(): String = body.toString(Charsets.UTF_8)
    }

    /**
     * `token` があれば `Authorization: Bearer` を付ける (家の外の denpa に登録したとき。README の「denpa に繋ぐ」)
     */
    fun request(url: URI, method: String = "GET", json: String? = null, token: String? = null): Response {
        val connection = url.toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = method
            connection.instanceFollowRedirects = true
            bearer(token)?.let { connection.setRequestProperty("Authorization", it) }
            // GET 以外には本文が無くても Content-Type を付ける。denpa (SvelteKit) は Content-Type も Origin も無い
            // GET 以外を「よそのサイトからのフォーム送信」と見なして 403 で断る (本文の無い DELETE が消せなかった)
            if (method != "GET") connection.setRequestProperty("Content-Type", "application/json")
            if (json != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(json.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            return Response(code, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    fun get(url: URI, token: String? = null): String {
        val res = request(url, token = token)
        if (res.code == 401) throw Unauthorized(url)
        if (!res.ok) throw IOException("${res.code} $url")
        return res.text()
    }

    /** Authorization の値。トークンが無ければ null */
    fun bearer(token: String?): String? = token?.takeIf { it.isNotEmpty() }?.let { "Bearer $it" }
}

/**
 * denpa に断られた (401)。**トークンが無効になった (外された・期限切れ) か、denpa が家の外と見なした。**
 * 呼ぶ側はトークンを捨てて、繋ぐ画面に戻す
 */
class Unauthorized(url: URI) : IOException("401 $url")
