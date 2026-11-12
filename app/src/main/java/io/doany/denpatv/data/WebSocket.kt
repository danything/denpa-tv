package io.doany.denpatv.data

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * **WebSocket の受け手 (RFC 6455) を自前で持つ。** 使うのは実況のコメント (NX-Jikkyo) だけで、要るのは文字のフレーム・ping / pong・close と、
 * 送るものにマスクを掛けることくらい (拡張も副プロトコルも使わない)。OkHttp を外したのでライブラリは足さない (docs/libraries.md)。
 *
 * - `wss:` は OS の `SSLSocketFactory` の上に載せ、繋いだあと**ホスト名を OS の `HostnameVerifier` で確かめる**
 *   (`SSLSocketFactory` は証明書の鎖は確かめるが、名前は確かめない)
 * - 受けるのは 1 本のスレッド (`receive` を呼び続ける)、送るのはどのスレッドからでもよい (`send` に鍵を掛ける)
 * - サーバからの ping には同じ中身の pong を返す。フレームの断片 (continuation) はつないで 1 つにする。1 つの文は `MAX_MESSAGE` まで
 */
class WebSocket private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
) : Closeable {
    private val random = SecureRandom()
    @Volatile
    private var closing = false

    /** 受けたもの。`Text` は文字のフレーム、`Closed` はサーバが閉じた (コード。無ければ 1005) */
    sealed interface Message {
        data class Text(val text: String) : Message
        data class Closed(val code: Int) : Message
    }

    /** 文字のフレームを送る (マスクを掛ける) */
    fun send(text: String) = sendFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))

    /** ping を送る (死んだ繋ぎを見つけるため。返事の pong は読み捨てる) */
    fun ping() = sendFrame(OP_PING, ByteArray(0))

    /**
     * 次の文字のフレームを待つ。サーバが閉じたら `Closed`。読みの時間切れ・切れたら IOException。
     * ping には pong を返し、pong とバイナリのフレームは読み捨てる
     */
    fun receive(): Message {
        val message = ByteArrayOutputStream()
        var opcode = -1
        while (true) {
            val first = firstByte()
            val second = readByte()
            val fin = first and 0x80 != 0
            val op = first and 0x0f
            val masked = second and 0x80 != 0
            var length = (second and 0x7f).toLong()
            if (length == 126L) length = ((readByte() shl 8) or readByte()).toLong()
            else if (length == 127L) length = (0 until 8).fold(0L) { acc, _ -> (acc shl 8) or readByte().toLong() }
            if (length < 0 || length > MAX_MESSAGE) throw IOException("WebSocket のフレームが大きすぎます: $length")
            val mask = if (masked) ByteArray(4).also { readFully(it) } else null
            val payload = ByteArray(length.toInt()).also { readFully(it) }
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (op) {
                OP_PING -> sendFrame(OP_PONG, payload)
                OP_PONG -> Unit
                OP_CLOSE -> {
                    val code = if (payload.size >= 2) ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff) else NO_STATUS
                    // 閉じる返事を返してから切る (RFC 6455 5.5.1)
                    closing = true
                    runCatching { sendFrame(OP_CLOSE, payload.copyOf(minOf(payload.size, 2))) }
                    runCatching { socket.close() }
                    return Message.Closed(code)
                }
                OP_TEXT, OP_BINARY, OP_CONTINUATION -> {
                    if (op != OP_CONTINUATION) {
                        opcode = op
                        message.reset()
                    }
                    if (message.size() + payload.size > MAX_MESSAGE) throw IOException("WebSocket の文が大きすぎます")
                    message.write(payload)
                    if (fin && opcode == OP_TEXT) return Message.Text(message.toString(Charsets.UTF_8.name()))
                }
                else -> throw IOException("知らない WebSocket のフレームです: $op")
            }
        }
    }

    /** 閉じる。受けているスレッドは IOException で戻る */
    override fun close() {
        if (closing) return
        closing = true
        runCatching { sendFrame(OP_CLOSE, byteArrayOf((NORMAL shr 8).toByte(), NORMAL.toByte())) }
        runCatching { socket.close() }
    }

    /** 返事を待たずに送った ping (読みの時間切れが続けば、死んだ繋ぎ) */
    private var pinged = false

    /**
     * フレームの頭の1バイト。**黙ったまま読みの時間切れが来たら ping を送って待ち直し、もう一度来たら切れたとみなす**
     * (コメントの少ない局では何分も何も来ない。サーバは ping に pong を返す決まり)。フレームの途中の時間切れはそのまま投げる
     */
    private fun firstByte(): Int {
        while (true) {
            try {
                return readByte().also { pinged = false }
            } catch (e: SocketTimeoutException) {
                if (pinged) throw e
                pinged = true
                ping()
            }
        }
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("WebSocket が切れました")
        return b
    }

    private fun readFully(buffer: ByteArray) {
        var at = 0
        while (at < buffer.size) {
            val read = input.read(buffer, at, buffer.size - at)
            if (read < 0) throw EOFException("WebSocket が切れました")
            at += read
        }
    }

    @Synchronized
    private fun sendFrame(op: Int, payload: ByteArray) {
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or op)
        when {
            payload.size < 126 -> header.write(0x80 or payload.size)
            payload.size < 65_536 -> {
                header.write(0x80 or 126)
                header.write(payload.size shr 8)
                header.write(payload.size)
            }
            else -> {
                header.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) header.write((payload.size.toLong() shr shift).toInt())
            }
        }
        // 送るものには毎回ちがうマスクを掛ける (RFC 6455 5.3)
        val mask = ByteArray(4).also(random::nextBytes)
        header.write(mask)
        val body = ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        output.write(header.toByteArray())
        output.write(body)
        output.flush()
    }

    companion object {
        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        /** きれいに閉じた */
        const val NORMAL = 1000
        /** 閉じたフレームにコードが無かった */
        const val NO_STATUS = 1005
        /** 閉じたフレームが来ないまま切れた (ブラウザの WebSocket と同じ値) */
        const val ABNORMAL = 1006
        /** 1 つの文の上限。コメントは 1 件ずつ来るので十分 */
        private const val MAX_MESSAGE = 4L * 1024 * 1024
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /**
         * `url` (`ws:` / `wss:`) に繋いでハンドシェイクを済ませる。断られた・名前が合わなければ IOException。
         * `readTimeoutMs` は 1 つのフレームを待つ上限 (0 なら待ち続ける)
         */
        fun connect(url: URI, readTimeoutMs: Int, connectTimeoutMs: Int = Http.CONNECT_TIMEOUT_MS): WebSocket {
            val secure = when (url.scheme) {
                "wss" -> true
                "ws" -> false
                else -> throw IOException("WebSocket の URL ではありません: $url")
            }
            val host = url.host ?: throw IOException("WebSocket の URL にホストがありません: $url")
            val port = if (url.port > 0) url.port else if (secure) 443 else 80
            val raw = Socket()
            try {
                raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
                raw.soTimeout = connectTimeoutMs
                val socket = if (secure) {
                    val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
                    tls.startHandshake()
                    if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session)) {
                        throw IOException("証明書の名前が $host と合いません")
                    }
                    tls
                } else raw
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                handshake(url, host, port, secure, input, output)
                socket.soTimeout = readTimeoutMs
                return WebSocket(socket, input, output)
            } catch (e: Exception) {
                runCatching { raw.close() }
                throw e as? IOException ?: IOException(e)
            }
        }

        private fun handshake(url: URI, host: String, port: Int, secure: Boolean, input: InputStream, output: OutputStream) {
            val key = base64(ByteArray(16).also(SecureRandom()::nextBytes))
            val path = (url.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (url.rawQuery?.let { "?$it" } ?: "")
            val hostHeader = if (port == (if (secure) 443 else 80)) host else "$host:$port"
            val request = "GET $path HTTP/1.1\r\n" +
                "Host: $hostHeader\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "\r\n"
            output.write(request.toByteArray(Charsets.US_ASCII))
            output.flush()
            val lines = readHead(input)
            val status = lines.firstOrNull().orEmpty()
            if (status.split(' ').getOrNull(1) != "101") throw IOException("WebSocket に繋げません: $status")
            val headers = lines.drop(1).mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon < 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
            }.toMap()
            val want = base64(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)))
            if (headers["sec-websocket-accept"] != want) throw IOException("WebSocket の返事が合いません")
        }

        /**
         * Base64 (パディング付き)。`java.util.Base64` は API 26 から、`android.util.Base64` は JVM の単体テストで動かないので、ここで書く
         */
        internal fun base64(bytes: ByteArray): String {
            val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val out = StringBuilder((bytes.size + 2) / 3 * 4)
            for (i in bytes.indices step 3) {
                val n = ((bytes[i].toInt() and 0xff) shl 16) or
                    (((bytes.getOrNull(i + 1)?.toInt() ?: 0) and 0xff) shl 8) or
                    ((bytes.getOrNull(i + 2)?.toInt() ?: 0) and 0xff)
                out.append(table[(n shr 18) and 63]).append(table[(n shr 12) and 63])
                out.append(if (i + 1 < bytes.size) table[(n shr 6) and 63] else '=')
                out.append(if (i + 2 < bytes.size) table[n and 63] else '=')
            }
            return out.toString()
        }

        /** 応答の頭 (空行まで) を行に分けて読む */
        private fun readHead(input: InputStream): List<String> {
            val bytes = ByteArrayOutputStream()
            var tail = 0
            while (tail != 0x0d0a0d0a) {
                val b = input.read()
                if (b < 0) throw EOFException("WebSocket の返事が途中で切れました")
                bytes.write(b)
                tail = (tail shl 8) or b
                if (bytes.size() > 16 * 1024) throw IOException("WebSocket の返事の頭が長すぎます")
            }
            return bytes.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
        }
    }
}
