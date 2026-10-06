package io.github.danything.denpatv

import androidx.media3.common.PlaybackException
import io.github.danything.denpatv.data.Chase
import io.github.danything.denpatv.data.ChaseEnd
import io.github.danything.denpatv.data.Reconnect
import io.github.danything.denpatv.data.Reconnect.Verdict
import io.github.danything.denpatv.data.StallWatch
import io.github.danything.denpatv.data.chaseEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectTest {
    @Test
    fun 待ちは1秒から倍々で10秒で頭打ち() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L), (0..5).map(Reconnect::delayMs))
        // 何度失敗しても溢れない
        assertEquals(10_000L, Reconnect.delayMs(1_000))
        assertEquals(1_000L, Reconnect.delayMs(-1))
    }

    @Test
    fun 失敗の見分け() {
        val badStatus = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertEquals(Verdict.Unauthorized, Reconnect.verdict(badStatus, 401))
        // 局が無い・録画が無い・認められていないは、待っても同じ
        assertEquals(Verdict.GiveUp, Reconnect.verdict(badStatus, 404))
        assertEquals(Verdict.GiveUp, Reconnect.verdict(badStatus, 403))
        assertEquals(Verdict.GiveUp, Reconnect.verdict(badStatus, 400))
        // 混んでいる・待ちきれない・denpa の入れ替え (前段の 502 など)
        assertEquals(Verdict.Retry, Reconnect.verdict(badStatus, 429))
        assertEquals(Verdict.Retry, Reconnect.verdict(badStatus, 408))
        assertEquals(Verdict.Retry, Reconnect.verdict(badStatus, 502))
        assertEquals(Verdict.Retry, Reconnect.verdict(badStatus, 503))
        // 繋がらない・読みが途切れた・待ちきれない
        assertEquals(Verdict.Retry, Reconnect.verdict(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null))
        assertEquals(Verdict.Retry, Reconnect.verdict(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null))
        assertEquals(Verdict.Retry, Reconnect.verdict(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, null))
        assertEquals(Verdict.Retry, Reconnect.verdict(PlaybackException.ERROR_CODE_TIMEOUT, null))
        // 中身が読めない (途中で切れた fMP4 もここ)・解けないは何度か
        assertEquals(Verdict.RetryFew, Reconnect.verdict(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, null))
        assertEquals(Verdict.RetryFew, Reconnect.verdict(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, null))
        assertEquals(Verdict.RetryFew, Reconnect.verdict(PlaybackException.ERROR_CODE_UNSPECIFIED, null))
        assertEquals(Verdict.RetryFew, Reconnect.verdict(PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, null))
        // ファイルが無い・平文が許されていない
        assertEquals(Verdict.GiveUp, Reconnect.verdict(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, null))
        assertEquals(Verdict.GiveUp, Reconnect.verdict(PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED, null))
    }

    @Test
    fun ライブと追っかけは回線の失敗なら何度でも繋ぎ直す() {
        assertTrue(Reconnect.shouldRetry(Verdict.Retry, stream = true, attempts = 1_000, few = 0))
        assertTrue(Reconnect.shouldRetry(Verdict.RetryFew, stream = true, attempts = 1_000, few = Reconnect.FEW - 1))
        assertFalse(Reconnect.shouldRetry(Verdict.RetryFew, stream = true, attempts = 1_000, few = Reconnect.FEW))
        assertFalse(Reconnect.shouldRetry(Verdict.GiveUp, stream = true, attempts = 0, few = 0))
        assertFalse(Reconnect.shouldRetry(Verdict.Unauthorized, stream = true, attempts = 0, few = 0))
    }

    @Test
    fun 録画のファイルは回線の失敗だけを何度か() {
        assertTrue(Reconnect.shouldRetry(Verdict.Retry, stream = false, attempts = Reconnect.FEW - 1, few = 0))
        assertFalse(Reconnect.shouldRetry(Verdict.Retry, stream = false, attempts = Reconnect.FEW, few = 0))
        assertFalse(Reconnect.shouldRetry(Verdict.RetryFew, stream = false, attempts = 0, few = 0))
        assertFalse(Reconnect.shouldRetry(Verdict.GiveUp, stream = false, attempts = 0, few = 0))
    }

    @Test
    fun 位置も溜まりも10秒動かなければ止まった() {
        val watch = StallWatch()
        assertNull(watch.check(0, watching = true, positionMs = 5_000, bufferedMs = 6_000))
        assertNull(watch.check(9_999, watching = true, positionMs = 5_000, bufferedMs = 6_000))
        assertEquals(10_000L, watch.check(10_000, watching = true, positionMs = 5_000, bufferedMs = 6_000))
        // 見つけたら数え直す (待っている間に何度も鳴らない)
        assertNull(watch.check(11_000, watching = true, positionMs = 5_000, bufferedMs = 6_000))
        assertEquals(10_000L, watch.check(20_000, watching = true, positionMs = 5_000, bufferedMs = 6_000))
    }

    @Test
    fun 位置か溜まりが動いていれば止まっていない() {
        val watch = StallWatch()
        watch.check(0, watching = true, positionMs = 0, bufferedMs = 1_000)
        // 絵は止まっていても届いている (溜めている最中)
        assertNull(watch.check(6_000, watching = true, positionMs = 0, bufferedMs = 2_000))
        assertNull(watch.check(12_000, watching = true, positionMs = 0, bufferedMs = 2_000))
        // 届いてはいないが進んでいる (溜めたぶんを流している)
        assertNull(watch.check(15_000, watching = true, positionMs = 3_000, bufferedMs = 2_000))
        assertEquals(10_000L, watch.check(25_000, watching = true, positionMs = 3_000, bufferedMs = 2_000))
    }

    @Test
    fun 見張っていない間は数えない() {
        val watch = StallWatch()
        watch.check(0, watching = true, positionMs = 0, bufferedMs = 0)
        // 一時停止・映す前・繋ぎ直しを待っている間
        assertNull(watch.check(30_000, watching = false, positionMs = 0, bufferedMs = 0))
        // 見張りに戻ったところから数える
        assertNull(watch.check(39_000, watching = true, positionMs = 0, bufferedMs = 0))
        assertEquals(10_000L, watch.check(40_000, watching = true, positionMs = 0, bufferedMs = 0))
    }

    @Test
    fun 追っかけの流れが終わったのは録り終えたときだけ() {
        // まだ録っている・denpa に聞けない (入れ替わっている最中) なら切れた
        assertEquals(ChaseEnd.Lost, chaseEnd(stillRecording = true, positionMs = 600_000, durationMs = null, pictured = true))
        assertEquals(ChaseEnd.Lost, chaseEnd(stillRecording = null, positionMs = 600_000, durationMs = 600_000, pictured = true))
        // 録り終えて尻まで観た
        assertEquals(ChaseEnd.Finished, chaseEnd(stillRecording = false, positionMs = 1_790_000, durationMs = 1_800_000, pictured = true))
        // 録り終えたが途中で切れた (denpa の入れ替え)。居た場所から続ける
        assertEquals(ChaseEnd.Lost, chaseEnd(stillRecording = false, positionMs = 600_000, durationMs = 1_800_000, pictured = true))
        // 頼み直しても何も映らずに終わった・長さが分からない (前と同じく観終えたことに)
        assertEquals(ChaseEnd.Finished, chaseEnd(stillRecording = false, positionMs = 600_000, durationMs = 1_800_000, pictured = false))
        assertEquals(ChaseEnd.Finished, chaseEnd(stillRecording = false, positionMs = 600_000, durationMs = null, pictured = true))
    }

    @Test
    fun 追っかけで映る前に繋がらないのは断られたのではない() {
        val parse = PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
        // 焼くのを断られた空の 200 は、形が分からないと言われる
        assertTrue(Chase.refused(started = false, baked = true, httpStatus = null, errorCode = parse))
        // denpa の入れ替えの最中 (繋ぎ直しの途中) に繋がらない・読めないのは、繋ぎ直しに任せる
        assertFalse(Chase.refused(started = false, baked = true, httpStatus = null, errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertFalse(Chase.refused(started = false, baked = true, httpStatus = null, errorCode = PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        // HTTP の番号があるもの・映したあと・生の TS は断られたのではない
        assertFalse(Chase.refused(started = false, baked = true, httpStatus = 503, errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertFalse(Chase.refused(started = true, baked = true, httpStatus = null, errorCode = parse))
        assertFalse(Chase.refused(started = false, baked = false, httpStatus = null, errorCode = parse))
    }
}
