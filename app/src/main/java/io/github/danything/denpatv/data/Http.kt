package io.github.danything.denpatv.data

import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** JSON の読み書き。知らない鍵は読み捨てる (denpa も GitHub も鍵を足すことがある) */
val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * 素の HTTP (denpa の API・GitHub のリリース・小さな画像)。**OS の HttpURLConnection で足りる** — 大きな流れは無く、
 * HTTP/2 も QUIC も効かない (docs/libraries.md)。呼ぶ側が IO の上で呼ぶ
 */
object Http {
    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 30_000

    class Response(val code: Int, val body: ByteArray) {
        val ok: Boolean get() = code in 200..299
        fun text(): String = body.toString(Charsets.UTF_8)
    }

    /**
     * `token` があれば `Authorization: Bearer` を付ける (家の外の denpa に登録したとき。README の「denpa に繋ぐ」)。
     * `headers` はほかに付けるもの (GitHub のリリースを引くときの Accept など)
     */
    fun request(
        url: URI,
        method: String = "GET",
        json: String? = null,
        token: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val connection = connection(url, token, headers)
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = true
            // GET 以外には本文が無くても Content-Type を付ける。denpa (SvelteKit) は Content-Type も Origin も無い
            // GET 以外を「よそのサイトからのフォーム送信」と見なして 403 で断る
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

    /**
     * まだ繋いでいない HttpURLConnection (時間切れ・トークン・ヘッダを付けたもの)。流しっぱなしの本文
     * (知らせ・字幕・APK) は、これで開いて呼ぶ側が読む。閉じる (`disconnect`) のも呼ぶ側
     */
    fun connection(
        url: URI,
        token: String? = null,
        headers: Map<String, String> = emptyMap(),
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): HttpURLConnection = (url.toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = readTimeoutMs
        bearer(token)?.let { setRequestProperty("Authorization", it) }
        headers.forEach { (name, value) -> setRequestProperty(name, value) }
    }

    /** Authorization の値。トークンが無ければ null */
    fun bearer(token: String?): String? = token?.takeIf { it.isNotEmpty() }?.let { "Bearer $it" }
}

/**
 * denpa に断られた (401)。**トークンが無効になった (外された・期限切れ) か、denpa が家の外と見なした。**
 * 呼ぶ側はトークンを捨てて、繋ぐ画面に戻す
 */
class Unauthorized(url: URI) : IOException("401 $url")
