package io.github.danything.denpatv

import io.github.danything.denpatv.data.BaseUrl
import io.github.danything.denpatv.data.DenpaApi
import io.github.danything.denpatv.data.RecordResult
import io.github.danything.denpatv.data.unwatched
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/api.md の例そのままの JSON を読む。知らない鍵は無視する (denpa は足すことがある) */
class DenpaApiTest {
    private val denpa = FakeDenpa()
    private val api = DenpaApi()

    @After fun stop() = denpa.close()

    @Test
    fun 局と録画の一覧を読む() = runTest {
        denpa.enqueue("""[{"id":3227310008,"type":"GR","name":"TOKYO MX","remoteControlKey":9,"logo":null,"live":"api/services/3227310008/live","new":1}]""")
        denpa.enqueue(
            """[{"id":12,"title":"番組 第1話","name":"[新]番組 第1話[字]","serviceId":3227310008,"serviceName":"TOKYO MX",
               "startAt":1790000000000,"endAt":1790001800000,"durationMs":1800000,"poster":"api/recordings/12/poster",
               "files":[{"source":"encoded","codec":"av1","url":"api/recordings/12/file?source=encoded"}],
               "audio":"api/recordings/12/file?audio=only"}]""",
        )
        denpa.enqueue(
            """[{"id":1,"type":"BS","name":"BS11","live":"api/services/1/live","now":{"title":"アニメ","startAt":1000,"endAt":181000}},
               {"id":2,"type":"BS","name":"BS12","live":"api/services/2/live","now":null}]""",
        )
        denpa.enqueue("""[{"id":13,"title":"続き","name":"続き","startAt":1,"endAt":2,"resumeMs":754000,"files":[]}]""")
        val base = BaseUrl.normalize(denpa.url("/denpa"))!!

        val services = api.services(base)
        assertEquals("TOKYO MX", services.single().name)
        assertNull(services.single().logo)
        assertEquals("/denpa/api/services", denpa.requests.take().target)

        val recordings = api.recordings(base)
        assertEquals("av1", recordings.single().files.single().codec)
        assertEquals("/denpa/api/recordings", denpa.requests.take().target)
        // now (番組表に無い) も resumeMs (観ていない) も無ければ null
        assertNull(services.single().now)
        assertNull(recordings.single().resumeMs)

        val withNow = api.services(base)
        assertEquals("アニメ", withNow[0].now?.title)
        assertEquals(3L, withNow[0].now?.remainingMinutes(1000))
        assertEquals(0.5f, withNow[0].now!!.progress(91_000))
        assertNull(withNow[1].now)
        assertEquals(754_000L, api.recordings(base).single().resumeMs)
    }

    /**
     * いまの番組を録る (`POST api/services/<id>/record`)。本文は無くても Content-Type を付ける。
     * 番組表に無い (404 と理由)・予約できない (400 と理由) は理由をそのまま出す
     */
    @Test
    fun いまの番組を録る() = runTest {
        val base = BaseUrl.normalize(denpa.url())!!
        denpa.enqueue("""{"recorded":"ニュース","programId":32736103210001,"reserved":true}""")
        assertEquals(RecordResult.Recorded("ニュース", reserved = true), api.recordNow(base, 3273601024))
        val request = denpa.requests.take()
        assertEquals("POST", request.method)
        assertEquals("/api/services/3273601024/record", request.target)
        assertEquals("application/json", request.contentType)

        denpa.enqueue("""{"recorded":"ニュース","programId":1,"reserved":false}""")
        assertEquals(RecordResult.Recorded("ニュース", reserved = false), api.recordNow(base, 1))
        denpa.enqueue("""{"message":"いま流れている番組が番組表に見つかりません"}""", code = 404)
        assertEquals(RecordResult.Failed("いま流れている番組が番組表に見つかりません"), api.recordNow(base, 1))
        denpa.enqueue("""{"message":"この番組は放送が終わっています"}""", code = 400)
        assertEquals(RecordResult.Failed("この番組は放送が終わっています"), api.recordNow(base, 1))
        // 通ったのに答えが読めない (版のずれ)。予約できたとは言わない
        denpa.enqueue("<html></html>")
        assertEquals(RecordResult.Failed("denpa の答えを読めません (200)"), api.recordNow(base, 1))
    }

    /** 局の now に録画の印。無ければ false */
    @Test
    fun 局の録画の印を読む() = runTest {
        denpa.enqueue(
            """[{"id":1,"type":"GR","name":"A","live":"api/services/1/live","now":{"id":9,"title":"x","startAt":1,"endAt":2,"reserved":true,"recording":true}},
               {"id":2,"type":"GR","name":"B","live":"api/services/2/live","now":{"title":"y","startAt":1,"endAt":2}}]""",
        )
        val services = api.services(BaseUrl.normalize(denpa.url())!!)
        assertTrue(services[0].now!!.reserved)
        assertTrue(services[0].now!!.recording)
        assertFalse(services[1].now!!.reserved)
        assertFalse(services[1].now!!.recording)
    }

    /**
     * 未視聴は `watchedAt` も `resumeMs` も null のもの。`watchedAt` を送らない古い denpa (v1.45.0 より前) では分からないので、
     * 印を付けない。形が違っても一覧は読む
     */
    @Test
    fun 未視聴を見分ける() = runTest {
        denpa.enqueue(
            """[{"id":1,"title":"a","startAt":1,"watchedAt":null,"resumeMs":null},
               {"id":2,"title":"b","startAt":1,"watchedAt":null,"resumeMs":754000},
               {"id":3,"title":"c","startAt":1,"watchedAt":1790000000000,"resumeMs":null},
               {"id":4,"title":"d","startAt":1,"resumeMs":null},
               {"id":5,"title":"e","startAt":1,"watchedAt":"2026-10-07"}]""",
        )
        val recordings = api.recordings(BaseUrl.normalize(denpa.url())!!)
        assertEquals(listOf(true, false, false, false, false), recordings.map { it.unwatched })
    }

    /** 観た位置は秒で預ける (denpa の POST api/recordings/<id>/resume は {at, length} を秒で受ける) */
    @Test
    fun 観た位置を秒で預ける() = runTest {
        denpa.enqueue("{}")
        api.saveResume(BaseUrl.normalize(denpa.url())!!, 12, 754.5, 1800.0)
        val request = denpa.requests.take()
        assertEquals("POST", request.method)
        assertEquals("/api/recordings/12/resume", request.target)
        assertEquals("""{"at":754.5,"length":1800.0}""", request.body)
    }

    /**
     * 消すのは DELETE api/recordings/<id>。denpa は 204 を返す。もう無い (404) のも消せたとみなす。録画中 (409) は false。
     * 本文が無くても Content-Type を付ける (無いと SvelteKit がよそのサイトからのフォーム送信と見なして 403)
     */
    @Test
    fun 録画を消す() = runTest {
        val base = BaseUrl.normalize(denpa.url())!!
        denpa.enqueue("", code = 204)
        assertTrue(api.deleteRecording(base, 12))
        val request = denpa.requests.take()
        assertEquals("DELETE", request.method)
        assertEquals("/api/recordings/12", request.target)
        assertEquals("application/json", request.contentType)

        denpa.enqueue("""{"message":"録画中は消せません"}""", code = 409)
        assertFalse(api.deleteRecording(base, 13))

        denpa.enqueue("""{"message":"Not Found"}""", code = 404)
        assertTrue(api.deleteRecording(base, 14))
    }

    @Test
    fun 繋がるかを確かめる() = runTest {
        denpa.enqueue(HEALTH_OK)
        assertNotNull(api.health(BaseUrl.normalize(denpa.url())!!))
        assertEquals("/api/health", denpa.requests.take().target)
        assertNull(api.health(BaseUrl.normalize("http://127.0.0.1:1")!!))
    }

    /** 番組の中身は別の口。無い (404) なら null で、画面は出さないだけ */
    @Test
    fun 番組の中身を読み_無ければ_null() = runTest {
        val base = BaseUrl.normalize(denpa.url())!!
        denpa.enqueue("""{"id":12,"title":"t","name":"n","description":"概要","extended":{"出演者":"だれか"}}""")
        val detail = api.recordingDetail(base, 12)!!
        assertEquals("概要", detail.description)
        assertEquals(mapOf("出演者" to "だれか"), detail.extended)
        assertEquals("/api/recordings/12/detail", denpa.requests.take().target)
        denpa.enqueue("", code = 404)
        assertEquals(null, api.recordingDetail(base, 13))
    }
}
