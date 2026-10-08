package io.github.danything.denpatv

import io.github.danything.denpatv.data.ChapterMark
import io.github.danything.denpatv.data.CM_LEAD_MS
import io.github.danything.denpatv.data.cmHopPoints
import io.github.danything.denpatv.data.cmRunAt
import io.github.danything.denpatv.data.cmRuns
import io.github.danything.denpatv.data.cmSkipTarget
import io.github.danything.denpatv.data.nextChapter
import io.github.danything.denpatv.data.previousChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** denpa が焼くときに書くチャプター (cm.ts の chapterMetadata): 本編と CM が交互に並ぶ */
class ChaptersTest {
    private val chapters = listOf(
        ChapterMark(0, 60_000, "本編"),
        ChapterMark(60_000, 150_000, "CM"),
        ChapterMark(150_000, 900_000, "本編"),
        ChapterMark(900_000, 990_000, "CM"),
        ChapterMark(990_000, 1_800_000, "本編"),
    )

    @Test
    fun CM_の中なら終わりへ飛ぶ() {
        assertEquals(150_000L, cmSkipTarget(chapters, 60_100, emptySet())?.endMs)
        assertNull(cmSkipTarget(chapters, 30_000, emptySet()))
        // 終わり際は飛ばさない (飛んでも得が無い)
        assertNull(cmSkipTarget(chapters, 149_500, emptySet()))
    }

    @Test
    fun 一度飛ばした_CM_や自分で戻って観に行った_CM_は飛ばさない() {
        assertNull(cmSkipTarget(chapters, 70_000, setOf(60_000L)))
        assertEquals(990_000L, cmSkipTarget(chapters, 900_000, setOf(60_000L))?.endMs)
    }

    @Test
    fun CM_の頭の手前から飛ぶ() {
        // 手前の幅に入っていれば、まだ本編でも飛ぶ
        assertEquals(150_000L, cmSkipTarget(chapters, 59_990, emptySet(), CM_LEAD_MS)?.endMs)
        assertNull(cmSkipTarget(chapters, 60_000 - CM_LEAD_MS - 1, emptySet(), CM_LEAD_MS))
        assertNull(cmSkipTarget(chapters, 59_990, emptySet()))
    }

    @Test
    fun 先回りして飛ぶところは_まだ来ていない_CM_の頭の手前() {
        assertEquals(
            listOf(60_000L - CM_LEAD_MS to 60_000L, 900_000L - CM_LEAD_MS to 900_000L),
            cmHopPoints(chapters, 0, emptySet(), CM_LEAD_MS).map { (at, cm) -> at to cm.startMs },
        )
        // 過ぎた CM・飛ばした (戻って観に行った) CM は入れない
        assertEquals(listOf(900_000L - CM_LEAD_MS), cmHopPoints(chapters, 200_000, emptySet(), CM_LEAD_MS).map { it.first })
        assertEquals(listOf(900_000L - CM_LEAD_MS), cmHopPoints(chapters, 0, setOf(60_000L), CM_LEAD_MS).map { it.first })
        // もう手前の幅に入っているもの・中に居るものは今すぐ飛ぶほう (cmSkipTarget)
        assertEquals(listOf(900_000L - CM_LEAD_MS), cmHopPoints(chapters, 59_990, emptySet(), CM_LEAD_MS).map { it.first })
        assertEquals(60_000L, cmSkipTarget(chapters, 59_990, emptySet(), CM_LEAD_MS)?.startMs)
        // 手前の幅の1つ前なら、まだ先回りして預ける
        assertEquals(60_000L - CM_LEAD_MS, cmHopPoints(chapters, 60_000 - CM_LEAD_MS - 1, emptySet(), CM_LEAD_MS).first().first)
    }

    @Test
    fun 頭から_CM_なら_0_より前にはせず今すぐ飛ぶ() {
        val opening = listOf(ChapterMark(0, 30_000, "CM"), ChapterMark(30_000, 600_000, "本編"))
        assertEquals(emptyList<Long>(), cmHopPoints(opening, 0, emptySet(), CM_LEAD_MS).map { it.first })
        assertEquals(30_000L, cmSkipTarget(opening, 0, emptySet(), CM_LEAD_MS)?.endMs)
        // 頭の CM が手前の幅より短い位置で始まるときも 0 で止める
        val early = listOf(ChapterMark(0, 50, "本編"), ChapterMark(50, 30_000, "CM"), ChapterMark(30_000, 600_000, "本編"))
        assertEquals(emptyList<Long>(), cmHopPoints(early, 0, emptySet(), CM_LEAD_MS).map { it.first })
        assertEquals(30_000L, cmSkipTarget(early, 0, emptySet(), CM_LEAD_MS)?.endMs)
    }

    @Test
    fun 続いた_CM_はまとめて跨ぐ() {
        val back = listOf(
            ChapterMark(0, 60_000, "本編"),
            ChapterMark(60_000, 75_000, "CM"),
            ChapterMark(75_000, 90_000, "CM"),
            ChapterMark(90_000, 600_000, "本編"),
        )
        assertEquals(listOf(ChapterMark(60_000, 90_000, "CM")), cmRuns(back))
        assertEquals(listOf(60_000L - CM_LEAD_MS), cmHopPoints(back, 0, emptySet(), CM_LEAD_MS).map { it.first })
        assertEquals(90_000L, cmSkipTarget(back, 80_000, emptySet())?.endMs)
        // 後ろの CM に戻っても、まとめた頭で覚える
        assertEquals(60_000L, cmRunAt(back, 80_000)?.startMs)
        assertNull(cmSkipTarget(back, 80_000, setOf(60_000L)))
    }

    @Test
    fun 短すぎる_CM_は先回りしない() {
        val short = listOf(ChapterMark(0, 60_000, "本編"), ChapterMark(60_000, 60_500, "CM"), ChapterMark(60_500, 600_000, "本編"))
        assertEquals(emptyList<Long>(), cmHopPoints(short, 0, emptySet(), CM_LEAD_MS).map { it.first })
    }

    @Test
    fun チャプター送り() {
        assertEquals(150_000L, nextChapter(chapters, 70_000)?.startMs)
        assertNull(nextChapter(chapters, 1_000_000))
        // 入って 3 秒より後なら、いまのチャプターの頭へ。3 秒以内ならその1つ前へ
        assertEquals(150_000L, previousChapter(chapters, 200_000)?.startMs)
        assertEquals(60_000L, previousChapter(chapters, 151_000)?.startMs)
    }
}

class SpeedTest {
    @org.junit.Test
    fun 速さは段を巡り_段に無い値は等速() {
        assertEquals(1.25f, io.github.danything.denpatv.data.nextSpeed(1f))
        assertEquals(1f, io.github.danything.denpatv.data.nextSpeed(2f))
        assertEquals(1.25f, io.github.danything.denpatv.data.nextSpeed(3f))
        // 段に無い値 (前の版の 1.1 など) は等速として扱い、その次へ
        assertEquals(1f, io.github.danything.denpatv.data.knownSpeed(1.1f))
        assertEquals(1.25f, io.github.danything.denpatv.data.nextSpeed(1.1f))
        assertEquals(1.5f, io.github.danything.denpatv.data.nextSpeed(1.25f))
        assertEquals(1f, io.github.danything.denpatv.data.knownSpeed(null))
        assertEquals(1f, io.github.danything.denpatv.data.knownSpeed(0.75f))
        assertEquals(1.5f, io.github.danything.denpatv.data.knownSpeed(1.5f))
        assertEquals("1.25×", io.github.danything.denpatv.data.speedLabel(1.25f))
        assertEquals("2×", io.github.danything.denpatv.data.speedLabel(2f))
    }

    @org.junit.Test
    fun 流れている最中に速さを変えたときだけ飛び直す() {
        assertEquals(true, io.github.danything.denpatv.data.resyncAfterSpeedChange(1f, 1.25f, playing = true))
        // 始まる前 (開いたときに覚えていた速さを入れる) は飛ばない
        assertEquals(false, io.github.danything.denpatv.data.resyncAfterSpeedChange(1f, 1.25f, playing = false))
        // 同じ速さ (画面を作り直しただけ) なら飛ばない
        assertEquals(false, io.github.danything.denpatv.data.resyncAfterSpeedChange(1.25f, 1.25f, playing = true))
    }
}
