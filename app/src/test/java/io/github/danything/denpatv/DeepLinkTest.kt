package io.github.danything.denpatv

import io.github.danything.denpatv.data.DeepLink
import io.github.danything.denpatv.data.NowProgram
import io.github.danything.denpatv.data.Service
import io.github.danything.denpatv.data.findService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkTest {
    @Test
    fun リンクを読む() {
        assertEquals(DeepLink.Live(null), DeepLink.parse("denpa://live"))
        assertEquals(DeepLink.Live(null), DeepLink.parse("denpa://live/"))
        assertEquals(DeepLink.Live("3227310008"), DeepLink.parse("denpa://live/3227310008"))
        assertEquals(DeepLink.Live("NHK総合"), DeepLink.parse("denpa://live/NHK総合"))
        assertEquals(DeepLink.Recordings, DeepLink.parse("denpa://recordings"))
        assertEquals(DeepLink.Watch("12"), DeepLink.parse("denpa://recording/12"))
        // 録画の id が無ければ一覧
        assertEquals(DeepLink.Recordings, DeepLink.parse("denpa://recording"))
    }

    @Test
    fun 符号化と大文字と余り() {
        assertEquals(DeepLink.Live("NHK総合"), DeepLink.parse("denpa://live/NHK%E7%B7%8F%E5%90%88"))
        assertEquals(DeepLink.Live("TOKYO MX"), DeepLink.parse("denpa://live/TOKYO%20MX"))
        // + は空白にしない
        assertEquals(DeepLink.Live("A+B"), DeepLink.parse("denpa://live/A+B"))
        // 崩れた % はそのまま
        assertEquals(DeepLink.Live("100%"), DeepLink.parse("denpa://live/100%"))
        assertEquals(DeepLink.Live("9"), DeepLink.parse("DENPA://LIVE/9?from=ha#x"))
        assertEquals(DeepLink.Recordings, DeepLink.parse(" denpa://recordings/ "))
    }

    @Test
    fun ほかの形は読まない() {
        assertNull(DeepLink.parse(null))
        assertNull(DeepLink.parse(""))
        assertNull(DeepLink.parse("https://example.com/live"))
        assertNull(DeepLink.parse("denpa:live"))
        assertNull(DeepLink.parse("denpa://guide"))
    }

    private val nhk = service(3273601024, "GR", "ＮＨＫ総合１・東京", 1, airing = true)
    private val nhk2 = service(3273601025, "GR", "ＮＨＫ総合２・東京", 1, airing = false)
    private val mx = service(3239123608, "GR", "ＴＯＫＹＯ　ＭＸ１", 9, airing = true)
    private val bsAsahi = service(400151, "BS", "BS朝日1", null, airing = true)
    private val services = listOf(nhk2, nhk, mx, bsAsahi)

    @Test
    fun 局を探す() {
        assertEquals(mx, findService(services, "3239123608"))
        assertEquals(bsAsahi, findService(services, "BS朝日1"))
        // 全角・半角と大文字・小文字・空白を揃える
        assertEquals(mx, findService(services, "tokyo mx1"))
        // 番号は放送している局から (NHK総合2 は同じものを流しているので後)
        assertEquals(nhk, findService(services, "1"))
        assertEquals(mx, findService(services, "９"))
        assertEquals(bsAsahi, findService(services, "151"))
        // 名前の頭でも
        assertEquals(nhk, findService(services, "NHK総合"))
        assertEquals(mx, findService(services, "TOKYO MX"))
    }

    @Test
    fun 見つからない局() {
        assertNull(findService(services, "テレビ大阪"))
        assertNull(findService(services, "999"))
        assertNull(findService(services, " "))
        assertNull(findService(emptyList(), "NHK"))
    }

    private fun service(id: Long, type: String, name: String, key: Int?, airing: Boolean) =
        Service(
            id = id, type = type, name = name, remoteControlKey = key, live = "api/services/$id/live",
            now = NowProgram(if (airing) "番組" else "", 0, 1),
        )
}
