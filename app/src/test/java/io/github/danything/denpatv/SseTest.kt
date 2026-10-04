package io.github.danything.denpatv

import io.github.danything.denpatv.data.DenpaEvent
import io.github.danything.denpatv.data.SseEvent
import io.github.danything.denpatv.data.SseParser
import io.github.danything.denpatv.data.Unauthorized
import io.github.danything.denpatv.data.denpaEvent
import io.github.danything.denpatv.data.followEvents
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class SseTest {
    @Test
    fun 空行で1件ずつ返す() {
        val parser = SseParser()
        assertEquals(
            listOf(SseEvent("recordings", "1"), SseEvent("encode", """{"recordingId":12}""")),
            parser.feed("event: recordings\ndata: 1\n\nevent: encode\ndata: {\"recordingId\":12}\n\n"),
        )
    }

    @Test
    fun 複数行の_data_は改行で繋ぐ() {
        assertEquals(listOf(SseEvent("message", "a\nb\n")), SseParser().feed("data: a\ndata:b\ndata\n\n"))
    }

    @Test
    fun 書き添えと_data_の無い区切りは読み捨てる() {
        val parser = SseParser()
        // denpa は繋いですぐ `: connected` を送る
        assertEquals(emptyList<SseEvent>(), parser.feed(": connected\n\nid: 3\nretry: 1000\n\n"))
        assertEquals(listOf(SseEvent("ping", "1")), parser.feed(": x\nevent: ping\ndata: 1\n\n"))
    }

    @Test
    fun CRLF_と_CR_も行の終わり() {
        val parser = SseParser()
        assertEquals(listOf(SseEvent("a", "1")), parser.feed("event: a\r\ndata: 1\r\n\r\n"))
        assertEquals(listOf(SseEvent("b", "2")), parser.feed("event: b\rdata: 2\r\r"))
    }

    @Test
    fun 途中で切れて届いてもつなぐ() {
        val parser = SseParser()
        val out = listOf("\uFEFFeve", "nt: recor", "dings\r", "\ndata: 1\r", "\n", "\r\n").flatMap(parser::feed)
        assertEquals(listOf(SseEvent("recordings", "1")), out)
    }

    @Test
    fun denpa_の知らせに読む() {
        assertNull(denpaEvent(SseEvent("ping", "1")))
        assertEquals(DenpaEvent.Changed("recordings"), denpaEvent(SseEvent("recordings", "1")))
        assertEquals(DenpaEvent.Changed("logos"), denpaEvent(SseEvent("logos", "1")))
        assertEquals(
            DenpaEvent.Encode(12, 0.5f),
            denpaEvent(SseEvent("encode", """{"recordingId":12,"percent":0.5,"etaMs":null,"log":"","extra":1}""")),
        )
        // docs/api.md の例は百分率
        assertEquals(DenpaEvent.Encode(12, 0.425f), denpaEvent(SseEvent("encode", """{"recordingId":12,"percent":42.5}""")))
        val warnings = mutableListOf<String>()
        assertNull(denpaEvent(SseEvent("encode", """{"id":12}"""), warnings::add))
        assertNull(denpaEvent(SseEvent("encode", "not json"), warnings::add))
        assertEquals(2, warnings.size)
    }

    @Test
    fun 切れたら繋ぎ直し_401_で止まる() = runBlocking {
        FakeDenpa().use { denpa ->
            denpa.enqueue(": connected\n\nevent: recordings\ndata: 1\n\nevent: ping\ndata: 1\n\n")
            denpa.enqueue("", code = 500)
            denpa.enqueue(": connected\n\n")
            denpa.enqueue("", code = 401)
            val events = mutableListOf<DenpaEvent>()
            val waits = mutableListOf<Long>()
            val thrown = runCatching {
                followEvents(URI(denpa.url("/api/events")), "denpa_x", onEvent = { events += it }, wait = { waits += it })
            }.exceptionOrNull()
            assertTrue(thrown is Unauthorized)
            // 繋ぐたびに Opened (読み直しの合図)
            assertEquals(listOf(DenpaEvent.Opened, DenpaEvent.Changed("recordings"), DenpaEvent.Opened), events)
            // すぐ切れるあいだは倍々 (10 秒保つまで 1 秒に戻さない)
            assertEquals(listOf(1_000L, 2_000L, 4_000L), waits)
            assertEquals("Bearer denpa_x", denpa.requests.take().authorization)
        }
    }
}
