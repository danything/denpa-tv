package io.github.danything.denpatv

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import io.github.danything.denpatv.ui.Buffering
import io.github.danything.denpatv.ui.BufferingControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class BufferingControlTest {
    private val mb = 1024L * 1024
    private val player = PlayerId("test")
    private val timeline = SinglePeriodTimeline(C.TIME_UNSET, false, true, false, null, MediaItem.Builder().build())

    /** `buffered` マイクロ秒ぶん溜まっているときに、溜めるか・動き出すかを聞くもの */
    private fun parameters(buffered: Long) = LoadControl.Parameters(
        player,
        timeline,
        MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
        0,
        buffered,
        1f,
        true,
        false,
        C.TIME_UNSET,
        C.TIME_UNSET,
    )

    /** 上限を `limit` バイトにして、プレーヤーに渡したときと同じく準備したもの */
    private fun prepared(buffering: Buffering, limit: Long): BufferingControl {
        val control = BufferingControl(buffering, targetBytes = limit.toInt())
        control.onPrepared(player)
        control.onTracksSelected(parameters(0), TrackGroupArray.EMPTY, arrayOf())
        return control
    }

    /** プレーヤーが読み込むときと同じく、割り当て口から `bytes` ぶん取る */
    private fun fill(control: BufferingControl, bytes: Long) {
        val allocator = control.getAllocator(player)
        repeat((bytes / C.DEFAULT_BUFFER_SEGMENT_SIZE).toInt()) { allocator.allocate() }
    }

    /** ヒープが 192 MB の端末 (denpa-tv#142 の BRAVIA) では 48 MB まで。ヒープが大きくても Media3 の既定は超えない */
    @Test
    fun 溜める量はヒープの4分の1で_Media3の既定を超えない() {
        assertEquals(48 * mb, BufferingControl.bytesFor(192 * mb).toLong())
        assertEquals(128 * mb, BufferingControl.bytesFor(512 * mb).toLong())
        assertEquals(BufferingControl.MEDIA3_DEFAULT_BYTES, BufferingControl.bytesFor(4096 * mb))
    }

    /** 録画は 50 秒まで溜めるが、届く前でも上限のバイトまで溜まったら読むのを止める (溜め続けてヒープを使い切らない) */
    @Test
    fun 上限のバイトまで溜まったら50秒に届かなくても止める() {
        val control = prepared(Buffering.Recording, 2 * mb)
        assertTrue(control.shouldContinueLoading(parameters(10_000_000)))
        fill(control, 2 * mb)
        assertFalse(control.shouldContinueLoading(parameters(10_000_000)))
    }

    /** 動き出す時間 (録画は 1 秒) に届く前に上限のバイトまで溜まったら、読むのを止めたまま待たずに動き出す */
    @Test
    fun 上限のバイトまで溜まったら動き出す() {
        val control = prepared(Buffering.Recording, 2 * mb)
        assertFalse(control.shouldStartPlayback(parameters(500_000)))
        fill(control, 2 * mb)
        assertTrue(control.shouldStartPlayback(parameters(500_000)))
    }
}
