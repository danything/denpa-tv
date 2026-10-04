package io.github.danything.denpatv

import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.RecordingPager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPagerTest {
    private fun rec(id: Long) = Recording(id = id, title = "$id", startAt = 0)

    /** id が 1..total の録画を新しい順 (大きい順) に返す偽の denpa */
    private class Server(val total: Int) {
        val calls = mutableListOf<Pair<Int, Int>>()
        fun page(limit: Int, offset: Int): List<Recording> {
            calls += limit to offset
            return (total downTo 1).drop(offset).take(limit).map { Recording(id = it.toLong(), title = "", startAt = 0) }
        }
    }

    @Test
    fun 続きを読み_尽きたら止まる() = runTest {
        val server = Server(25)
        val pager = RecordingPager(10, server::page)
        pager.refresh()
        assertEquals(10, pager.items.size)
        assertTrue(pager.hasMore)
        pager.loadMore()
        pager.loadMore()
        assertEquals(25, pager.items.size)
        assertFalse(pager.hasMore)
        assertFalse(pager.loadMore())
        assertEquals(listOf(10 to 0, 10 to 10, 10 to 20), server.calls)
    }

    @Test
    fun 読む間に増えてずれたぶんは重ねない() = runTest {
        var shift = 0
        val pager = RecordingPager(3) { limit, offset ->
            // 2回目は新しい録画が1つ増えて、前のページの最後が繰り返される
            ((10 + shift) downTo 1).drop(offset).take(limit).map { rec(it.toLong()) }.also { shift = 1 }
        }
        pager.refresh()
        pager.loadMore()
        assertEquals(listOf(10L, 9L, 8L, 7L, 6L), pager.items.map { it.id })
    }

    @Test
    fun 読み込み中の続きの呼び出しは捨てる() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val pager = RecordingPager(2) { limit, offset ->
            calls++
            if (offset > 0) gate.await()
            (100 downTo 1).drop(offset).take(limit).map { rec(it.toLong()) }
        }
        pager.refresh()
        val first = async { pager.loadMore() }
        yield()
        assertFalse(pager.loadMore())
        gate.complete(Unit)
        assertTrue(first.await())
        assertEquals(2, calls)
        assertEquals(4, pager.items.size)
    }

    @Test
    fun 消したものは手元から抜き_続きまで読んだぶんは残す() = runTest {
        val pager = RecordingPager(10, Server(25)::page)
        pager.refresh()
        pager.loadMore()
        pager.remove(15)
        assertEquals(19, pager.items.size)
        assertFalse(pager.items.any { it.id == 15L })
    }

    /**
     * 読んでいる最中に消したら、その答えは捨てる。頼んだ offset は消す前の数なので、denpa が消したあとに
     * 答えると1件飛ぶ。次の続き読みで、消したあとの数から頼み直す
     */
    @Test
    fun 読んでいる最中に消したら答えを捨て_消したあとの数から頼み直す() = runTest {
        val all = (25 downTo 1).map { it.toLong() }.toMutableList()
        val gate = CompletableDeferred<Unit>()
        val offsets = mutableListOf<Int>()
        val pager = RecordingPager(10) { limit, offset ->
            offsets += offset
            if (offsets.size == 2) gate.await()
            all.drop(offset).take(limit).map { rec(it) }
        }
        pager.refresh()
        val inFlight = async { pager.loadMore() }
        yield()
        // 読んでいる間に 18 を消す (denpa からも消える)
        all.remove(18L)
        pager.remove(18)
        gate.complete(Unit)
        assertFalse(inFlight.await())
        assertEquals(9, pager.items.size)

        assertTrue(pager.loadMore())
        assertEquals(listOf(0, 10, 9), offsets)
        // 飛ばしも重なりも無い
        assertEquals((25 downTo 1).map { it.toLong() }.filter { it != 18L }.take(19), pager.items.map { it.id })
    }

    @Test
    fun 読み直している最中に消したものは戻さない() = runTest {
        val gate = CompletableDeferred<Unit>()
        val pager = RecordingPager(10) { limit, offset ->
            gate.await()
            (25 downTo 1).drop(offset).take(limit).map { rec(it.toLong()) }
        }
        val refreshing = async { pager.refresh() }
        yield()
        pager.remove(25)
        gate.complete(Unit)
        refreshing.await()
        assertFalse(pager.items.any { it.id == 25L })
        assertEquals(9, pager.items.size)
    }
}
