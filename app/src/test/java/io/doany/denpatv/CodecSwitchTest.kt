package io.doany.denpatv

import io.doany.denpatv.data.CodecSwitch
import io.doany.denpatv.data.LiveQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecSwitchTest {
    @Test
    fun 選んだらすぐ切り替え中_最初のフレームで済む() {
        val chosen = CodecSwitch(LiveQuality.H264).choose(LiveQuality.Av1)!!
        assertEquals("AV1 に切り替え中", chosen.label(LiveQuality.H264))
        // リクエストする前のフレーム (前のストリーム) では済ませない
        val (early, none) = chosen.pictured()
        assertNull(none)
        assertEquals(chosen, early)
        val (done, switched) = chosen.requested(LiveQuality.Av1).pictured()
        assertEquals(LiveQuality.Av1, switched?.choice)
        assertEquals(CodecSwitch(LiveQuality.Av1), done)
        assertEquals("AV1", done.label(LiveQuality.Av1))
    }

    /** 自動 (null) も選択の1つ。名前には denpa が選んだ形式 (応答ヘッダ) を添える */
    @Test
    fun 自動に切り替える() {
        val chosen = CodecSwitch(LiveQuality.Raw).choose(null)!!
        assertEquals("自動に切り替え中", chosen.label(LiveQuality.Raw))
        assertNull(chosen.chosen)
        val (done, switched) = chosen.requested(null).pictured()
        assertNull(switched!!.choice)
        assertEquals(CodecSwitch(null), done)
        assertEquals("自動 (H.264)", done.label(LiveQuality.H264))
        assertEquals("自動", done.label(null))
        // 自動から選んだものへ
        assertEquals(LiveQuality.Raw, CodecSwitch(null).choose(LiveQuality.Raw)?.chosen)
    }

    @Test
    fun 同じものを選び直しても何もしない() {
        assertNull(CodecSwitch(LiveQuality.H264).choose(LiveQuality.H264))
        assertNull(CodecSwitch(null).choose(null))
        val chosen = CodecSwitch(LiveQuality.H264).choose(LiveQuality.Av1)!!
        assertNull(chosen.choose(LiveQuality.Av1))
        // 切り替え中に映っているものを選び直せば、切り替えをやめる
        assertEquals(CodecSwitch(LiveQuality.H264), chosen.choose(LiveQuality.H264))
        // 切り替え中に別のものを選べば、そちらへ
        assertEquals(LiveQuality.Raw, chosen.choose(LiveQuality.Raw)?.pending?.choice)
        assertEquals(CodecSwitch.Pending(null), chosen.choose(null)?.pending)
    }

    @Test
    fun 映せなければ元に戻す() {
        val chosen = CodecSwitch(LiveQuality.H264).choose(LiveQuality.Raw)!!.requested(LiveQuality.Raw)
        val (back, failed) = chosen.failed()
        assertEquals(LiveQuality.Raw, failed?.choice)
        assertEquals(CodecSwitch(LiveQuality.H264), back)
        // 切り替え中でなければ何もしない
        assertNull(back.failed().second)
    }

    @Test
    fun 切り替え中でないリクエストは_映っている画質() {
        assertEquals(LiveQuality.Raw, CodecSwitch(LiveQuality.H264).requested(LiveQuality.Raw).shown)
        assertNull(CodecSwitch(LiveQuality.H264).requested(null).shown)
        // 局を替えても、切り替え中の画質は待ち続ける
        val chosen = CodecSwitch(LiveQuality.H264).choose(LiveQuality.Av1)!!
        assertEquals(chosen, chosen.requested(LiveQuality.H264))
    }
}
