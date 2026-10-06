package io.github.danything.denpatv

import io.github.danything.denpatv.data.CodecSwitch
import io.github.danything.denpatv.data.LiveQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecSwitchTest {
    @Test
    fun 選んだらすぐ切り替え中_最初の絵で済む() {
        val chosen = CodecSwitch(LiveQuality.H264).choose(LiveQuality.Av1)!!
        assertEquals("AV1 に切り替え中", chosen.label)
        // 頼む前の絵 (前の流れ) では済ませない
        val (early, none) = chosen.pictured()
        assertNull(none)
        assertEquals(chosen, early)
        val (done, switched) = chosen.requested(LiveQuality.Av1).pictured()
        assertEquals(LiveQuality.Av1, switched)
        assertEquals(CodecSwitch(LiveQuality.Av1), done)
        assertEquals("AV1", done.label)
    }

    @Test
    fun 同じものを選び直しても何もしない() {
        assertNull(CodecSwitch(LiveQuality.H264).choose(LiveQuality.H264))
        val chosen = CodecSwitch(LiveQuality.H264).choose(LiveQuality.Av1)!!
        assertNull(chosen.choose(LiveQuality.Av1))
        // 切り替え中に元のものを選び直せば、それへの切り替え
        assertEquals(LiveQuality.H264, chosen.choose(LiveQuality.H264)?.pending)
    }

    @Test
    fun 映せなければ元に戻す() {
        val chosen = CodecSwitch(LiveQuality.H264).choose(LiveQuality.Raw)!!.requested(LiveQuality.Raw)
        val (back, failed) = chosen.failed()
        assertEquals(LiveQuality.Raw, failed)
        assertEquals(CodecSwitch(LiveQuality.H264), back)
        // 切り替え中でなければ何もしない
        assertNull(back.failed().second)
    }

    @Test
    fun 切り替え中でない頼みは_映っている画質() {
        assertEquals(LiveQuality.Raw, CodecSwitch(LiveQuality.H264).requested(LiveQuality.Raw).shown)
        // 局を替えても、切り替え中の画質は待ち続ける
        val chosen = CodecSwitch(LiveQuality.H264).choose(LiveQuality.Av1)!!
        assertEquals(chosen, chosen.requested(LiveQuality.H264))
    }
}
