package io.github.danything.denpatv

import io.github.danything.denpatv.data.CommentStore
import io.github.danything.denpatv.data.JikkyoComment
import io.github.danything.denpatv.data.JikkyoList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** 右の列の「コメントの一覧」(denpa の `list.test.ts` と同じ例。テレビは巻かないので、巻く決まりのぶんは持たない) */
class JikkyoListTest {
    private fun at(ms: Long, text: String = "c$ms") = JikkyoComment(ms, text)

    @Test
    fun 窓より古いものは並べない_上限は_200_件() {
        val store = CommentStore()
        val w = JikkyoList.SPAN_MS + 10_000
        store.add(listOf(at(5_000)) + (0 until 300).map { at(w - it) })
        val rows = store.latest(w - JikkyoList.SPAN_MS, w, JikkyoList.MAX)
        assertEquals(JikkyoList.MAX, rows.size)
        assertEquals(w, rows.last().at)
        assertFalse(store.latest(w - JikkyoList.SPAN_MS, w, 1000).any { it.at == 5_000L })
    }

    @Test
    fun 遅れて届いたものも_読み直せば時刻の位置に入る() {
        val store = CommentStore()
        store.add(listOf(at(1), at(3)))
        val before = store.latest(0, 5, 10)
        store.add(listOf(at(2)))
        val after = store.latest(0, 5, 10)
        assertEquals(listOf(1L, 2L, 3L), after.map { it.at })
        assertFalse(JikkyoList.sameRows(before, after))
    }

    @Test
    fun 件数と両端が同じなら描き直さない() {
        val a = at(1)
        val b = at(2)
        assertTrue(JikkyoList.sameRows(listOf(a, b), listOf(a, b)))
        assertFalse(JikkyoList.sameRows(listOf(a), listOf(a, b)))
        assertTrue(JikkyoList.sameRows(emptyList(), emptyList()))
    }

    @Test
    fun 時刻は_h_mm_ss() {
        val zone = TimeZone.getTimeZone("Asia/Tokyo")
        val ms = Calendar.getInstance(zone).apply { set(2026, 9, 31, 9, 5, 7) }.timeInMillis
        assertEquals("9:05:07", JikkyoList.airClock(ms, zone))
    }
}
