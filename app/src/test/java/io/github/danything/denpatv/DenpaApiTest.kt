package io.github.danything.denpatv

import io.github.danything.denpatv.data.BaseUrl
import io.github.danything.denpatv.data.DenpaApi
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** docs/api.md の例そのままの JSON を読む。知らない鍵は無視する (denpa は足すことがある) */
class DenpaApiTest {
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    @Test
    fun 局と録画の一覧を読む() = runTest {
        server.enqueue(MockResponse.Builder().body(
            """[{"id":3227310008,"type":"GR","name":"TOKYO MX","remoteControlKey":9,"logo":null,"live":"api/services/3227310008/live","new":1}]""",
        ).build())
        server.enqueue(MockResponse.Builder().body(
            """[{"id":12,"title":"番組 第1話","name":"[新]番組 第1話[字]","serviceId":3227310008,"serviceName":"TOKYO MX",
               "startAt":1790000000000,"endAt":1790001800000,"durationMs":1800000,"poster":"api/recordings/12/poster",
               "files":[{"source":"encoded","codec":"av1","url":"api/recordings/12/file?source=encoded"}],
               "audio":"api/recordings/12/file?audio=only"}]""",
        ).build())
        server.enqueue(MockResponse.Builder().body(
            """[{"id":1,"type":"BS","name":"BS11","live":"api/services/1/live","now":{"title":"アニメ","startAt":1000,"endAt":181000}},
               {"id":2,"type":"BS","name":"BS12","live":"api/services/2/live","now":null}]""",
        ).build())
        server.enqueue(MockResponse.Builder().body(
            """[{"id":13,"title":"続き","name":"続き","startAt":1,"endAt":2,"resumeMs":754000,"files":[]}]""",
        ).build())
        val api = DenpaApi(OkHttpClient())
        val base = BaseUrl.normalize(server.url("/denpa").toString())!!

        val services = api.services(base)
        assertEquals("TOKYO MX", services.single().name)
        assertNull(services.single().logo)
        assertEquals("/denpa/api/services", server.takeRequest().target)

        val recordings = api.recordings(base)
        assertEquals("av1", recordings.single().files.single().codec)
        assertEquals("/denpa/api/recordings?limit=50", server.takeRequest().target)
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
        server.enqueue(MockResponse.Builder().body("{}").build())
        DenpaApi(OkHttpClient()).saveResume(BaseUrl.normalize(server.url("/").toString())!!, 12, 754.5, 1800.0)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/recordings/12/resume", request.target)
        assertEquals("""{"at":754.5,"length":1800.0}""", request.body?.utf8())
    }
}
