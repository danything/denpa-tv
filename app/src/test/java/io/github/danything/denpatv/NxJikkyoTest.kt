package io.github.danything.denpatv

import io.github.danything.denpatv.data.JikkyoComment
import io.github.danything.denpatv.data.JikkyoEndpoints
import io.github.danything.denpatv.data.JikkyoSocket
import io.github.danything.denpatv.data.JikkyoSocketListener
import io.github.danything.denpatv.data.NxJikkyo
import io.github.danything.denpatv.data.lenientJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NX-Jikkyo への繋ぎ方 (denpa の `nx.test.ts` と同じ例)。外には繋がず、偽の WebSocket と、時計を進められるコルーチンで見る。返事は自分で作った偽物
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NxJikkyoTest {
    private val t = 1_793_188_800_000L
    private val endpoints = JikkyoEndpoints("wss://nx-jikkyo.tsukumijima.net", "https://jikkyo.tsukumijima.net")
    private val commentUrl = "wss://nx-jikkyo.tsukumijima.net/api/v1/channels/jk9/ws/comment"

    private inner class FakeSocket(val url: String, val listener: JikkyoSocketListener, val openedAt: Long) : JikkyoSocket {
        val sent = mutableListOf<JsonElement>()
        var closed = false

        override fun send(text: String) {
            sent += lenientJson.parseToJsonElement(text)
        }

        override fun close() {
            closed = true
        }
    }

    private inner class Harness {
        val scope = TestScope()
        val sockets = mutableListOf<FakeSocket>()
        val got = mutableListOf<JikkyoComment>()
        val states = mutableListOf<Boolean>()
        val nx = NxJikkyo(
            "jk9",
            object : NxJikkyo.Events {
                override fun comments(comments: List<JikkyoComment>) {
                    got += comments
                }

                override fun state(ok: Boolean) {
                    states += ok
                }

                override fun odd(what: String) = Unit
            },
            scope,
            endpoints,
            { url, listener -> FakeSocket(url, listener, scope.testScheduler.currentTime).also { sockets += it } },
        )

        fun run() = scope.runCurrent()

        fun open(socket: FakeSocket) {
            socket.listener.onOpen()
            run()
        }

        fun receive(socket: FakeSocket, json: String) {
            socket.listener.onMessage(json)
            run()
        }

        fun drop(socket: FakeSocket, code: Int = 1006) {
            socket.listener.onClose(code)
            run()
        }

        /** 視聴の WS を開いて room まで受ける。コメントの WS を返す */
        fun connect(from: Int = 0): FakeSocket {
            val watch = sockets[from]
            open(watch)
            receive(watch, """{"type":"seat","data":{"keepIntervalSec":30}}""")
            receive(watch, """{"type":"room","data":{"messageServer":{"uri":"$commentUrl"},"threadId":"777","yourPostKey":"key","vposBaseTime":"2026-10-28T11:00:00Z"}}""")
            val comment = sockets[from + 1]
            open(comment)
            return comment
        }

        fun start() {
            nx.start()
            run()
        }
    }

    private fun JsonElement.type() = (this as? JsonObject)?.get("type")?.jsonPrimitive?.content

    @Test
    fun 視聴の_WS_から_room_の_URL_のコメントの_WS_に繋ぎ_直近_100_件からコメントを受ける() {
        val h = Harness()
        h.start()
        assertEquals("wss://nx-jikkyo.tsukumijima.net/api/v1/channels/jk9/ws/watch", h.sockets[0].url)
        val comment = h.connect()
        assertEquals(lenientJson.parseToJsonElement("""{"type":"startWatching","data":{"reconnect":false}}"""), h.sockets[0].sent[0])
        assertEquals(commentUrl, comment.url)
        val thread = (comment.sent[0] as JsonArray).first { "thread" in it.jsonObject }
        assertEquals(
            lenientJson.parseToJsonElement("""{"thread":{"version":"20061206","thread":"777","threadkey":"key","res_from":-100}}"""),
            thread,
        )
        h.receive(comment, """{"thread":{"resultcode":0}}""")
        h.receive(comment, """{"chat":{"date":${t / 1000},"date_usec":0,"content":"わこつ","user_id":"x"}}""")
        assertEquals(listOf(JikkyoComment(t, "わこつ")), h.got)
    }

    @Test
    fun ping_には_pong_seat_の間隔で_keepSeat_を返す() {
        val h = Harness()
        h.start()
        h.connect()
        val watch = h.sockets[0]
        h.receive(watch, """{"type":"ping"}""")
        assertTrue(watch.sent.any { it.type() == "pong" })
        h.scope.advanceTimeBy(29_999)
        h.run()
        assertTrue(watch.sent.none { it.type() == "keepSeat" })
        h.scope.advanceTimeBy(1)
        h.run()
        assertTrue(watch.sent.any { it.type() == "keepSeat" })
    }

    @Test
    fun 切れたら_1_秒_2_秒と間を空けて繋ぎ直し_5_回続けて失敗したら読めないと言う() {
        val h = Harness()
        h.start()
        val waits = mutableListOf<Long>()
        repeat(5) {
            val droppedAt = h.scope.testScheduler.currentTime
            h.drop(h.sockets.last())
            val before = h.sockets.size
            h.scope.advanceTimeBy(60_001)
            h.run()
            assertEquals(before + 1, h.sockets.size)
            waits += h.sockets.last().openedAt - droppedAt
        }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L), waits)
        assertEquals(listOf(false), h.states)
        // 戻ったら読めると言う
        val comment = h.connect(h.sockets.size - 1)
        h.receive(comment, """{"thread":{"resultcode":0}}""")
        assertEquals(listOf(false, true), h.states)
    }

    @Test
    fun 四時にサーバがきれいに閉じたら_失敗に数えず_1_秒で視聴の_WS_から繋ぎ直す() {
        val h = Harness()
        h.start()
        val comment = h.connect()
        h.receive(comment, """{"thread":{"resultcode":0}}""")
        val at = h.scope.testScheduler.currentTime
        h.drop(h.sockets[0], 1000)
        h.scope.advanceTimeBy(999)
        h.run()
        assertEquals(2, h.sockets.size)
        h.scope.advanceTimeBy(1)
        h.run()
        assertEquals(3, h.sockets.size)
        assertEquals(at + 1000, h.sockets.last().openedAt)
        assertTrue(h.sockets.last().url.endsWith("/ws/watch"))
        assertEquals(emptyList<Boolean>(), h.states)
    }

    @Test
    fun 遡るのは_1_回だけ_when_付きで_1000_件まで() {
        val h = Harness()
        h.start()
        val comment = h.connect()
        h.nx.backfill(t)
        h.nx.backfill(t - 60_000)
        val asked = comment.sent.flatMap { it as? JsonArray ?: listOf(it) }
            .filter { (it as? JsonObject)?.get("thread")?.jsonObject?.get("when") != null }
        assertEquals(listOf(lenientJson.parseToJsonElement("""{"thread":{"version":"20061206","thread":"777","threadkey":"key","res_from":-1000,"when":${t / 1000}}}""")), asked)
        assertEquals(t / 1000, asked[0].jsonObject["thread"]!!.jsonObject["when"]!!.jsonPrimitive.long)
    }

    @Test
    fun room_のコメントの_WS_が_NX_Jikkyo_の外を指していたら繋がない() {
        val h = Harness()
        h.start()
        val watch = h.sockets[0]
        h.open(watch)
        h.receive(watch, """{"type":"room","data":{"messageServer":{"uri":"wss://example.com/ws"},"threadId":"1"}}""")
        assertEquals(1, h.sockets.size)
        assertEquals("/api/v1/x?y=1", endpoints.commentPath("wss://nx-jikkyo.tsukumijima.net/api/v1/x?y=1"))
        assertNull(endpoints.commentPath("ws://nx-jikkyo.tsukumijima.net/api"))
    }

    @Test
    fun 閉じたら繋ぎ直さない() {
        val h = Harness()
        h.start()
        h.connect()
        h.nx.close()
        h.run()
        assertTrue(h.sockets.all { it.closed })
        h.scope.advanceTimeBy(120_000)
        h.run()
        assertEquals(2, h.sockets.size)
    }
}
