package io.github.danything.denpatv

import io.github.danything.denpatv.data.Abort
import io.github.danything.denpatv.data.Http
import io.github.danything.denpatv.data.JikkyoComment
import io.github.danything.denpatv.data.JikkyoFeed
import io.github.danything.denpatv.data.JikkyoLive
import io.github.danything.denpatv.data.JikkyoServer
import io.github.danything.denpatv.data.Unauthorized
import io.github.danything.denpatv.data.lenientJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URI

/**
 * ライブの実況のコメントを denpa のサーバの受け口 (`api/jikkyo/feed`) から受ける (denpa の `live.test.ts`・`official.test.ts` と同じ例)。
 * 外には繋がず、偽の受け口と、時計を進められるコルーチンで見る。返事は自分で作った偽物
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JikkyoLiveTest {
    private val t = 1_793_188_800_000L

    /** `feed` の返事 */
    private fun reply(
        seq: Long = 1,
        comments: String = "",
        hub: String = "up",
        epoch: String = "e1",
        official: String? = null,
    ): String = """{"epoch":"$epoch","seq":$seq,"hub":"$hub","nx":"up","comments":[$comments]""" +
        (official?.let { ""","official":$it""" } ?: "") + "}"

    /** 返事の `official` */
    private fun official(seq: Long = 1, since: Long? = null, now: Long = t, comments: String = "", epoch: String = "o1") =
        """{"epoch":"$epoch","state":"ready","since":$since,"now":$now,"seq":$seq,"comments":[$comments]}"""

    private fun ok(body: String, code: Int = 200) = Http.Response(code, body.toByteArray())

    private class FakeServer : JikkyoServer {
        val bodies = mutableListOf<JsonObject>()
        val replies = ArrayDeque<() -> Http.Response>()
        /** 返事が尽きたら返すもの (コメントの無い続き) */
        var fallback: () -> Http.Response = { Http.Response(200, """{"epoch":"e1","seq":1,"hub":"up","nx":"up","comments":[]}""".toByteArray()) }
        var aborted = false

        override fun feed(json: String, abort: Abort): Http.Response {
            bodies += lenientJson.parseToJsonElement(json).jsonObject
            abort.on { aborted = true }
            return (replies.removeFirstOrNull() ?: fallback)()
        }

        override fun kakolog(channel: String, from: Long, to: Long): Http.Response = error("ライブは過去ログを呼ばない")
    }

    private inner class Harness(official: Boolean = false) {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val server = FakeServer()
        val got = mutableListOf<JikkyoComment>()
        val hubs = mutableListOf<Boolean>()
        val boundaries = mutableListOf<Long?>()
        val odd = mutableListOf<String>()
        var tooOld = false
        var unauthorized = false
        val live = JikkyoLive(
            "jk1",
            server,
            object : JikkyoLive.Events {
                override fun comments(comments: List<JikkyoComment>) {
                    got += comments
                }

                override fun hub(ok: Boolean) {
                    hubs += ok
                }

                override fun boundary(at: Long?) {
                    boundaries += at
                }

                override fun odd(what: String) {
                    odd += what
                }

                override fun tooOld() {
                    tooOld = true
                }

                override fun unauthorized() {
                    unauthorized = true
                }
            },
            scope,
            dispatcher,
            { scope.testScheduler.currentTime },
            official,
        )

        fun start() {
            live.start()
            scope.runCurrent()
        }

        fun advance(ms: Long) {
            scope.advanceTimeBy(ms)
            scope.runCurrent()
        }

        fun calls() = server.bodies.size
    }

    @Test
    fun 最初は続きの番号を付けず_受けた_seq_と_epoch_で続きを訊く() {
        val h = Harness()
        h.server.replies += { ok(reply(seq = 7, epoch = "e9", comments = """{"at":$t,"text":"わこつ"}""")) }
        h.start()
        assertEquals("""{"channel":"jk1"}""", h.server.bodies[0].toString())
        assertEquals("""{"channel":"jk1","after":7,"epoch":"e9"}""", h.server.bodies[1].toString())
        assertEquals(listOf(JikkyoComment(t, "わこつ")), h.got)
        // ライブだけ (公式を受けない) なら official は載せない
        assertNull(h.server.bodies[1]["official"])
    }

    @Test
    fun コメントのある返事のあとはすぐ次を呼び_無い返事がすぐ返ったら_1_秒置く() {
        val h = Harness()
        h.server.replies += { ok(reply(comments = """{"at":$t,"text":"a"}""")) }
        h.start()
        // コメントのある返事のあとはすぐ。2 回目はコメントが無いので 1 秒待つ
        assertEquals(2, h.calls())
        h.advance(JikkyoLive.GAP_MS - 1)
        assertEquals(2, h.calls())
        h.advance(1)
        assertEquals(3, h.calls())
    }

    @Test
    fun 届かなければ_10_秒置き_3_回続けて届かなければ_hub_に繋がっていない_にする_戻れば戻す() {
        val h = Harness()
        repeat(3) { h.server.replies += { throw IOException("届かない") } }
        h.start()
        assertEquals(1, h.calls())
        h.advance(JikkyoLive.RETRY_MS - 1)
        assertEquals(1, h.calls())
        h.advance(1)
        assertEquals(2, h.calls())
        assertEquals(emptyList<Boolean>(), h.hubs)
        h.advance(JikkyoLive.RETRY_MS)
        assertEquals(listOf(false), h.hubs)
        h.advance(JikkyoLive.RETRY_MS)
        assertEquals(listOf(false, true), h.hubs)
    }

    @Test
    fun 断られた返事も届かないのと同じ_返事の_hub_が_down_なら_hub_に繋がっていない() {
        val h = Harness()
        h.server.replies += { ok("", code = 503) }
        h.server.replies += { ok(reply(hub = "down")) }
        h.start()
        assertEquals(1, h.calls())
        h.advance(JikkyoLive.RETRY_MS)
        assertEquals(listOf(false), h.hubs)
    }

    @Test
    fun 受け口が無い古い_denpa_ならやめる_401_もやめる() {
        val old = Harness()
        old.server.replies += { ok("Not Found", code = 404) }
        old.start()
        old.advance(60_000)
        assertTrue(old.tooOld)
        assertEquals(1, old.calls())

        val refused = Harness()
        refused.server.replies += { throw Unauthorized(URI("http://127.0.0.1/api/jikkyo/feed")) }
        refused.start()
        refused.advance(60_000)
        assertTrue(refused.unauthorized)
        assertEquals(1, refused.calls())
    }

    @Test
    fun 形式のずれたものは飛ばして言い_読めたぶんだけ流す() {
        val h = Harness()
        h.server.replies += { ok(reply(comments = """{"at":$t,"text":"a"},{"text":"時刻が無い"},{"at":$t,"text":"/nicoad {}"}""")) }
        h.server.replies += { ok("<html>") }
        h.start()
        assertEquals(listOf("a"), h.got.map { it.text })
        assertEquals(listOf("実況のコメントの形式が一部違います", "実況の返事の形式が違います"), h.odd)
    }

    @Test
    fun 止めたら待っている返事を切り_そのあとは呼ばない() {
        val h = Harness()
        h.start()
        h.live.stop()
        assertTrue(h.server.aborted)
        val calls = h.calls()
        h.advance(60_000)
        assertEquals(calls, h.calls())
    }

    @Test
    fun 公式も受けるなら_official_を載せ_続きの番号を返事の_official_から取る() {
        val h = Harness(official = true)
        h.server.replies += { ok(reply(official = official(seq = 12, epoch = "o7"))) }
        h.start()
        h.advance(JikkyoLive.GAP_MS)
        assertEquals("{}", h.server.bodies[0]["official"].toString())
        assertEquals("""{"after":12,"epoch":"o7"}""", h.server.bodies[1]["official"].toString())
    }

    @Test
    fun 公式から受けている間は_境より後の公式を流し_境より後の_NX_Jikkyo_の取り込みは捨てる() {
        val h = Harness(official = true)
        // 続きの返事も公式を受け続けている
        h.server.fallback = { ok(reply(official = official(since = t - 60_000, now = t))) }
        // 境は max(受け続けている始まり, 最初の返事の時刻) = t
        h.server.replies += {
            ok(
                reply(
                    comments = """{"at":${t - 1000},"text":"前の取り込み","src":"nicolive"},{"at":${t + 1000},"text":"後の取り込み","src":"nicolive"},""" +
                        """{"at":${t + 1000},"text":"NX","src":"nx"},{"at":${t + 1000},"text":"hub","src":"hub","id":"h1"}""",
                    official = official(
                        since = t - 60_000,
                        now = t,
                        comments = """{"at":${t - 500},"text":"境より前の公式"},{"at":${t + 1000},"text":"公式","style":{"color":"#ffff00","place":"ue"}},""" +
                            """{"at":${t + 2000},"text":"世帯のアカウント","own":true}""",
                    ),
                ),
            )
        }
        h.start()
        assertEquals(listOf<Long?>(t), h.boundaries)
        assertEquals(listOf("公式", "世帯のアカウント", "前の取り込み", "NX", "hub"), h.got.map { it.text })
        assertEquals("#ffff00", h.got[0].style?.color)
        assertEquals("ue", h.got[0].style?.place)
    }

    @Test
    fun 公式を受けられていない世帯は境が無く_NX_Jikkyo_の取り込みを全部流す() {
        val h = Harness(official = true)
        // 連携していない世帯はサーバが unlinked・since null を返す
        h.server.replies += {
            ok(
                reply(
                    comments = """{"at":${t + 1000},"text":"取り込み","src":"nicolive"}""",
                    official = """{"epoch":"o1","state":"unlinked","since":null,"now":$t,"seq":0,"comments":[{"at":${t + 1000},"text":"流さない"}]}""",
                ),
            )
        }
        h.start()
        assertEquals(emptyList<Long?>(), h.boundaries)
        assertEquals(listOf("取り込み"), h.got.map { it.text })
    }

    @Test
    fun 届かなければ境を無くし_サーバを起こし直したら最初の返事の時刻から数え直す() {
        val h = Harness(official = true)
        h.server.replies += { ok(reply(official = official(since = t - 60_000, now = t))) }
        h.server.replies += { throw IOException("届かない") }
        h.server.replies += { ok(reply(official = official(since = t - 60_000, now = t + 30_000, epoch = "o2"))) }
        h.start()
        // コメントの無い返事がすぐ返ったので 1 秒置いてから次を呼ぶ
        h.advance(JikkyoLive.GAP_MS)
        assertEquals(listOf<Long?>(t, null), h.boundaries)
        h.advance(JikkyoLive.RETRY_MS)
        assertEquals(listOf<Long?>(t, null, t + 30_000), h.boundaries)
    }

    @Test
    fun 公式の返事の形式が違えば境を無くして言う() {
        val h = Harness(official = true)
        h.server.replies += { ok(reply(official = official(since = t - 60_000, now = t))) }
        h.server.replies += { ok(reply(official = """{"state":"ready"}""")) }
        h.start()
        h.advance(JikkyoLive.GAP_MS)
        assertEquals(listOf<Long?>(t, null), h.boundaries)
        assertEquals(listOf("公式の実況の返事の形式が違います"), h.odd)
    }

    /** `JikkyoFeed` の「いまは読めない」。hub に繋がらなくても、公式から受けている間は読めている */
    @Test
    fun hub_に繋がらなくても公式から受けている間は_down_にしない() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val server = FakeServer()
        val feed = JikkyoFeed(receives = true, scope = scope, server = server, now = { t }, warn = {}, io = dispatcher)
        server.fallback = { ok(reply(hub = "down", official = official(since = t - 60_000, now = t))) }
        feed.set("jk1", true, official = true)
        scope.runCurrent()
        assertFalse(feed.down.value)
        server.fallback = { ok(reply(hub = "down", official = """{"epoch":"o1","state":"down","since":null,"now":$t,"seq":1,"comments":[]}""")) }
        scope.advanceTimeBy(JikkyoLive.GAP_MS)
        scope.runCurrent()
        assertTrue(feed.down.value)
        feed.close()
        scope.runCurrent()
    }
}
