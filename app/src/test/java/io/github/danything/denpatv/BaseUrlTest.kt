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

    /** 欄にはじめから入っている `http://` のあとに URL をまるごと打ったとき */
    @Test
    fun 重なったスキームは内側を使う() {
        assertEquals("http://192.168.1.10:3000/", BaseUrl.normalize("http://http://192.168.1.10:3000").toString())
        assertEquals("https://tv.example.jp/denpa/", BaseUrl.normalize("http://https://tv.example.jp/denpa").toString())
        assertEquals("https://tv.example.jp/", BaseUrl.normalize("HTTP://http://https://tv.example.jp").toString())
        // パスの頭の // は1つに (別のホストと読まない)
        assertEquals("http://192.168.1.10/denpa/", BaseUrl.normalize("192.168.1.10//denpa").toString())
        // 貼った URL の頭が大文字
        assertEquals("https://tv.example.jp/", BaseUrl.normalize("http://Https://tv.example.jp").toString())
    }

    @Test
    fun ポートを書いていない_http_は_3000_も試す() {
        assertEquals(
            listOf("http://192.168.1.10/", "http://192.168.1.10:3000/"),
            BaseUrl.candidates(BaseUrl.normalize("192.168.1.10")!!).map { it.toString() },
        )
        assertEquals(
            listOf("http://nas.local/denpa/", "http://nas.local:3000/denpa/"),
            BaseUrl.candidates(BaseUrl.normalize("nas.local/denpa")!!).map { it.toString() },
        )
        // 接頭辞の % はそのまま
        assertEquals("http://nas.local:3000/tv%2541/", BaseUrl.candidates(BaseUrl.normalize("nas.local/tv%2541")!!)[1].toString())
        // ポートを書いた・https なら、そのまま
        assertEquals(1, BaseUrl.candidates(BaseUrl.normalize("192.168.1.10:8080")!!).size)
        assertEquals(1, BaseUrl.candidates(BaseUrl.normalize("https://tv.example.jp")!!).size)
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
