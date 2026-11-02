package io.github.danything.denpatv

import io.github.danything.denpatv.data.JikkyoComment
import io.github.danything.denpatv.data.JikkyoCursor
import io.github.danything.denpatv.data.JikkyoLanes
import io.github.danything.denpatv.data.JikkyoNg
import io.github.danything.denpatv.data.jumped
import io.github.danything.denpatv.data.jikkyoSupported
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 控えから出す (`cursor.test.ts`)・行の割り当て (`lanes.test.ts`)・NG ワード (`ng.test.ts`)。例は denpa の単体テストと同じ */
class JikkyoCursorTest {
    private val t = 1_793_188_800_000L

    private fun at(ms: Long, text: String = ms.toString()) = JikkyoComment(t + ms, text)
    private fun texts(take: JikkyoCursor.Take) = take.comments.map { it.text }

    @Test
    fun 前の_W_から今の_W_までに書かれたものを出す_最初の_1_回は出さない() {
        val cursor = JikkyoCursor()
        cursor.receive(listOf(at(500), at(1500), at(2500)))
        assertEquals(JikkyoCursor.Take(emptyList(), false), cursor.take(t + 1000))
        assertEquals(listOf("1500"), texts(cursor.take(t + 2000)))
        assertEquals(listOf("2500"), texts(cursor.take(t + 3000)))
    }

    @Test
    fun W_が戻った_大きく飛んだら_飛んだ先から出し直す() {
        val cursor = JikkyoCursor()
        cursor.receive(listOf(at(500), at(30_500), at(31_500)))
        cursor.take(t)
        assertEquals(JikkyoCursor.Take(emptyList(), true), cursor.take(t + 30_000))
        assertEquals(listOf("30500", "31500"), texts(cursor.take(t + 32_000)))
        assertTrue(cursor.take(t).jumped)
        assertEquals(listOf("500"), texts(cursor.take(t + 1000)))
    }

    @Test
    fun 時計の合わせ直しで少し戻っただけなら飛んだことにせず_W_も戻さない() {
        val cursor = JikkyoCursor()
        cursor.receive(listOf(at(1500), at(2500)))
        cursor.take(t + 2000)
        assertEquals(JikkyoCursor.Take(emptyList(), false), cursor.take(t + 1800))
        assertEquals(listOf("2500"), texts(cursor.take(t + 3000)))
    }

    @Test
    fun 遅れて届いたものは_10_秒以内なら次に出す_それより古いものは出さない() {
        val cursor = JikkyoCursor()
        cursor.take(t + 20_000)
        cursor.receive(listOf(at(15_000, "遅れた"), at(5_000, "古い")))
        assertEquals(listOf("遅れた"), texts(cursor.take(t + 20_100)))
    }

    @Test
    fun オフにしていた間に進んだぶんは出さない() {
        val cursor = JikkyoCursor()
        cursor.receive(listOf(at(1500), at(2500)))
        cursor.take(t + 1000)
        cursor.pause()
        assertEquals(emptyList<JikkyoComment>(), cursor.take(t + 2000).comments)
        assertEquals(listOf("2500"), texts(cursor.take(t + 3000)))
    }

    @Test
    fun 放送時刻が飛んだか() {
        assertFalse(jumped(null, 0))
        assertFalse(jumped(10_000, 10_250))
        assertFalse(jumped(10_000, 9_500))
        assertTrue(jumped(10_000, 5_000))
        assertTrue(jumped(10_000, 30_000))
    }

    @Test
    fun 行の高さは枠の_1_14_下限_14px_上下に余白を残しておよそ_13_行() {
        assertEquals(1080f / 14, JikkyoLanes.lineHeight(1080f), 0.001f)
        assertEquals(14f, JikkyoLanes.lineHeight(100f))
        assertEquals(13, JikkyoLanes.laneCount(1080f))
    }

    @Test
    fun 上の行から使い_前のコメントの尻尾が入りきるまで同じ行に入れない() {
        val lanes = JikkyoLanes(2)
        // 幅 1000px の枠で、幅 200px のコメント。速さは 1000px / 4 秒
        assertEquals(JikkyoLanes.Flow(0, (1000 + 200) * JikkyoLanes.SPAN_MS / 1000), lanes.flow(0, 200f, 1000f, 20f))
        assertEquals(1, lanes.flow(100, 200f, 1000f, 20f)?.lane)
        // 尻尾 (200px + 間 20px) が入りきるのは 880ms 後
        assertNull(lanes.flow(800, 200f, 1000f, 20f))
        assertEquals(0, lanes.flow(880, 200f, 1000f, 20f)?.lane)
    }

    @Test
    fun 空いた行が無ければ捨てる_飛んだら全部空ける() {
        val lanes = JikkyoLanes(1)
        lanes.flow(0, 100f, 1000f, 20f)
        assertNull(lanes.flow(1, 100f, 1000f, 20f))
        lanes.clear()
        assertEquals(0, lanes.flow(1, 100f, 1000f, 20f)?.lane)
    }

    @Test
    fun 止めるものは_3_秒置いて空ける_上は上の行から_下は下の行から積み_流れるものとは別に数える() {
        val lanes = JikkyoLanes(3)
        assertEquals(JikkyoLanes.Flow(0, 3000), lanes.hold(0, fromBottom = false))
        assertEquals(JikkyoLanes.Flow(2, 3000), lanes.hold(0, fromBottom = true))
        assertEquals(1, lanes.hold(1, fromBottom = true)?.lane)
        assertNull(lanes.hold(2, fromBottom = false))
        assertEquals(0, lanes.hold(3000, fromBottom = false)?.lane)
        // 流れるものの行は空いたまま
        assertEquals(0, lanes.flow(1, 100f, 1000f, 20f)?.lane)
    }

    @Test
    fun 大きいものは_1_25_倍_小さいものは_0_75_倍() {
        assertEquals(1.25f, JikkyoLanes.sizeScale("big"))
        assertEquals(0.75f, JikkyoLanes.sizeScale("small"))
        assertEquals(1f, JikkyoLanes.sizeScale(null))
    }

    @Test
    fun NG_ワードは部分一致で引っかける() {
        val ng = JikkyoNg.compile(listOf("ネタバレ"))
        assertTrue(ng("これはネタバレだ"))
        assertFalse(ng("ネタ"))
    }

    @Test
    fun 全角_半角_半角カナ_英字の大小_ひらがな_カタカナは同じとみなす() {
        val ng = JikkyoNg.compile(listOf("ｗｗｗ", "ﾈﾀﾊﾞﾚ", "ABC"))
        assertTrue(ng("草www"))
        assertTrue(ng("ねたばれ注意"))
        assertTrue(ng("abc"))
        assertTrue(JikkyoNg.compile(listOf("かわいい"))("カワイイ"))
        assertTrue(JikkyoNg.compile(listOf("ゞ"))("ヾ"))
    }

    @Test
    fun 正規表現としては読まない() {
        val ng = JikkyoNg.compile(listOf(".*"))
        assertFalse(ng("なんでも"))
        assertTrue(ng("a.*b"))
    }

    @Test
    fun 語が無ければ何も引っかけない() {
        assertFalse(JikkyoNg.compile(emptyList())("なんでも"))
        assertFalse(JikkyoNg.compile(listOf("  "))("なんでも"))
    }

    @Test
    fun 長音はそのまま() {
        assertEquals("らーめん", JikkyoNg.fold("ラーメン"))
    }

    @Test
    fun NG_ワードの答えを読む_壊れていれば_null_上限を超えたぶんは捨てる() {
        assertEquals(listOf("a", "b"), JikkyoNg.parse("""{"words":["a","b"]}"""))
        assertEquals(listOf("a"), JikkyoNg.parse("""{"words":["a",1," ",null]}"""))
        assertNull(JikkyoNg.parse("{"))
        assertNull(JikkyoNg.parse("""["a"]"""))
        assertNull(JikkyoNg.parse("""{"a":1}"""))
        val many = (0..JikkyoNg.NG_MAX).joinToString(",") { "\"w$it\"" }
        assertEquals(JikkyoNg.NG_MAX, JikkyoNg.parse("""{"words":[$many]}""")?.size)
        // 長すぎる語は捨てる。絵文字も1文字と数える
        assertEquals(emptyList<String>(), JikkyoNg.parse("""{"words":["${"あ".repeat(JikkyoNg.NG_LENGTH + 1)}"]}"""))
        assertEquals(1, JikkyoNg.parse("""{"words":["${"😀".repeat(JikkyoNg.NG_LENGTH)}"]}""")?.size)
    }

    @Test
    fun 古い_denpa_では実況を出さない_版の分からない_denpa_では出す() {
        assertFalse(jikkyoSupported("v2.25.0"))
        assertTrue(jikkyoSupported("v2.26.0"))
        assertTrue(jikkyoSupported("dev"))
        assertFalse(jikkyoSupported(null))
    }
}
