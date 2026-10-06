package io.github.danything.denpatv

import androidx.media3.common.text.Cue
import io.github.danything.denpatv.data.captionLift
import io.github.danything.denpatv.data.inkRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import io.github.danything.denpatv.ui.cueSpan
import org.junit.Test

class CaptionLiftTest {
    /** 帯が出ていなければ動かさない */
    @Test
    fun 帯が無ければ動かさない() {
        assertEquals(0f, captionLift(1080f, 0f, top = 900f, bottom = 1000f, gap = 24f))
    }

    /** 下の字幕は、帯の上の端より gap 上まで持ち上げる */
    @Test
    fun 下の字幕は帯の上へ持ち上げる() {
        // 帯の上の端は 1080 - 300 = 780。字幕の下の端 1000 を 780 - 24 = 756 まで
        assertEquals(244f, captionLift(1080f, 300f, top = 900f, bottom = 1000f, gap = 24f))
    }

    /** もとから帯より上にある字幕 (上に出ている字幕) は動かさない */
    @Test
    fun 帯より上の字幕は動かさない() {
        assertEquals(0f, captionLift(1080f, 300f, top = 40f, bottom = 140f, gap = 24f))
    }

    /** 帯が高すぎる (ライブのメニュー) ときは、画面の上の端に着いたところで止める */
    @Test
    fun 画面の上へははみ出させない() {
        assertEquals(900f, captionLift(1080f, 1000f, top = 900f, bottom = 1000f, gap = 24f))
    }

    /** 画面まるごとの字幕の絵から、字のある行を探す (何行かずつ読む。読む束の境目をまたいでも同じ) */
    @Test
    fun 字のある行を探す() {
        val width = 4
        val rows = Array(10) { IntArray(width) }
        rows[6][2] = 0xFF000000.toInt()
        rows[8][0] = 0x01FFFFFF
        val read = { y: Int, count: Int, into: IntArray ->
            for (i in 0 until count) rows[y + i].copyInto(into, i * width)
        }
        for (band in listOf(1, 3, 4, 32)) assertEquals(6 to 9, inkRows(width, 10, band, read))
        // 1行だけ
        assertEquals(6 to 7, inkRows(width, 7, 3, read))
        // 透明な白 (アルファ 0) は字ではない
        assertNull(inkRows(width, 10, 4) { _, count, into -> into.fill(0x00FFFFFF, 0, count * width) })
    }

    /** 置き場所の無い文字の字幕は、SubtitleView と同じく下から 8% のところに下揃え */
    @Test
    fun 文字の字幕は下揃え() {
        val (top, bottom) = cueSpan(listOf(Cue.Builder().setText("灯、少し休んだらどうだ").build()), 1920, 1080)!!
        assertEquals(1080 * 0.92f, bottom, 0.5f)
        assertEquals(1080 * 0.0533f * 1.3f, bottom - top, 0.5f)
    }

    /** 上揃えの文字の字幕は、折り返すぶんも下へ伸ばして見積もる (少なく見積もると帯に重なる) */
    @Test
    fun 折り返す字幕は行を足して見積もる() {
        val cue = Cue.Builder().setText("あ".repeat(50)).setLine(0.5f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START).build()
        val (top, bottom) = cueSpan(listOf(cue), 1920, 1080)!!
        assertEquals(540f, top, 0.5f)
        assertEquals(2 * 1080 * 0.0533f * 1.3f, bottom - top, 0.5f)
        // 何も出ていなければ null
        assertNull(cueSpan(emptyList(), 1920, 1080))
    }
}
