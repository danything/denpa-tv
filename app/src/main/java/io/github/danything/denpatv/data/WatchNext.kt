package io.github.danything.denpatv.data

import java.util.TimeZone
import kotlin.math.abs

/**
 * Google TV のホームの「続きを視聴」(Watch Next) に出す・消す・直すを決める。**ここは素の関数だけ** で、
 * OS の TvProvider に書くのは `WatchNextRows` (Android 8.0 から)。
 *
 * - 録画を途中で閉じたら出す (`onStopped`)。最後まで観たら消す
 * - 録画の一覧を読み直すたびに合わせる (`syncWatchNext`): 消えた録画・続きの無くなった録画 (観終えた) は消し、
 *   続きの位置が denpa と違えば (ほかの端末で観た) 直す。**一覧からは足さない** (このテレビで観はじめたものだけ)
 * - **ホームで消された (BROWSABLE = 0) ものは、もう一度観るまで出し直さない。** 合わせるときは触らず、
 *   録画が消えたか観終えたら片付ける。途中で閉じたら (もう一度観た) 消して入れ直す (アプリからは BROWSABLE を戻せない)
 *
 * 行の目印は `INTERNAL_PROVIDER_ID` = 録画の id
 */

/** TvProvider にある、このアプリの行 */
data class WatchNextRow(
    val rowId: Long,
    val recordingId: Long,
    val positionMs: Long,
    val durationMs: Long,
    /** false ならホームで消された */
    val browsable: Boolean,
    /** 最後に観た (行を書いた) とき */
    val engagedAt: Long = 0,
)

/** 出したい中身 */
data class WatchNextItem(
    val recordingId: Long,
    val title: String,
    /** 局と放送の日時 (「ＮＨＫ総合１・東京 ・ 10/5(日) 21:00」) */
    val description: String,
    val durationMs: Long,
    val positionMs: Long,
    val engagedAt: Long,
    /** 選ばれたら開くリンク (docs/usage.md の「リンクで開く」) */
    val link: String,
)

sealed interface WatchNextChange {
    data class Insert(val item: WatchNextItem) : WatchNextChange
    /** 位置と長さだけ直す (題名・ポスターは変わらない) */
    data class Update(val rowId: Long, val recordingId: Long, val positionMs: Long, val durationMs: Long, val engagedAt: Long) : WatchNextChange
    data class Delete(val rowId: Long, val recordingId: Long) : WatchNextChange
}

/** 録画の長さ。焼いた長さ、無ければ予定の長さ (録画中)。分からなければ 0 */
fun Recording.lengthMs(): Long = durationMs?.takeIf { it > 0 } ?: endAt?.let { it - startAt }?.takeIf { it > 0 } ?: 0L

private fun watchNextLink(recordingId: Long) = "denpa://recording/$recordingId"

fun watchNextItem(recording: Recording, positionMs: Long, durationMs: Long, engagedAt: Long, zone: TimeZone = TimeZone.getDefault()) =
    WatchNextItem(
        recordingId = recording.id,
        title = recording.title,
        description = programMeta(recording.serviceName, recording.startAt, null, zone),
        durationMs = durationMs,
        positionMs = positionMs,
        engagedAt = engagedAt,
        link = watchNextLink(recording.id),
    )

/**
 * 録画を閉じた。`finished` (最後まで観た) か位置が頭なら消し、途中なら出す (あれば直す)。
 * ホームで消されていた行は、もう一度観たので消して入れ直す。`durationMs` は再生の長さ (分からなければ録画の長さ)
 */
fun onStopped(
    recording: Recording,
    positionMs: Long,
    durationMs: Long,
    finished: Boolean,
    rows: List<WatchNextRow>,
    now: Long,
    zone: TimeZone = TimeZone.getDefault(),
): List<WatchNextChange> {
    val mine = rows.filter { it.recordingId == recording.id }
    val length = durationMs.takeIf { it > 0 } ?: recording.lengthMs()
    if (finished || positionMs <= 0 || (length > 0 && positionMs >= length)) {
        return mine.map { WatchNextChange.Delete(it.rowId, it.recordingId) }
    }
    val keep = mine.firstOrNull { it.browsable }
    // 同じ録画の行が2つ以上あれば (ふつうは無い) 1つに。消された行は入れ直すので消す
    val drop = mine.filter { it !== keep }.map { WatchNextChange.Delete(it.rowId, it.recordingId) }
    val change = if (keep != null) {
        WatchNextChange.Update(keep.rowId, keep.recordingId, positionMs, length, now)
    } else {
        WatchNextChange.Insert(watchNextItem(recording, positionMs, length, now, zone))
    }
    return drop + change
}

/** これより小さい位置のずれは直さない (預けるのは 15 秒おき・秒の小数で、行の位置と少し食い違う) */
private const val WATCH_NEXT_POSITION_SLACK_MS = 5_000L

/**
 * 録画の一覧 (全部) を読み直した。消えた録画と続きの無い録画の行を消し、続きの位置が違えば直す。
 * ホームで消された行は直さない (出し直さない)。
 * **一覧を取りはじめた (`fetchedAt`) あとに書いた行は触らない** — 閉じたときに預けた位置がまだ一覧に入っていない
 * (続きが null に見える) かもしれない。次に読み直したときに合わせる
 */
fun syncWatchNext(recordings: List<Recording>, rows: List<WatchNextRow>, now: Long, fetchedAt: Long): List<WatchNextChange> {
    val byId = recordings.associateBy { it.id }
    return rows.mapNotNull { row ->
        val recording = byId[row.recordingId]
        val resume = recording?.resumeMs?.takeIf { it > 0 }
        when {
            row.engagedAt >= fetchedAt -> null
            recording == null || resume == null -> WatchNextChange.Delete(row.rowId, row.recordingId)
            !row.browsable -> null
            abs(resume - row.positionMs) < WATCH_NEXT_POSITION_SLACK_MS -> null
            else -> WatchNextChange.Update(row.rowId, row.recordingId, resume, recording.lengthMs().takeIf { it > 0 } ?: row.durationMs, now)
        }
    }
}
