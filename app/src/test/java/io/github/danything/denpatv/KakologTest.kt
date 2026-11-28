package io.github.danything.denpatv

import io.github.danything.denpatv.data.Abort
import io.github.danything.denpatv.data.DenpaJikkyoServer
import io.github.danything.denpatv.data.Http
import io.github.danything.denpatv.data.JikkyoComment
import io.github.danything.denpatv.data.JikkyoFeed
import io.github.danything.denpatv.data.JikkyoServer
import io.github.danything.denpatv.data.JikkyoTooOld
import io.github.danything.denpatv.data.Kakolog
import io.github.danything.denpatv.data.Unauthorized
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.URI

/**
 * 録画の実況のコメントを denpa のサーバの過去ログの受け口 (`api/jikkyo/kakolog`) から取る (denpa の `kakolog.test.ts` と同じ例)。
 * 偽の denpa (`FakeDenpa`) に繋ぐ。返事は自分で作った偽物。**テレビは外 (過去ログ API) へ繋がない**
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KakologTest {
    private val t = 1_793_188_800_000L
    private val day = 24 * 60 * 60_000L

    private fun comment(ms: Long, text: String) = """{"at":$ms,"text":"$text","src":"nx"}"""

    private fun server(fake: FakeDenpa, token: String? = null) = DenpaJikkyoServer(URI(fake.url("/"))) { token }

    private fun FakeDenpa.query(): Map<String, String> =
        URI(requests.take().target).rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun denpa_の受け口に区間を_ms_で訊き_アクセストークンを付ける() = FakeDenpa().use { fake ->
        fake.enqueue("""{"comments":[${comment(t + 1500, "a")},${comment(t + 2500, "b")}],"complete":true}""")
        val got = Kakolog.fetch(server(fake, "tok"), "jk1", t, t + 60_000, now = t + day)
        assertEquals(listOf(JikkyoComment(t + 1500, "a"), JikkyoComment(t + 2500, "b")), got.comments)
        assertEquals(0, got.odd)
        assertTrue(got.complete)
        val request = fake.requests.peek()!!
        assertEquals("GET", request.method)
        assertEquals("/api/jikkyo/kakolog", URI(request.target).path)
        assertEquals("Bearer tok", request.authorization)
        assertEquals(mapOf("channel" to "jk1", "from" to "$t", "to" to "${t + 60_000}"), fake.query())
    }

    @Test
    fun 終わりは今で切る() = FakeDenpa().use { fake ->
        fake.enqueue("""{"comments":[],"complete":true}""")
        Kakolog.fetch(server(fake), "jk1", t, t + 60 * 60_000, now = t + 600_500)
        assertEquals("${t + 600_500}", fake.query()["to"])
    }

    @Test
    fun 三日を超える区間は分けて訊く() = FakeDenpa().use { fake ->
        fake.enqueue("""{"comments":[],"complete":true}""")
        fake.enqueue("""{"comments":[],"complete":true}""")
        Kakolog.fetch(server(fake), "jk1", t, t + 4 * day, now = t + 10 * day)
        assertEquals("$t", fake.query()["from"])
        assertEquals("${t + 3 * day}", fake.query()["from"])
    }

    @Test
    fun hub_が取りきれなかったら_complete_は_false() = FakeDenpa().use { fake ->
        fake.enqueue("""{"comments":[${comment(t, "a")}],"complete":false}""")
        val got = Kakolog.fetch(server(fake), "jk1", t, t + 1000, now = t + day)
        assertEquals(listOf("a"), got.comments.map { it.text })
        assertFalse(got.complete)
    }

    @Test
    fun 形式のずれた行は飛ばして数え_区間の外は捨てる() = FakeDenpa().use { fake ->
        fake.enqueue("""{"comments":[${comment(t, "a")},{"text":"時刻が無い"},${comment(t + 5000, "外")}],"complete":true}""")
        val got = Kakolog.fetch(server(fake), "jk1", t, t + 1000, now = t + day)
        assertEquals(listOf("a"), got.comments.map { it.text })
        assertEquals(1, got.odd)
    }

    @Test
    fun 読めない返事は_IOException_受け口が無い古い_denpa_は_JikkyoTooOld_401_は_Unauthorized() = FakeDenpa().use { fake ->
        fun fails(expected: Class<out Exception>) {
            try {
                Kakolog.fetch(server(fake), "jk1", t, t + 1000, now = t + day)
                fail("誤りを読み流しました")
            } catch (e: IOException) {
                assertEquals(expected, e.javaClass)
            }
        }
        fake.enqueue("いまは過去ログを読めません", code = 503)
        fails(IOException::class.java)
        fake.enqueue("""{"packet":[]}""")
        fails(IOException::class.java)
        fake.enqueue("Not Found", code = 404)
        fails(JikkyoTooOld::class.java)
        fake.enqueue("", code = 401)
        fails(Unauthorized::class.java)
    }

    /** 過去ログを取り足す決まり (`JikkyoFeed.kakolog`)。denpa のサーバは偽物、時計は進められるもの */
    private class FeedHarness(var now: Long) {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val asked = mutableListOf<Pair<Long, Long>>()
        /** 返事 (HTTP のコードと本文)。区間を受け取る */
        var answer: (Long, Long) -> Http.Response = { _, _ -> ok("""{"comments":[],"complete":true}""") }
        val feed = JikkyoFeed(
            receives = false,
            scope = scope,
            server = object : JikkyoServer {
                override fun feed(json: String, abort: Abort): Http.Response = error("録画はライブの受け口を呼ばない")

                override fun kakolog(channel: String, from: Long, to: Long): Http.Response {
                    asked += from to to
                    return answer(from, to)
                }
            },
            now = { now },
            warn = {},
            io = dispatcher,
        )

        fun advance(ms: Long) {
            now += ms
            scope.advanceTimeBy(ms)
            scope.runCurrent()
        }

        companion object {
            fun ok(body: String, code: Int = 200) = Http.Response(code, body.toByteArray())
        }
    }

    @Test
    fun 録画は開いたときに全区間を取り_10_分より新しい所だけ_5_分おきに取り足す() {
        val end = t + 60 * 60_000
        val h = FeedHarness(now = end + 60_000)
        h.answer = { from, _ -> FeedHarness.ok("""{"comments":[${comment(from + 1, "c$from")}],"complete":true}""") }
        h.feed.set("jk1", true)
        h.feed.kakolog(t, { end })
        h.scope.runCurrent()
        assertEquals(listOf(t to end), h.asked)
        // 録り終えて 1 分。終わりの 9 分は過去ログにまだ入りきっていないので、5 分後にそこだけ訊き直す
        h.advance(JikkyoFeed.REFRESH_MS)
        assertEquals(listOf(t to end, (h.now - JikkyoFeed.REFRESH_MS - 10 * 60_000) to end), h.asked)
        // 埋まったら (10 分より古くなったら) もう訊かない
        h.advance(JikkyoFeed.REFRESH_MS)
        h.advance(JikkyoFeed.REFRESH_MS)
        val count = h.asked.size
        h.advance(JikkyoFeed.REFRESH_MS * 3)
        assertEquals(count, h.asked.size)
    }

    @Test
    fun 読めなければ_いまは読めない_にして_60_秒後にもう一度試す() {
        val h = FeedHarness(now = t + day)
        h.answer = { _, _ -> FeedHarness.ok("", code = 503) }
        h.feed.set("jk1", true)
        h.feed.kakolog(t, { t + 60_000 })
        h.scope.runCurrent()
        assertTrue(h.feed.down.value)
        h.answer = { _, _ -> FeedHarness.ok("""{"comments":[],"complete":true}""") }
        h.advance(59_999)
        assertEquals(1, h.asked.size)
        h.advance(1)
        assertEquals(2, h.asked.size)
        assertFalse(h.feed.down.value)
    }

    @Test
    fun hub_が取りきれなかった区間は取れた分を流し_埋めたことにせず_60_秒後に取り直す() {
        val h = FeedHarness(now = t + day)
        h.answer = { _, _ -> FeedHarness.ok("""{"comments":[${comment(t + 1500, "a")}],"complete":false}""") }
        h.feed.set("jk1", true)
        h.feed.kakolog(t, { t + 60_000 })
        h.scope.runCurrent()
        assertTrue(h.feed.down.value)
        h.feed.take(t + 1000)
        assertEquals(listOf("a"), h.feed.take(t + 2000).comments.map { it.text })
        h.answer = { _, _ -> FeedHarness.ok("""{"comments":[${comment(t + 1500, "a")}],"complete":true}""") }
        h.advance(60_000)
        // 同じ区間をもう一度訊く (同じコメントは 2 度流さない)
        assertEquals(listOf(t to t + 60_000, t to t + 60_000), h.asked)
        assertFalse(h.feed.down.value)
        assertEquals(emptyList<JikkyoComment>(), h.feed.take(t + 3000).comments)
    }

    @Test
    fun 受け口が無い古い_denpa_では_tooOld_にして_もう訊かない() {
        val h = FeedHarness(now = t + day)
        h.answer = { _, _ -> FeedHarness.ok("Not Found", code = 404) }
        h.feed.set("jk1", true)
        h.feed.kakolog(t, { t + 60_000 })
        h.scope.runCurrent()
        assertTrue(h.feed.tooOld.value)
        assertFalse(h.feed.down.value)
        h.advance(10 * 60_000)
        h.feed.kakolog(t, { t + 60_000 })
        h.scope.runCurrent()
        assertEquals(1, h.asked.size)
    }

    @Test
    fun 断られたら_refused() {
        val h = FeedHarness(now = t + day)
        h.answer = { _, _ -> throw Unauthorized(URI("http://127.0.0.1/api/jikkyo/kakolog")) }
        h.feed.set("jk1", true)
        h.feed.kakolog(t, { t + 60_000 })
        h.scope.runCurrent()
        assertTrue(h.feed.refused.value)
    }

    @Test
    fun 流すときに_NG_ワードを外し_勢いは_NG_ワードのものも数える() {
        val h = FeedHarness(now = t + day)
        h.answer = { _, _ ->
            FeedHarness.ok("""{"comments":[${comment(t + 1500, "ネタバレ注意")},${comment(t + 2500, "わこつ")}],"complete":true}""")
        }
        h.feed.set("jk1", true)
        h.feed.setNg(listOf("ねたばれ"))
        h.feed.kakolog(t, { t + 60_000 })
        h.scope.runCurrent()
        h.feed.take(t + 1000)
        assertEquals(listOf("わこつ"), h.feed.take(t + 3000).comments.map { it.text })
        assertEquals(listOf("わこつ"), h.feed.recent(t + 3000, 60_000, 10).map { it.text })
        assertEquals(2, h.feed.force(t + 3000, 60_000))
        // オフにしたら何も出さない
        h.feed.set("jk1", false)
        assertEquals(0, h.feed.force(t + 3000, 60_000))
    }
}
