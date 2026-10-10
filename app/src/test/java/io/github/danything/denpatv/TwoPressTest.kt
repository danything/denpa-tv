package io.github.danything.denpatv

import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.RecordingFile
import io.github.danything.denpatv.data.TwoPress
import io.github.danything.denpatv.data.codecLabels
import io.github.danything.denpatv.data.durationLabel
import io.github.danything.denpatv.data.watched
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoPressTest {
    /** 1回目は「もう一度押すと…」、間を空けずにもう1回で実行。間が空いたら数え直し */
    @Test
    fun 二回押しで実行し_間が空いたら数え直す() {
        val press = TwoPress(windowMs = 4_000)
        assertFalse(press.press(0))
        assertTrue(press.press(3_000))

        assertFalse(press.press(10_000))
        assertFalse(press.press(15_000))
        press.reset()
        assertFalse(press.press(15_500))
    }
}

class RecordingCardTest {
    private fun rec(durationMs: Long? = 1_800_000, resumeMs: Long? = null, codecs: List<String> = emptyList()) =
        Recording(id = 1, title = "", startAt = 0, durationMs = durationMs, resumeMs = resumeMs,
            files = codecs.map { RecordingFile(it, it, "") })

    @Test
    fun 観た割合と長さと形の札() {
        assertEquals(0.5f, rec(resumeMs = 900_000).watched)
        assertNull(rec(resumeMs = null).watched)
        assertNull(rec(durationMs = null, resumeMs = 900_000).watched)
        assertEquals("30分", durationLabel(1_800_000))
        assertEquals("1時間", durationLabel(3_600_000))
        assertEquals("1時間24分", durationLabel(84 * 60_000 + 10_000))
        assertEquals(listOf("AV1", "H.264", "生TS"), rec(codecs = listOf("mpeg2", "h264", "av1")).codecLabels)
    }
}
