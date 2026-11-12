package io.doany.denpatv

import io.doany.denpatv.data.JikkyoRanking
import io.doany.denpatv.data.ListHold
import io.doany.denpatv.data.Service
import io.doany.denpatv.ui.JikkyoRankingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「実況」の列の並べ方。ブラウザの denpa の `ranking.test.ts` と同じ場合を見る。局の ID は番号順 (地上波 → BS → CS) に渡す */
class JikkyoRankingTest {
    private val ids = listOf(1L, 2L, 3L, 4L, 5L)

    @Test
    fun 勢いの大きい順_同じ勢いは番号順() {
        val forces = mapOf(1L to 3, 2L to 36, 3L to 27, 4L to 36, 5L to 0)
        assertEquals(listOf(2L, 4L, 3L, 1L, 5L), JikkyoRanking.order(ids, forces))
    }

    @Test
    fun 放送中のスレッドが無い局は末尾に番号順で() {
        assertEquals(listOf(4L, 2L, 1L, 3L, 5L), JikkyoRanking.order(ids, mapOf(4L to 10, 2L to 5)))
    }

    @Test
    fun 読めないときは番号順のまま() {
        assertEquals(ids, JikkyoRanking.order(ids, null))
    }

    @Test
    fun 返事は局の_ID_から勢い() {
        assertEquals(mapOf(7L to 36), JikkyoRanking.parse("""{"at":1,"channels":[{"serviceId":7,"force":36}]}"""))
        // denpa の局の ID は大きい (地上波は 10 桁)
        assertEquals(mapOf(3227310008L to 1), JikkyoRanking.parse("""{"at":1,"channels":[{"serviceId":3227310008,"force":1}]}"""))
        assertEquals(emptyMap<Long, Int>(), JikkyoRanking.parse("""{"at":1,"channels":[]}"""))
    }

    @Test
    fun 形式がずれていれば_null() {
        assertNull(JikkyoRanking.parse("""{"at":1}"""))
        assertNull(JikkyoRanking.parse("""{"channels":[{"serviceId":"7","force":1}]}"""))
        assertNull(JikkyoRanking.parse("null"))
        assertNull(JikkyoRanking.parse("<html>"))
    }

    @Test
    fun 並べる局は実況のある局を種別の順に() {
        fun s(id: Long, type: String, jikkyo: String?) = Service(id = id, type = type, name = "", live = "", jikkyo = jikkyo)
        val services = listOf(s(1, "BS", "jk101"), s(2, "GR", "jk1"), s(3, "GR", null), s(4, "CS", "jk333"), s(5, "GR", "jk4"))
        assertEquals(listOf(2L, 5L, 1L, 4L), JikkyoRanking.candidates(services).map { it.id })
    }
}

/** フォーカスのある間は並べ直しを待つ (ブラウザの `ListHold` と同じ。テレビの理由はフォーカスだけ) */
@OptIn(ExperimentalCoroutinesApi::class)
class ListHoldTest {
    private val settle = 20L

    @Test
    fun 離れてから待って並べ直す_離れる前は並べ直さない() = runTest {
        var released = 0
        val hold = ListHold(backgroundScope, settle) { released++ }
        assertFalse(hold.held)
        hold.set(true)
        advanceTimeBy(settle * 2)
        assertEquals(0, released)
        assertTrue(hold.held)
        hold.set(false)
        // 離した瞬間には替えない
        runCurrent()
        assertEquals(0, released)
        assertTrue(hold.held)
        advanceTimeBy(settle * 2)
        assertEquals(1, released)
        assertFalse(hold.held)
    }

    @Test
    fun 待っている間にまた入ったら並べ直さない() = runTest {
        var released = 0
        val hold = ListHold(backgroundScope, settle) { released++ }
        hold.set(true)
        hold.set(false)
        hold.set(true)
        advanceTimeBy(settle * 2)
        assertEquals(0, released)
        hold.set(false)
        advanceTimeBy(settle * 2)
        assertEquals(1, released)
    }

    @Test
    fun 入っていないのに離れても並べ直さない() = runTest {
        var released = 0
        val hold = ListHold(backgroundScope, settle) { released++ }
        hold.set(false)
        advanceTimeBy(settle * 2)
        assertEquals(0, released)
    }

    @Test
    fun 閉じたら待つのをやめ_入っている印も忘れる() = runTest {
        var released = 0
        val hold = ListHold(backgroundScope, settle) { released++ }
        hold.set(true)
        hold.set(false)
        hold.clear()
        assertFalse(hold.held)
        advanceTimeBy(settle * 2)
        assertEquals(0, released)
        hold.set(true)
        hold.clear()
        assertFalse(hold.held)
    }
}

/** 「実況」の列の勢いと並び (`JikkyoRankingState`)。フォーカスのある間は数字だけ替え、離れて 1 秒たってから並べ直す */
@OptIn(ExperimentalCoroutinesApi::class)
class JikkyoRankingStateTest {
    private val ids = listOf(1L, 2L, 3L)

    @Test
    fun フォーカスが無ければすぐ並べ直し_ある間は数字だけ替えて離れて1秒後に並べ直す() = runTest {
        val state = JikkyoRankingState(backgroundScope)
        assertNull(state.order)
        state.received(ids, mapOf(3L to 10))
        assertEquals(listOf(3L, 1L, 2L), state.order)

        state.hold(true)
        state.received(ids, mapOf(2L to 50))
        // 数字はすぐ、並びはまだ
        assertEquals(mapOf(2L to 50), state.forces)
        assertEquals(listOf(3L, 1L, 2L), state.order)
        advanceTimeBy(JikkyoRanking.SETTLE_MS * 5)
        assertEquals(listOf(3L, 1L, 2L), state.order)

        state.hold(false)
        advanceTimeBy(JikkyoRanking.SETTLE_MS - 1)
        runCurrent()
        assertEquals(listOf(3L, 1L, 2L), state.order)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(2L, 1L, 3L), state.order)
    }

    @Test
    fun 読めなければ番号順に戻して印を立てる_閉じたら待っていた並びをすぐ入れる() = runTest {
        val state = JikkyoRankingState(backgroundScope)
        state.received(ids, mapOf(3L to 10))
        state.hold(true)
        state.received(ids, null)
        assertTrue(state.failed)
        assertNull(state.forces)
        assertEquals(listOf(3L, 1L, 2L), state.order)
        // メニューを閉じた (フォーカスのあるまま列が消えた)
        state.release()
        assertEquals(ids, state.order)
        // もう待っていない: 次の返事はすぐ並べ直す
        state.received(ids, mapOf(2L to 1))
        assertEquals(listOf(2L, 1L, 3L), state.order)
    }

    @Test
    fun 実況をオフにしたら空に戻す() = runTest {
        val state = JikkyoRankingState(backgroundScope)
        state.received(ids, null)
        state.hold(true)
        state.reset()
        assertNull(state.order)
        assertNull(state.forces)
        assertFalse(state.failed)
        // 待つ印も消えている: オンに戻した最初の返事はすぐ並べ直す
        state.received(ids, mapOf(3L to 1))
        assertEquals(listOf(3L, 1L, 2L), state.order)
    }
}
