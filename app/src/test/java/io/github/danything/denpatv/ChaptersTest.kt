package io.github.danything.denpatv

import io.github.danything.denpatv.data.ChapterMark
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
        assertEquals(1f, io.github.danything.denpatv.data.knownSpeed(null))
        assertEquals(1f, io.github.danything.denpatv.data.knownSpeed(0.75f))
        assertEquals(1.5f, io.github.danything.denpatv.data.knownSpeed(1.5f))
        assertEquals("1.25×", io.github.danything.denpatv.data.speedLabel(1.25f))
        assertEquals("2×", io.github.danything.denpatv.data.speedLabel(2f))
    }
}
