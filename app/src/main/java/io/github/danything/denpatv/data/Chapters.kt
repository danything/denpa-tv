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
 * **CM の頭のどれだけ手前で飛ぶか** (ミリ秒、等速で)。入ってから飛ぶと、気付くまでの CM のコマが映る。
 * 跨ぐ直前に飛べば最後に映っているのは本編のコマで、飛ぶ間もそのまま残る (ブラウザの denpa の `CM_LEAD` と同じ考え)。
 *
 * ブラウザ (0.1 秒) より長いのは、ExoPlayer が少し先のコマまで画面へ渡しているうえ、再生の糸が詰まると飛ぶのが
 * そのぶん遅れるため。エミュレータでは 0.15 秒でもまれに CM が1コマ出た。本編の末尾がそのぶん欠けるが、
 * 境目は場面の切れ目なので分からない
 */
const val CM_LEAD_MS = 200L

/**
 * 続いた CM を1つにまとめたもの (頭は最初の CM の頭、終わりは最後の CM の終わり)。
 * まとめないと、飛んだ先がまた CM の頭で、もう一度飛ぶまでの間にそのコマが映る
 */
fun cmRuns(chapters: List<ChapterMark>): List<ChapterMark> {
    val runs = mutableListOf<ChapterMark>()
    for (chapter in chapters) {
        if (!chapter.isCm) continue
        val last = runs.lastOrNull()
        if (last != null && chapter.startMs <= last.endMs + JOIN_MS) runs[runs.lastIndex] = last.copy(endMs = maxOf(last.endMs, chapter.endMs))
        else runs += chapter
    }
    return runs
}

/** `positionMs` を含む CM (続いたものはまとめて)。無ければ null */
fun cmRunAt(chapters: List<ChapterMark>, positionMs: Long): ChapterMark? =
    cmRuns(chapters).firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }

/**
 * いまの位置が CM の中 (か、頭の `leadMs` 手前) なら、その CM (続いたものはまとめて。`endMs` が飛ぶ先)。でなければ null。
 *
 * `skipped` は一度飛ばした CM の頭。**戻して観に行った CM は二度と飛ばさない** —
 * 人が自分で戻ったなら観たいのであって、飛ばし返すと抜けられなくなる
 */
fun cmSkipTarget(chapters: List<ChapterMark>, positionMs: Long, skipped: Set<Long>, leadMs: Long = 0): ChapterMark? =
    cmRuns(chapters).firstOrNull { positionMs >= it.startMs - leadMs && positionMs < it.endMs - END_SLACK_MS && it.startMs !in skipped }

/**
 * 先回りして飛ぶところ (位置と、そこで飛ぶ CM)。**まだ来ていない CM ごとに、頭の `leadMs` 手前** (0 より前にはしない)。
 * もうその手前の幅に入っている CM は入れない (`cmSkipTarget` で今すぐ飛ぶ)。飛ばしても得が無いほど短い CM も入れない
 */
fun cmHopPoints(chapters: List<ChapterMark>, positionMs: Long, skipped: Set<Long>, leadMs: Long): List<Pair<Long, ChapterMark>> =
    cmRuns(chapters)
        .filter { it.startMs !in skipped && it.startMs < it.endMs - END_SLACK_MS }
        .map { (it.startMs - leadMs).coerceAtLeast(0) to it }
        .filter { (at, _) -> at > positionMs }

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

/** これだけの隙間なら続いた CM とみなす (チャプターの境目の丸めの差) */
private const val JOIN_MS = 10L
