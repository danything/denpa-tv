package io.github.danything.denpatv

import io.github.danything.denpatv.data.CaptionPages
import io.github.danything.denpatv.data.captionBaseline
import io.github.danything.denpatv.data.captionColor
import io.github.danything.denpatv.data.decodeDrcs
import io.github.danything.denpatv.data.isCaptionSpace
import io.github.danything.denpatv.data.lenientJson
import io.github.danything.denpatv.data.parseCaptionPage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionTextTest {
    private fun page(json: String) = parseCaptionPage(lenientJson.parseToJsonElement(json))

    /** denpa の docs/api.md「字幕の文字の配置」の例 */
    private val example = """
        { "v": 1, "plane": [960, 540], "duration": 3000,
          "runs": [{ "x": 250, "y": 450, "w": 40, "h": 60, "fx": 2, "fy": 12, "size": 36, "scaleX": 1,
                     "text": "字幕です", "fg": "#ffffffff", "bg": "#00000080" },
                   { "x": 410, "y": 450, "w": 40, "h": 60, "fx": 2, "fy": 12, "size": 36, "scaleX": 1,
                     "text": "〓", "drcs": "65569", "fg": "#ffffffff", "bg": "#00000080", "stroke": "#000000ff" },
                   { "x": 250, "y": 390, "w": 20, "h": 30, "fx": 1, "fy": 6, "size": 18, "scaleX": 0.5,
                     "text": "じまく", "fg": "#ffff00ff", "bg": "#00000000", "underline": true, "box": 5, "flash": true,
                     "ruby": true, "bold": true, "future": "知らない鍵" }],
          "drcs": { "65569": { "w": 4, "h": 2, "depth": 2, "bits": 1, "data": "lg==" } } }
    """

    @Test
    fun 文字の配置を読む() {
        val page = page(example)!!
        assertEquals(960f, page.planeWidth)
        assertEquals(540f, page.planeHeight)
        assertEquals(3000L, page.durationMs)
        assertEquals(3, page.runs.size)
        val first = page.runs[0]
        assertEquals(listOf(250f, 450f, 40f, 60f, 2f, 12f, 36f, 1f), listOf(first.x, first.y, first.w, first.h, first.fx, first.fy, first.size, first.scaleX))
        assertEquals(0xffffffff.toInt(), first.fg)
        assertEquals(0x80000000.toInt(), first.bg)
        assertNull(first.stroke)
        assertFalse(first.underline || first.flash)
        assertEquals(0, first.box)
        assertEquals(4, first.count)
        val drcs = page.runs[1]
        assertEquals("65569", drcs.drcs)
        assertEquals(0xff000000.toInt(), drcs.stroke)
        val ruby = page.runs[2]
        assertEquals(0.5f, ruby.scaleX)
        assertTrue(ruby.underline && ruby.flash)
        assertEquals(5, ruby.box)
        assertTrue(page.flashes)
        // 外字の絵 (#..# / .##.)
        assertArrayEquals(
            byteArrayOf(-1, 0, 0, -1, 0, -1, -1, 0),
            page.drcs.getValue("65569").alpha,
        )
    }

    @Test
    fun 描く範囲と読み上げの文() {
        val page = page(example)!!
        // いちばん上の run (390) から、いちばん下の run の下の端 (450 + 60) まで
        assertEquals(390f, page.top)
        assertEquals(510f, page.bottom)
        // 外字は読まない。行が替われば改行
        assertEquals("字幕です\nじまく", page.text)
    }

    @Test
    fun 知らない版と崩れた1枚は描かない() {
        assertNull(page("""{"v":2,"plane":[960,540],"runs":[]}"""))
        assertNull(page("""{"plane":[960,540],"runs":[]}"""))
        assertNull(page("""{"v":1,"plane":[0,540],"runs":[]}"""))
        assertNull(page("""{"v":1,"plane":[960,540]}"""))
        assertNull(page("""[1,2]"""))
        // 読めない run (色が無い・座標が文字) だけ捨てる
        val page = page(
            """{"v":1,"plane":[960,540],"duration":null,"runs":[
                {"x":0,"y":0,"w":10,"h":10,"size":9,"text":"a"},
                {"x":"左","y":0,"w":10,"h":10,"size":9,"text":"b","fg":"#ffffffff"},
                {"x":0,"y":0,"w":10,"h":10,"size":9,"text":"c","fg":"#ffffffff"}]}""",
        )!!
        assertEquals(listOf("c"), page.runs.map { it.text })
        assertNull(page.durationMs)
        // 足りないものは既定 (背景は透明、横の縮めは 1、ずらしは 0)
        val run = page.runs.single()
        assertEquals(0, run.bg)
        assertEquals(1f, run.scaleX)
        assertEquals(0f, run.fx)
    }

    @Test
    fun 色を読む() {
        assertEquals(0x80ff0000.toInt(), captionColor("#ff000080"))
        assertEquals(0x00123456, captionColor("#12345600"))
        assertNull(captionColor("#fff"))
        assertNull(captionColor("ff000080"))
        assertNull(captionColor("#gg000080"))
        assertNull(captionColor(null))
    }

    @Test
    fun 外字の絵を濃さに開く() {
        // 4 階調 (2 ビット): 0, 1, 2, 3 → 0, 85, 170, 255
        val gray = decodeDrcs(4, 1, 4, 2, "Gw==")!!
        assertArrayEquals(byteArrayOf(0, 85, 170.toByte(), 255.toByte()), gray.alpha)
        // 中身が足りない・大きさがおかしい・base64 でない
        assertNull(decodeDrcs(8, 8, 2, 1, "lg=="))
        assertNull(decodeDrcs(0, 8, 2, 1, "lg=="))
        assertNull(decodeDrcs(4, 2, 2, 1, "!!"))
        assertNotNull(decodeDrcs(4, 2, 2, 1, "lg=="))
    }

    @Test
    fun 字はコードポイントで数え_空白は描かない() {
        val page = page(
            """{"v":1,"plane":[960,540],"runs":[{"x":0,"y":0,"w":10,"h":10,"size":9,"text":"𠮷 a　","fg":"#ffffffff"}]}""",
        )!!
        val run = page.runs.single()
        assertEquals(4, run.count)
        assertArrayEquals(intArrayOf(0, 2, 3, 4, 5), run.bounds)
        assertArrayEquals(booleanArrayOf(false, true, false, true), run.spaces)
        assertTrue(isCaptionSpace(0x2003))
        assertFalse(isCaptionSpace('字'.code))
    }

    @Test
    fun 永の墨を字の枠の真ん中に置く() {
        // 墨が上 0.8・下 0.1 なら、上下の空きは (1 - 0.9) / 2 ずつ。基準線は空き + 上の墨
        assertEquals(30.6f, captionBaseline(36f, 0.8f, 0.1f), 1e-4f)
        // 墨が枠いっぱいなら、基準線は上の墨の高さ
        assertEquals(28f, captionBaseline(36f, 28f / 36f, 8f / 36f), 1e-4f)
    }

    @Test
    fun 録画の字幕は位置を追い越していない最後の1枚() {
        fun entry(at: Double, duration: String, runs: String) =
            """{"at":$at,"page":{"v":1,"plane":[960,540],"duration":$duration,"runs":$runs}}"""
        val run = """[{"x":0,"y":0,"w":10,"h":10,"size":9,"text":"a","fg":"#ffffffff"}]"""
        val pages = CaptionPages.parse(
            """{"v":1,"pages":[${entry(5.0, "null", run)},${entry(1.0, "2000", run)},${entry(6.25, "null", "[]")},{"at":7,"page":{"v":9}}]}""",
        )!!
        // 読めない1枚は捨て、時刻の順に並べ直す
        assertEquals(3, pages.size)
        assertNull(pages.at(999))
        val first = pages.at(1_000)
        assertNotNull(first)
        assertSame(first, pages.at(2_999))
        // 出しておく長さを過ぎた
        assertNull(pages.at(3_000))
        assertNotNull(pages.at(5_000))
        // 空の1枚で消える
        assertNull(pages.at(6_250))
        assertNull(pages.at(60_000))
        assertNull(CaptionPages.parse("""{"v":2,"pages":[]}"""))
        assertNull(CaptionPages.parse("not json"))
    }
}
