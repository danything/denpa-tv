package io.github.danything.denpatv

import io.github.danything.denpatv.data.SetupServer
import io.github.danything.denpatv.data.parseForm
import io.github.danything.denpatv.data.parseRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class SetupServerTest {
    @Test
    fun 要求とフォームを読む() {
        val raw = "POST /abc/ HTTP/1.1\r\nHost: tv\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: 34\r\n\r\nurl=https%3A%2F%2Fdp.example%2Fx+y"
        val request = parseRequest(raw.byteInputStream())!!
        assertEquals("POST", request.method)
        assertEquals("/abc/", request.path)
        assertEquals("tv", request.headers["host"])
        assertEquals("https://dp.example/x y", parseForm(request.body)["url"])
    }

    @Test
    fun 途中で切れた要求や大きすぎる要求は読まない() {
        assertNull(parseRequest("GET / HTTP/1.1\r\nHost".byteInputStream()))
        assertNull(parseRequest("POST / HTTP/1.1\r\nContent-Length: 999999\r\n\r\n".byteInputStream()))
    }

    /** 道を知らなければ 404。知っていればフォームが出て、送った URL がテレビに届く */
    @Test
    fun 推測できない道でだけ応える() {
        var submitted: String? = null
        SetupServer { submitted = it; "<p>ok</p>" }.use { server ->
            val base = "http://127.0.0.1:${server.port}"
            assertEquals(404, (URL("$base/").openConnection() as HttpURLConnection).responseCode)
            assertEquals(404, (URL("$base/wrong/").openConnection() as HttpURLConnection).responseCode)

            val form = URL("$base/${server.token}/").readText()
            assertTrue(form.contains("<form"))

            val post = URL("$base/${server.token}/").openConnection() as HttpURLConnection
            post.requestMethod = "POST"
            post.doOutput = true
            post.outputStream.use { it.write("url=http%3A%2F%2F192.168.1.10%3A3000".toByteArray()) }
            assertEquals(200, post.responseCode)
            assertEquals("<p>ok</p>", post.inputStream.readBytes().toString(Charsets.UTF_8))
            assertEquals("http://192.168.1.10:3000", submitted)
        }
        assertEquals(32, SetupServer { "" }.use { it.token.length })
    }
}
