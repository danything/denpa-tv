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

    fun request(url: URI, method: String = "GET", json: String? = null): Response {
        val connection = url.toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = method
            connection.instanceFollowRedirects = true
            if (json != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(json.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            return Response(code, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    fun get(url: URI): String {
        val res = request(url)
        if (!res.ok) throw IOException("${res.code} $url")
        return res.text()
    }
}
