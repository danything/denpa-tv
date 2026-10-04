package io.github.danything.denpatv

import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.RecordingList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
