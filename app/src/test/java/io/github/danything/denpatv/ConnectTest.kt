package io.github.danything.denpatv

import io.github.danything.denpatv.data.BaseUrl
import io.github.danything.denpatv.data.ConnectStep
import io.github.danything.denpatv.data.DenpaApi
import io.github.danything.denpatv.data.Http
import io.github.danything.denpatv.data.Unauthorized
import io.github.danything.denpatv.data.connect
import io.github.danything.denpatv.ui.authorizationHeaders
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/** URL を入れてもらったあと: 家の LAN ならそのまま、家の外なら登録へ */
class ConnectTest {
    private val denpa = FakeDenpa()

    @After fun stop() = denpa.close()

    private val code = """{"deviceCode":"dc","userCode":"ABCD-EFGH","verificationUri":"device",
        "verificationUriComplete":"device?code=ABCD-EFGH","expiresIn":600,"interval":5}"""

    @Test
    fun 家の_LAN_からトークン無しで入れればそのまま使う() = runTest {
        denpa.enqueue("ok")
        denpa.enqueue("[]")
        val step = connect(DenpaApi(), denpa.url("/denpa"), "tv")
        assertEquals(ConnectStep.Open(URI(denpa.url("/denpa/"))), step)
    }

    /** ポートを書かずに打ったら、80 に繋がらなければ denpa の既定のポートも試し、繋がったほうを使う */
    @Test
    fun ポートが無く_80_に繋がらなければ既定のポートも試す() = runTest {
        denpa.enqueue("ok")
        denpa.enqueue("[]")
        val port = URI(denpa.url()).port
        val step = connect(DenpaApi(), "http://http://127.0.0.1/denpa", "tv", fallbackPort = port)
        assertEquals(ConnectStep.Open(URI("http://127.0.0.1:$port/denpa/")), step)
    }

    @Test
    fun どこにも繋がらなければそう言う() = runTest {
        val step = connect(DenpaApi(), "127.0.0.1", "tv", fallbackPort = 9) as ConnectStep.Failed
        assertEquals("http://127.0.0.1/ に接続できません。URL を確認してください", step.message)
    }

    @Test
    fun 断られたら登録をはじめ_登録の画面の_URL_は接頭辞の下に解く() = runTest {
        for (refused in listOf(401, 403)) {
            denpa.enqueue("ok")
            denpa.enqueue("""{"error":"unauthorized"}""", code = refused)
            denpa.enqueue(code)
            val step = connect(DenpaApi(), denpa.url("/denpa"), "denpa TV (emu)") as ConnectStep.NeedsLogin
            assertEquals(denpa.url("/denpa/device?code=ABCD-EFGH"), step.verificationUrl)
            assertEquals("ABCD-EFGH", step.code.userCode)
            denpa.requests.take(); denpa.requests.take()
            val request = denpa.requests.take()
            assertEquals("/denpa/api/device/code", request.target)
            assertEquals("""{"name":"denpa TV (emu)"}""", request.body)
        }
    }

    @Test
    fun 登録待ちが多すぎるときと_denpa_でないとき() = runTest {
        denpa.enqueue("ok")
        denpa.enqueue("", code = 401)
        denpa.enqueue("""{"error":"too_many_pending"}""", code = 429)
        assertTrue((connect(DenpaApi(), denpa.url(), "tv") as ConnectStep.Failed).message.contains("多すぎ"))
        denpa.enqueue("", code = 404)
        assertTrue(connect(DenpaApi(), denpa.url(), "tv") is ConnectStep.Failed)
    }

    @Test
    fun トークンがあれば付け_効かなければ_Unauthorized() = runTest {
        val base = BaseUrl.normalize(denpa.url())!!
        val api = DenpaApi { "denpa_abc" }
        denpa.enqueue("[]")
        api.services(base)
        assertEquals("Bearer denpa_abc", denpa.requests.take().authorization)

        denpa.enqueue("""{"error":"invalid_token"}""", code = 401)
        assertTrue(runCatching { api.services(base) }.exceptionOrNull() is Unauthorized)

        denpa.enqueue("", code = 204)
        api.logout(base)
        denpa.requests.take()
        val logout = denpa.requests.take()
        assertEquals("/api/device/logout", logout.target)
        assertEquals("application/json", logout.contentType)
        assertEquals("Bearer denpa_abc", logout.authorization)

        // トークンの無い繋ぎ方では何も付けない
        denpa.enqueue("[]")
        DenpaApi().services(base)
        assertNull(denpa.requests.take().authorization)
    }

    @Test
    fun 映像と絵にも同じヘッダを付ける() {
        assertEquals(mapOf("Authorization" to "Bearer denpa_abc"), authorizationHeaders("denpa_abc"))
        assertEquals(emptyMap<String, String>(), authorizationHeaders(null))
        assertNull(Http.bearer(""))
    }
}
