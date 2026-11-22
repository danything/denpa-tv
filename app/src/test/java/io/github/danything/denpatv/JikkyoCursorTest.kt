package io.github.danything.denpatv

import io.github.danything.denpatv.data.JikkyoComment
import io.github.danything.denpatv.data.JikkyoCursor
import io.github.danything.denpatv.data.JikkyoLanes
import io.github.danything.denpatv.data.JikkyoNg
import io.github.danything.denpatv.data.jumped
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

    /** くじがいつも同じ値を返す、高さ 14 の枠 (長さは行の高さを 1 として数える) */
    private fun lanes(draw: Double = 0.0, height: Float = 14f) = JikkyoLanes(height) { draw }

    @Test
    fun 行の高さは枠の_1_14_下限_14px() {
        assertEquals(1080f / 14, JikkyoLanes.lineHeight(1080f), 0.001f)
        assertEquals(14f, JikkyoLanes.lineHeight(100f))
    }

    @Test
    fun 大きいものと小さいものの比はニコニコと同じ() {
        assertEquals(1.625f, JikkyoLanes.sizeScale("big"), 0.0001f)
        assertEquals(0.625f, JikkyoLanes.sizeScale("small"), 0.0001f)
        assertEquals(1f, JikkyoLanes.sizeScale(null))
    }

    @Test
    fun どれも枠を_4_秒で渡りきり_長いものほど速い() {
        assertEquals(1200f / JikkyoLanes.SPAN_MS, JikkyoLanes.speedOf(1000f, 200f), 0.0001f)
        assertEquals(2 * JikkyoLanes.speedOf(1000f, 0f), JikkyoLanes.speedOf(1000f, 1000f), 0.0001f)
        assertEquals(JikkyoLanes.Flow(0.5f, JikkyoLanes.SPAN_MS), lanes().flow(0, 200f, 1000f, 20f))
    }

    @Test
    fun 上から積み_前のものの末尾が入りきるまで同じ高さに入れない() {
        val lanes = lanes()
        // 幅 1000px の枠で、幅 200px のコメント。速さは 1200px / 4 秒
        lanes.flow(0, 200f, 1000f, 20f)
        assertEquals(1.5f, lanes.flow(100, 200f, 1000f, 20f)?.top)
        // 末尾 (200px + 間 20px) が入りきるのは 733ms 後
        assertEquals(0.5f, lanes.flow(734, 200f, 1000f, 20f)?.top)
    }

    @Test
    fun 決まった行は持たない_大きいものは高さぶん場所を取り_次のものはその下に積む() {
        val lanes = lanes()
        assertEquals(0.5f, lanes.flow(0, 200f, 1000f, 30f, JikkyoLanes.sizeScale("big"))?.top)
        assertEquals(2.125f, lanes.flow(0, 200f, 1000f, 20f)?.top ?: 0f, 0.0001f)
        assertEquals(3.125f, lanes.flow(0, 200f, 1000f, 12f, JikkyoLanes.sizeScale("small"))?.top ?: 0f, 0.0001f)
    }

    @Test
    fun 普通の大きさなら_13_件_大きいものなら_8_件まで縦に並ぶ_それより多いとくじの高さに重ねる() {
        val normal = lanes(0.5)
        repeat(13) { assertEquals(0.5f + it, normal.flow(0, 200f, 1000f, 20f)?.top ?: 0f, 0.0001f) }
        // 余白の内 (0.5〜13.5) でくじ (0.5) を引く
        assertEquals(0.5f + 12 * 0.5f, normal.flow(0, 200f, 1000f, 20f)?.top ?: 0f, 0.0001f)
        val big = lanes(0.5)
        repeat(8) { big.flow(0, 200f, 1000f, 30f, JikkyoLanes.sizeScale("big")) }
        assertEquals(0.5f + (13 - 1.625f) * 0.5f, big.flow(0, 200f, 1000f, 30f, JikkyoLanes.sizeScale("big"))?.top ?: 0f, 0.0001f)
    }

    @Test
    fun 重ねたものは空きに数えない_飛んだら全部空ける() {
        val lanes = lanes(0.99, 4f)
        repeat(3) { lanes.flow(0, 200f, 1000f, 20f) }
        // どこも埋まっている。くじ (0.99) で下のほうに重ねる
        assertEquals(0.5f + 2 * 0.99f, lanes.flow(1, 200f, 1000f, 20f)?.top ?: 0f, 0.0001f)
        // 重ねたものは数えないので、空く時刻は前のまま
        assertEquals(0.5f, lanes.flow(734, 200f, 1000f, 20f)?.top)
        lanes.clear()
        assertEquals(0.5f, lanes.flow(1, 200f, 1000f, 20f)?.top)
    }

    @Test
    fun あとから来た速いものが_前のものに枠の中で追い付く所には入れない() {
        val lanes = lanes()
        // 短いもの (幅 100px) は遅い。末尾 (と間) が左の端を出るのは 4072ms
        lanes.flow(0, 100f, 1000f, 20f)
        // 長いもの (幅 1000px) は 2 倍の速さ。500ms に入ると 2500ms に左の端へ着き、前のものに追い付く
        assertEquals(1.5f, lanes.flow(500, 1000f, 1000f, 20f)?.top)
        val again = lanes()
        again.flow(0, 100f, 1000f, 20f)
        // 2100ms に入れば、左の端に着くのは前のものが出たあと
        assertEquals(0.5f, again.flow(2100, 1000f, 1000f, 20f)?.top)
    }

    @Test
    fun 枠の幅が_0_なら流さない() {
        assertNull(lanes().flow(0, 100f, 0f, 20f))
    }

    @Test
    fun 枠の高さが変わっても_置いた場所は行の高さを_1_とした割合のまま() {
        val lanes = lanes()
        lanes.flow(0, 200f, 1000f, 20f)
        lanes.resize(20f)
        assertEquals(1.5f, lanes.flow(0, 200f, 1000f, 20f)?.top)
    }

    @Test
    fun 止めるものは_3_秒置いて空ける_上は上の端から_下は下の端から積み_流れるものとは別に数える() {
        val lanes = lanes(0.5, 4f)
        assertEquals(JikkyoLanes.Flow(0.5f, 3000), lanes.hold(0, fromBottom = false))
        assertEquals(JikkyoLanes.Flow(2.5f, 3000), lanes.hold(0, fromBottom = true))
        assertEquals(1.5f, lanes.hold(1, fromBottom = true).top)
        // 空いた所が無ければ、くじ (0.5) で真ん中に重ねる
        assertEquals(JikkyoLanes.Flow(1.5f, 3000), lanes.hold(2, fromBottom = false))
        assertEquals(0.5f, lanes.hold(3000, fromBottom = false).top)
        // 流れるものの場所は空いたまま
        assertEquals(0.5f, lanes.flow(1, 100f, 1000f, 20f)?.top)
    }

    @Test
    fun 大きいものは高さぶん取る_下に止めるものは下の端から上へ積む() {
        val lanes = lanes()
        assertEquals(13.5f - 1.625f, lanes.hold(0, fromBottom = true, JikkyoLanes.sizeScale("big")).top, 0.0001f)
        assertEquals(13.5f - 1.625f - 1, lanes.hold(0, fromBottom = true).top, 0.0001f)
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
}
