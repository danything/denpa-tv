package io.doany.denpatv

import io.doany.denpatv.data.WebSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 自前の WebSocket (RFC 6455)。ServerSocket で偽のサーバを立て、ハンドシェイク・マスク・ping / pong・断片・close を見る */
class WebSocketTest {
    /** 偽のサーバ。1 本だけ受けて `script` を走らせる */
    private class FakeServer(private val accept: (String) -> String? = { acceptFor(it) }, private val script: (Peer) -> Unit) : AutoCloseable {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val requestLines = LinkedBlockingQueue<List<String>>()
        val errors = LinkedBlockingQueue<Throwable>()

        init {
            thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        val input = BufferedInputStream(socket.getInputStream())
                        val lines = generateSequence { readLine(input).takeIf { it.isNotEmpty() } }.toList()
                        requestLines += lines
                        val key = lines.first { it.lowercase().startsWith("sec-websocket-key:") }.substringAfter(':').trim()
                        val out = socket.getOutputStream()
                        val accepted = accept(key)
                        out.write(
                            (
                                if (accepted == null) "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"
                                else "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accepted\r\n\r\n"
                                ).toByteArray(),
                        )
                        out.flush()
                        if (accepted != null) script(Peer(socket, input, out))
                    }
                } catch (e: Throwable) {
                    errors += e
                }
            }
        }

        val url get() = URI("ws://127.0.0.1:${server.localPort}/api/v1/channels/jk9/ws/watch?x=1")

        override fun close() = server.close()

        companion object {
            fun acceptFor(key: String): String = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
            )

            private fun readLine(input: InputStream): String {
                val out = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b < 0 || b == '\n'.code) return out.toString().trimEnd('\r')
                    out.append(b.toChar())
                }
            }
        }
    }

    /** サーバ側から見た 1 本。送るものはマスクしない (サーバの決まり)、受けるものはマスクを外す */
    private class Peer(val socket: Socket, val input: InputStream, val out: OutputStream) {
        fun frame(op: Int, payload: ByteArray, fin: Boolean = true) {
            out.write((if (fin) 0x80 else 0) or op)
            when {
                payload.size < 126 -> out.write(payload.size)
                else -> {
                    out.write(126)
                    out.write(payload.size shr 8)
                    out.write(payload.size and 0xff)
                }
            }
            out.write(payload)
            out.flush()
        }

        fun text(text: String) = frame(0x1, text.toByteArray())

        /** 1 つ受ける。マスクが掛かっていなければ失敗 */
        fun read(): Pair<Int, ByteArray> {
            val first = input.read()
            val second = input.read()
            if (second and 0x80 == 0) throw IOException("端末から来たフレームにマスクがありません")
            var length = second and 0x7f
            if (length == 126) length = (input.read() shl 8) or input.read()
            val mask = ByteArray(4).also { readFully(it) }
            val payload = ByteArray(length).also { readFully(it) }
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            return (first and 0x0f) to payload
        }

        private fun readFully(buffer: ByteArray) {
            var at = 0
            while (at < buffer.size) at += input.read(buffer, at, buffer.size - at).also { if (it < 0) throw IOException("切れました") }
        }
    }

    @Test
    fun ハンドシェイクして文字のフレームを受け_送るものにはマスクを掛ける() {
        val got = LinkedBlockingQueue<String>()
        FakeServer { peer ->
            peer.text("こんにちは")
            val (op, payload) = peer.read()
            got += "$op:${payload.toString(Charsets.UTF_8)}"
            peer.frame(0x8, byteArrayOf(0x03, 0xe8.toByte()))
            // 閉じる返事が来る
            got += "close:${peer.read().first}"
        }.use { server ->
            val ws = WebSocket.connect(server.url, readTimeoutMs = 5_000)
            val lines = server.requestLines.poll(5, TimeUnit.SECONDS)!!
            assertEquals("GET /api/v1/channels/jk9/ws/watch?x=1 HTTP/1.1", lines[0])
            assertTrue(lines.contains("Upgrade: websocket"))
            assertTrue(lines.contains("Sec-WebSocket-Version: 13"))
            assertTrue(lines.contains("Host: 127.0.0.1:${server.server.localPort}"))
            assertEquals(WebSocket.Message.Text("こんにちは"), ws.receive())
            ws.send("""{"type":"pong"}""")
            assertEquals(WebSocket.Message.Closed(1000), ws.receive())
            assertEquals("1:{\"type\":\"pong\"}", got.poll(5, TimeUnit.SECONDS))
            assertEquals("close:8", got.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun ping_には同じ中身の_pong_を返し_断片はつないで_1_つにする() {
        val got = LinkedBlockingQueue<Pair<Int, String>>()
        val long = "あ".repeat(100) // 300 バイト (長さが 2 バイトのフレーム)
        FakeServer { peer ->
            peer.frame(0x9, "p1".toByteArray())
            got += peer.read().let { it.first to it.second.toString(Charsets.UTF_8) }
            peer.frame(0x1, "前半".toByteArray(), fin = false)
            peer.frame(0xA, ByteArray(0))
            peer.frame(0x0, long.toByteArray(), fin = true)
            peer.frame(0x8, ByteArray(0))
            peer.read()
        }.use { server ->
            val ws = WebSocket.connect(server.url, readTimeoutMs = 5_000)
            assertEquals(WebSocket.Message.Text("前半$long"), ws.receive())
            assertEquals(0xA to "p1", got.poll(5, TimeUnit.SECONDS))
            // コードの無い close は 1005
            assertEquals(WebSocket.Message.Closed(WebSocket.NO_STATUS), ws.receive())
        }
    }

    @Test
    fun 黙ったままなら_ping_を送り_それでも黙っていれば切れたとみなす() {
        val got = LinkedBlockingQueue<Int>()
        FakeServer { peer ->
            got += peer.read().first
            // pong を返さずに黙る
            Thread.sleep(2_000)
        }.use { server ->
            val ws = WebSocket.connect(server.url, readTimeoutMs = 200)
            try {
                ws.receive()
                fail("黙ったままのサーバから受けました")
            } catch (_: IOException) {
            }
            assertEquals(0x9, got.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun 返事の_Sec_WebSocket_Accept_が合わなければ繋がない() {
        FakeServer(accept = { "違う" }) {}.use { server ->
            try {
                WebSocket.connect(server.url, readTimeoutMs = 5_000)
                fail("合わない返事で繋ぎました")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("合いません"))
            }
        }
        FakeServer(accept = { null }) {}.use { server ->
            try {
                WebSocket.connect(server.url, readTimeoutMs = 5_000)
                fail("断られたのに繋ぎました")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("403"))
            }
        }
    }

    @Test
    fun 自前の_Base64_は_JDK_と同じ() {
        for (size in 0..20) {
            val bytes = ByteArray(size) { (it * 37 + 11).toByte() }
            assertEquals(Base64.getEncoder().encodeToString(bytes), WebSocket.base64(bytes))
        }
    }
}
