package io.github.danything.denpatv

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue

/**
 * denpa の代わりに JSON を返す小さなサーバ。JDK の HttpServer で足りる (依存を足さない)。
 * 届いた要求は `requests` に溜める
 */
class FakeDenpa : AutoCloseable {
    data class Request(val method: String, val target: String, val body: String)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val responses = LinkedBlockingQueue<Pair<Int, String>>()
    val requests = LinkedBlockingQueue<Request>()

    init {
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += Request(exchange.requestMethod, exchange.requestURI.toString(), body)
            val (code, text) = responses.poll() ?: (404 to "")
            val bytes = text.toByteArray()
            exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    fun enqueue(body: String, code: Int = 200) {
        responses += code to body
    }

    fun url(path: String = "/") = "http://127.0.0.1:${server.address.port}$path"

    override fun close() = server.stop(0)
}
