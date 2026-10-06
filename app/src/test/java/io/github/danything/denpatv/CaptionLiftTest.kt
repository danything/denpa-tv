package io.github.danything.denpatv

import io.github.danything.denpatv.data.captionLift
import io.github.danything.denpatv.data.inkRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** 画面まるごとの字幕の絵から、字のある行を探す */
    @Test
    fun 字のある行を探す() {
        val width = 4
        val rows = Array(10) { IntArray(width) }
        rows[6][2] = 0xFF000000.toInt()
        rows[8][0] = 0x01FFFFFF
        assertEquals(6 to 9, inkRows(width, 10) { y, into -> rows[y].copyInto(into) })
        // 透明な白 (アルファ 0) は字ではない
        assertNull(inkRows(width, 10) { _, into -> into.fill(0x00FFFFFF) })
    }
}
