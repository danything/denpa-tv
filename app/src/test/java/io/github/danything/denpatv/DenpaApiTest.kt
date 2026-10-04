package io.github.danything.denpatv

import io.github.danything.denpatv.data.BaseUrl
import io.github.danything.denpatv.data.DenpaApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // 古い denpa は now も resumeMs も返さない。無ければ null
        assertNull(services.single().now)
        assertNull(recordings.single().resumeMs)

        val withNow = api.services(base)
        assertEquals("アニメ", withNow[0].now?.title)
        assertEquals(3L, withNow[0].now?.remainingMinutes(1000))
        assertEquals(0.5f, withNow[0].now!!.progress(91_000))
        assertNull(withNow[1].now)
        assertEquals(754_000L, api.recordings(base).single().resumeMs)
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

    /** 消すのは DELETE api/recordings/<id>。denpa は 204 を返す。断られたら false */
    @Test
    fun 録画を消す() = runTest {
        val base = BaseUrl.normalize(denpa.url())!!
        denpa.enqueue("", code = 204)
        assertTrue(api.deleteRecording(base, 12))
        val request = denpa.requests.take()
        assertEquals("DELETE", request.method)
        assertEquals("/api/recordings/12", request.target)

        denpa.enqueue("""{"message":"録画中は消せません"}""", code = 409)
        assertFalse(api.deleteRecording(base, 13))
    }

    @Test
    fun 繋がるかを確かめる() = runTest {
        denpa.enqueue("ok")
        assertTrue(api.health(BaseUrl.normalize(denpa.url())!!))
        assertEquals("/api/health", denpa.requests.take().target)
        assertFalse(api.health(BaseUrl.normalize("http://127.0.0.1:1")!!))
    }

    /** 番組の中身は別の口。古い denpa (口が無い = 404) では null で、画面は出さないだけ */
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
