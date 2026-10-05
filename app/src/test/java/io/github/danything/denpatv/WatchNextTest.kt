package io.github.danything.denpatv

import io.github.danything.denpatv.data.DeepLink
import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.WatchNextChange
import io.github.danything.denpatv.data.WatchNextRow
import io.github.danything.denpatv.data.lengthMs
import io.github.danything.denpatv.data.onStopped
import io.github.danything.denpatv.data.syncWatchNext
import io.github.danything.denpatv.data.watchNextItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class WatchNextTest {
    private val tokyo = TimeZone.getTimeZone("Asia/Tokyo")

    // 2026-10-04 21:00 (日本時間)
    private val start = 1_791_115_200_000L

    private fun recording(id: Long, resumeMs: Long? = null, durationMs: Long? = 1_800_000) = Recording(
        id = id,
        title = "番組 $id",
        serviceName = "ＮＨＫ総合１・東京",
        startAt = start,
        durationMs = durationMs,
        resumeMs = resumeMs,
    )

    private fun row(rowId: Long, recordingId: Long, positionMs: Long = 600_000, browsable: Boolean = true, engagedAt: Long = 0) =
        WatchNextRow(rowId, recordingId, positionMs, 1_800_000, browsable, engagedAt)

    @Test
    fun 中身() {
        val item = watchNextItem(recording(12), 600_000, 1_800_000, 5, tokyo)
        assertEquals("番組 12", item.title)
        assertEquals("ＮＨＫ総合１・東京 ・ 10/4(日) 21:00", item.description)
        assertEquals(600_000, item.positionMs)
        assertEquals(1_800_000, item.durationMs)
        assertEquals(5, item.engagedAt)
        // アプリのリンクで開く (README の「リンクで開く」)
        assertEquals(DeepLink.Watch("12"), DeepLink.parse(item.link))
    }

    @Test
    fun 局の名前が無ければ日時だけ() {
        val item = watchNextItem(recording(1).copy(serviceName = null), 1, 2, 0, tokyo)
        assertEquals("10/4(日) 21:00", item.description)
    }

    @Test
    fun 長さは焼いた長さ_無ければ予定の長さ() {
        assertEquals(1_800_000, recording(1).lengthMs())
        assertEquals(3_600_000, recording(1, durationMs = null).copy(endAt = start + 3_600_000).lengthMs())
        assertEquals(0, recording(1, durationMs = null).lengthMs())
    }

    @Test
    fun 途中で閉じたら出す() {
        val changes = onStopped(recording(12), 600_000, 1_800_000, finished = false, rows = emptyList(), now = 9, zone = tokyo)
        assertEquals(listOf(WatchNextChange.Insert(watchNextItem(recording(12), 600_000, 1_800_000, 9, tokyo))), changes)
    }

    @Test
    fun あれば位置を直す() {
        val changes = onStopped(recording(12), 900_000, 1_800_000, false, listOf(row(3, 12), row(4, 13)), now = 9)
        assertEquals(listOf(WatchNextChange.Update(3, 12, 900_000, 1_800_000, 9)), changes)
    }

    @Test
    fun 再生の長さが分からなければ録画の長さ() {
        val changes = onStopped(recording(12), 900_000, 0, false, listOf(row(3, 12)), now = 9)
        assertEquals(listOf(WatchNextChange.Update(3, 12, 900_000, 1_800_000, 9)), changes)
    }

    @Test
    fun 最後まで観たら消す() {
        val rows = listOf(row(3, 12), row(4, 13))
        val delete = listOf(WatchNextChange.Delete(3, 12))
        assertEquals(delete, onStopped(recording(12), 1_000_000, 1_800_000, finished = true, rows = rows, now = 9))
        // 末尾の位置・頭の位置も
        assertEquals(delete, onStopped(recording(12), 1_800_000, 1_800_000, false, rows, 9))
        assertEquals(delete, onStopped(recording(12), 0, 1_800_000, false, rows, 9))
        // 行が無ければ何もしない
        assertEquals(emptyList<WatchNextChange>(), onStopped(recording(12), 0, 1_800_000, true, emptyList(), 9))
    }

    @Test
    fun ホームで消されていても_もう一度観たら入れ直す() {
        val changes = onStopped(recording(12), 600_000, 1_800_000, false, listOf(row(3, 12, browsable = false)), now = 9, zone = tokyo)
        assertEquals(
            listOf(WatchNextChange.Delete(3, 12), WatchNextChange.Insert(watchNextItem(recording(12), 600_000, 1_800_000, 9, tokyo))),
            changes,
        )
    }

    @Test
    fun 同じ録画の行が重なっていたら1つに() {
        val changes = onStopped(recording(12), 600_000, 1_800_000, false, listOf(row(3, 12, browsable = false), row(4, 12), row(5, 12)), 9)
        assertEquals(
            listOf(WatchNextChange.Delete(3, 12), WatchNextChange.Delete(5, 12), WatchNextChange.Update(4, 12, 600_000, 1_800_000, 9)),
            changes,
        )
    }

    @Test
    fun 読み直したら_消えた録画と観終えた録画を消す() {
        val recordings = listOf(recording(12, resumeMs = 600_000), recording(13, resumeMs = null), recording(14, resumeMs = 0))
        val rows = listOf(row(1, 11), row(2, 12), row(3, 13), row(4, 14))
        assertEquals(
            listOf(WatchNextChange.Delete(1, 11), WatchNextChange.Delete(3, 13), WatchNextChange.Delete(4, 14)),
            syncWatchNext(recordings, rows, now = 9, fetchedAt = 5),
        )
    }

    @Test
    fun 読み直したら_ほかの端末で観た位置に直す() {
        val recordings = listOf(recording(12, resumeMs = 1_200_000), recording(13, resumeMs = 602_000))
        val rows = listOf(row(2, 12), row(3, 13))
        // 少しのずれ (預けるのは 15 秒おき) は直さない
        assertEquals(listOf(WatchNextChange.Update(2, 12, 1_200_000, 1_800_000, 9)), syncWatchNext(recordings, rows, now = 9, fetchedAt = 5))
    }

    @Test
    fun 読み直しても_ホームで消された行は出し直さない() {
        val recordings = listOf(recording(12, resumeMs = 1_200_000), recording(13, resumeMs = null))
        val rows = listOf(row(2, 12, browsable = false), row(3, 13, browsable = false))
        // 位置は直さない (触らない)。観終えた録画のものは片付ける
        assertEquals(listOf(WatchNextChange.Delete(3, 13)), syncWatchNext(recordings, rows, now = 9, fetchedAt = 5))
    }

    @Test
    fun 読み直しでは足さない() {
        assertEquals(emptyList<WatchNextChange>(), syncWatchNext(listOf(recording(12, resumeMs = 600_000)), emptyList(), 9, 5))
    }

    @Test
    fun 取りはじめたあとに書いた行は触らない() {
        // 閉じて書いた行。預けた位置はまだ一覧に入っていない (続きが null に見える)
        val rows = listOf(row(2, 12, engagedAt = 6))
        assertEquals(emptyList<WatchNextChange>(), syncWatchNext(listOf(recording(12, resumeMs = null)), rows, now = 9, fetchedAt = 5))
        assertEquals(listOf(WatchNextChange.Delete(2, 12)), syncWatchNext(listOf(recording(12, resumeMs = null)), rows, now = 9, fetchedAt = 7))
    }
}
