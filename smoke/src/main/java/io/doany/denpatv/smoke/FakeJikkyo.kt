package io.doany.denpatv.smoke

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * エミュレータの中で動かす偽の過去ログ API (`SmokeTest`)。**決まった口 ([PORT]) で待つ** — アプリの実況の取得先はビルドのときに決まるので
 * (`DENPA_TV_JIKKYO_KAKOLOG=http://127.0.0.1:18080`。app/build.gradle.kts・docs/development.md)。
 *
 * `GET /api/kakolog/<channel>?starttime=&endtime=` に、区間の中に 0.25 秒おきのコメントを返す。どれも緑の大きな「■」なので、
 * スクリーンショットで流れたかが分かる (`SmokeTest` の緑の点)。届いた要求は `requests` に、付いてきた Authorization・Referer と一緒に溜める
 */
class FakeJikkyo(
    /** スクリーンショットを撮るとき。作り物の普通のコメント ([SHOWCASE]) を、ところどころ色・大きさを替えて返す */
    private val showcase: Boolean = false,
) : AutoCloseable {
    private val server = ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }
    val requests = ConcurrentLinkedQueue<String>()

    init {
        thread(name = "FakeJikkyo", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                thread(isDaemon = true) { socket.use { handle(it.getInputStream(), it.getOutputStream()) } }
            }
        }
    }

    private fun handle(stream: InputStream, out: java.io.OutputStream) {
        try {
            val input = BufferedInputStream(stream)
            val target = readLine(input)?.split(' ')?.getOrNull(1) ?: return
            val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }.map { it.lowercase() }.toList()
            val sent = headers.filter { it.startsWith("authorization:") || it.startsWith("referer:") }
            requests += "GET ${target.substringBefore('?')}" + if (sent.isEmpty()) "" else " $sent"
            val query = URI(target).rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            val from = (query["starttime"]?.toLongOrNull() ?: 0) * 1000
            val to = (query["endtime"]?.toLongOrNull() ?: 0) * 1000
            val chats = generateSequence(from - Math.floorMod(from, STEP_MS)) { it + STEP_MS }.takeWhile { it < to }.filter { it >= from }
                .joinToString(",") { ms ->
                    val (mail, text) = if (showcase) SHOWCASE[Math.floorMod(ms / STEP_MS, SHOWCASE.size.toLong()).toInt()] else "184 green big" to TEXT
                    """{"chat":{"thread":"1","no":"1","date":"${ms / 1000}","date_usec":"${ms % 1000 * 1000}","mail":"$mail","content":"$text"}}"""
                }
            val body = """{"packet":[$chats]}""".toByteArray()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
        } catch (_: IOException) {
        }
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else line.toString("ISO-8859-1")
            if (b == '\n'.code) return line.toString("ISO-8859-1").trimEnd('\r')
            line.write(b)
        }
    }

    override fun close() = server.close()

    companion object {
        /** 待つ口。CI の apks の列が `DENPA_TV_JIKKYO_*` に入れるものと同じ */
        const val PORT = 18080
        /** コメントの間 (ミリ秒) */
        private const val STEP_MS = 250L
        const val TEXT = "■■■■■■"
        /** スクリーンショットのコメント (作り物)。`mail` と本文 */
        private val SHOWCASE = listOf(
            "184" to "わこつ",
            "184" to "きたー",
            "184" to "作画いいな",
            "184 yellow" to "鍛冶師かっこいい",
            "184" to "草",
            "184" to "この剣ほしい",
            "184 big" to "8888888",
            "184" to "OP いい曲",
            "184" to "勇者ざまぁ",
            "184 cyan" to "村の人やさしい",
            "184" to "ロイドさん有能",
            "184" to "王都編たのしみ",
            "184 ue" to "神回",
            "184" to "もう一話見たい",
        )

    }
}
