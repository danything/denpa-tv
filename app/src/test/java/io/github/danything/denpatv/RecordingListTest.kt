package io.github.danything.denpatv

import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.RecordingList
import io.github.danything.denpatv.data.reuse
import io.github.danything.denpatv.ui.nearFirst
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RecordingListTest {
    private fun recordings(total: Int) = (total downTo 1).map { Recording(id = it.toLong(), title = "", startAt = 0) }

    @Test
    fun 消すと隣を返す() = runTest {
        val list = RecordingList { recordings(25) }
        list.refresh()
        assertEquals(25, list.items.size)
        // 後ろ (古いほう) が隣。いちばん古いものなら前
        assertEquals(14L, list.remove(15))
        assertEquals(2L, list.remove(1))
        assertEquals(23, list.items.size)
        assertFalse(list.items.any { it.id == 15L })
        assertNull(list.remove(99))
    }

    /** 読み直している最中に消したら、消す前に作られた答えに混ざって戻らない */
    @Test
    fun 読み直す最中に消したものは戻らない() = runTest {
        val answer = CompletableDeferred<List<Recording>>()
        val list = RecordingList { answer.await() }
        val refreshing = async { list.refresh() }
        yield()
        list.remove(25)
        answer.complete(recordings(25))
        refreshing.await()
        assertFalse(list.items.any { it.id == 25L })
        assertEquals(24, list.items.size)
    }

    /** 読み直しても変わっていない録画は前のものを使い、何も変わっていなければ一覧ごと前のまま */
    @Test
    fun 変わっていない録画は前のものを使う() = runTest {
        var answer = recordings(5)
        val list = RecordingList { answer }
        list.refresh()
        val first = list.items
        answer = recordings(5)
        list.refresh()
        assertSame(first, list.items)

        // 1件だけ焼き上がった: その録画だけ新しく、ほかは前のもの
        answer = recordings(5).map { if (it.id == 3L) it.copy(title = "焼けた") else it }
        list.refresh()
        assertNotSame(first, list.items)
        list.items.forEach { if (it.id == 3L) assertEquals("焼けた", it.title) else assertSame(first.first { old -> old.id == it.id }, it) }
    }

    @Test
    fun 増えた録画と並びの変化() {
        val old = recordings(3)
        val fresh = recordings(4)
        val merged = reuse(old, fresh)
        assertEquals(fresh, merged)
        assertSame(old[0], merged[1])
        assertNotSame(old, merged)
        assertSame(fresh, reuse(emptyList(), fresh))
    }

    /** 先読みは近い順に、端は飛ばす */
    @Test
    fun 先読みは近い順() {
        assertEquals(listOf(3, 4, 2, 5, 1), nearFirst(3, 2, 10).take(5))
        assertEquals(listOf(0, 1, 2), nearFirst(0, 2, 10))
        assertEquals(listOf(4, 3, 2), nearFirst(4, 2, 5))
    }
}
