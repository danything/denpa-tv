package io.github.danything.denpatv.data

/**
 * 録画のチャプター1つ (ミリ秒)。**動画そのものに入っているもの** (denpa が焼くときに
 * `本編` / `CM` の名前で書く。denpa の `cm.ts` の `chapterMetadata`)。読むのは Media3
 * (Matroska の Chapters を `Chapter` として出す。1.11 から)
 */
data class ChapterMark(val startMs: Long, val endMs: Long, val title: String) {
    val isCm: Boolean get() = title == CM_TITLE

    companion object {
        const val CM_TITLE = "CM"
    }
}

/**
 * いまの位置が CM の中なら、その CM の終わり (飛ぶ先)。中でなければ null。
 *
 * `skipped` は一度飛ばした CM の頭。**戻して観に行った CM は二度と飛ばさない** —
 * 人が自分で戻ったなら観たいのであって、飛ばし返すと抜けられなくなる
 */
fun cmSkipTarget(chapters: List<ChapterMark>, positionMs: Long, skipped: Set<Long>): ChapterMark? =
    chapters.firstOrNull { it.isCm && positionMs >= it.startMs && positionMs < it.endMs - END_SLACK_MS && it.startMs !in skipped }

/** 次のチャプターの頭。無ければ null */
fun nextChapter(chapters: List<ChapterMark>, positionMs: Long): ChapterMark? =
    chapters.firstOrNull { it.startMs > positionMs + 500 }

/**
 * 前のチャプターの頭。いまのチャプターに入って 3 秒以内なら、その1つ前 (再生機の「前へ」と同じ)
 */
fun previousChapter(chapters: List<ChapterMark>, positionMs: Long): ChapterMark? {
    val current = chapters.lastOrNull { it.startMs <= positionMs } ?: return null
    if (positionMs - current.startMs > 3_000) return current
    return chapters.lastOrNull { it.startMs < current.startMs } ?: current
}

/** CM の終わり際 (これより後ろなら飛ばしても得が無い) */
private const val END_SLACK_MS = 1_000L
