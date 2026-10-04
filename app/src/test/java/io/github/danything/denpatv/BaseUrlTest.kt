package io.github.danything.denpatv

import io.github.danything.denpatv.data.BaseUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BaseUrlTest {
    @Test
    fun スキームが無ければ_http_を足し_末尾を_slash_にそろえる() {
        assertEquals("http://192.168.1.10:3000/", BaseUrl.normalize("192.168.1.10:3000").toString())
        assertEquals("https://tv.example.jp/denpa/", BaseUrl.normalize(" https://tv.example.jp/denpa ").toString())
    }

    @Test
    fun 読めないものは_null() {
        assertNull(BaseUrl.normalize(""))
        assertNull(BaseUrl.normalize("ftp://example.jp/"))
        assertNull(BaseUrl.normalize("http://"))
    }

    /** denpa は根からの相対 (`api/…`) で返す。前段の接頭辞の下でも、その下に足す */
    @Test
    fun 相対の_URL_は接頭辞の下に足す() {
        val base = BaseUrl.normalize("http://ha.local:8123/api/hassio_ingress/abc")!!
        assertEquals(
            "http://ha.local:8123/api/hassio_ingress/abc/api/services/1/logo",
            BaseUrl.resolve(base, "api/services/1/logo").toString(),
        )
    }
}
