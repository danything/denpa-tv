package io.github.danything.denpatv

import io.github.danything.denpatv.data.JikkyoComment
import io.github.danything.denpatv.data.JikkyoEndpoints
import io.github.danything.denpatv.data.JikkyoFeed
import io.github.danything.denpatv.data.Kakolog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.URI

/**
 * 過去ログ API から取る (denpa の `kakolog.test.ts` と同じ例)。偽の過去ログ API (`FakeDenpa`) に繋ぐ。返事は自分で作った偽物。
 * **denpa のアクセストークンも Referer も付けない**
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KakologTest {
    private val t = 1_793_188_800_000L
    private val day = 24 * 60 * 60_000L

    private fun chat(ms: Long, content: String) =
        """{"chat":{"thread":"1","no":"1","date":"${ms / 1000}","date_usec":"${ms % 1000 * 1000}","user_id":"1234","content":"$content"}}"""

    private fun endpoints(fake: FakeDenpa) = JikkyoEndpoints("ws://127.0.0.1:1", fake.url("").trimEnd('/'))

    private fun FakeDenpa.query(): Map<String, String> =
        URI(requests.take().target).rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun 区間を秒で訊き_アクセストークンも_Referer_も付けない() = FakeDenpa().use { fake ->
        fake.enqueue("""{"packet":[${chat(t + 1500, "a")},${chat(t + 2500, "b")}]}""")
        val got = Kakolog.fetch(endpoints(fake), "jk1", t, t + 60_000, now = t + day)
        assertEquals(listOf(JikkyoComment(t + 1500, "a"), JikkyoComment(t + 2500, "b")), got.comments)
        assertEquals(0, got.odd)
        val request = fake.requests.peek()!!
        assertEquals("/api/kakolog/jk1", URI(request.target).path)
        assertNull(request.authorization)
        assertNull(request.referer)
        val query = fake.query()
        assertEquals((t / 1000).toString(), query["starttime"])
        assertEquals((t / 1000 + 60).toString(), query["endtime"])
        assertEquals("json", query["format"])
    }

    @Test
    fun 終わりは今で切る() = FakeDenpa().use { fake ->
        fake.enqueue("""{"packet":[]}""")
        Kakolog.fetch(endpoints(fake), "jk1", t, t + 60 * 60_000, now = t + 600_500)
        assertEquals((t / 1000 + 600).toString(), fake.query()["endtime"])
    }

    @Test
    fun 三日を超える区間は分けて訊く() = FakeDenpa().use { fake ->
        fake.enqueue("""{"packet":[]}""")
        fake.enqueue("""{"packet":[]}""")
        Kakolog.fetch(endpoints(fake), "jk1", t, t + 4 * day, now = t + 10 * day)
        assertEquals((t / 1000).toString(), fake.query()["starttime"])
        assertEquals(((t + 3 * day) / 1000).toString(), fake.query()["starttime"])
    }

    @Test
    fun 空の_packet_はコメントなし_error_は_HTTP_200_でも誤り() = FakeDenpa().use { fake ->
        fake.enqueue("""{"packet":[]}""")
        assertEquals(emptyList<JikkyoComment>(), Kakolog.fetch(endpoints(fake), "jk1", t, t + 1000, now = t + day).comments)
        fake.enqueue("""{"error":"取得開始時刻または取得終了時刻が不正です。"}""")
        try {
            Kakolog.fetch(endpoints(fake), "jk1", t, t + 1000, now = t + day)
            fail("誤りを読み流しました")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("不正です"))
        }
    }

    @Test
    fun 形式のずれた行は飛ばして数える() = FakeDenpa().use { fake ->
        fake.enqueue("""{"packet":[${chat(t, "a")},{"chat":{"content":"時刻が無い"}}]}""")
        val got = Kakolog.fetch(endpoints(fake), "jk1", t, t + 1000, now = t + day)
        assertEquals(listOf("a"), got.comments.map { it.text })
        assertEquals(1, got.odd)
    }

    /** 過去ログを取り足す決まり (`JikkyoFeed.kakolog`)。過去ログ API は偽物、時計は進められるもの */
    private class FeedHarness(var now: Long) {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val asked = mutableListOf<Pair<Long, Long>>()
        var answer: (Long, Long) -> Kakolog.Result = { _, _ -> Kakolog.Result(emptyList(), 0) }
        val feed = JikkyoFeed(
            live = false,
            scope = scope,
            endpoints = JikkyoEndpoints("ws://127.0.0.1:1", "http://127.0.0.1:1"),
            now = { now },
            fetch = { _, from, to -> asked += from to to; answer(from, to) },
            warn = {},
            io = dispatcher,
        )

        fun advance(ms: Long) {
            now += ms
            scope.advanceTimeBy(ms)
            scope.runCurrent()
        }
    }

    @Test
    fun 録画は開いたときに全区間を取り_10_分より新しい所だけ_5_分おきに取り足す() {
        val end = t + 60 * 60_000
        val h = FeedHarness(now = end + 60_000)
        h.answer = { from, _ -> Kakolog.Result(listOf(JikkyoComment(from + 1, "c$from")), 0) }
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
        h.answer = { _, _ -> throw IOException("HTTP 503") }
        h.feed.set("jk1", true)
        h.feed.kakolog(t, { t + 60_000 })
        h.scope.runCurrent()
        assertTrue(h.feed.down.value)
        h.answer = { _, _ -> Kakolog.Result(emptyList(), 0) }
        h.advance(59_999)
        assertEquals(1, h.asked.size)
        h.advance(1)
        assertEquals(2, h.asked.size)
        assertFalse(h.feed.down.value)
    }

    @Test
    fun 流すときに_NG_ワードを外し_勢いは_NG_ワードのものも数える() {
        val h = FeedHarness(now = t + day)
        h.answer = { _, _ ->
            Kakolog.Result(listOf(JikkyoComment(t + 1500, "ネタバレ注意"), JikkyoComment(t + 2500, "わこつ")), 0)
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
