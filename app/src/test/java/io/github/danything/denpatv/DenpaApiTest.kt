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
        val api = DenpaApi(OkHttpClient())
        val base = BaseUrl.normalize(server.url("/denpa").toString())!!

        val services = api.services(base)
        assertEquals("TOKYO MX", services.single().name)
        assertNull(services.single().logo)
        assertEquals("/denpa/api/services", server.takeRequest().target)

        val recordings = api.recordings(base)
        assertEquals("av1", recordings.single().files.single().codec)
        assertEquals("/denpa/api/recordings?limit=50", server.takeRequest().target)
    }
}
