package io.github.danything.denpatv

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import io.github.danything.denpatv.data.AudioSide
import io.github.danything.denpatv.ui.DualMonoProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(UnstableApi::class)
class DualMonoProcessorTest {
    private fun stereo(processor: DualMonoProcessor) {
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    /** 左右の組を流して、出てきた左右の組 */
    private fun run(processor: DualMonoProcessor, vararg frames: Pair<Short, Short>): List<Pair<Short, Short>> {
        val input = ByteBuffer.allocateDirect(frames.size * 4).order(ByteOrder.nativeOrder())
        frames.forEach { (l, r) -> input.putShort(l).putShort(r) }
        input.flip()
        processor.queueInput(input)
        val output = processor.output
        return List(output.remaining() / 4) { output.short to output.short }
    }

    @Test
    fun 主は左を_副は右を両耳へ_主副はそのまま() {
        val processor = DualMonoProcessor()
        stereo(processor)
        assertTrue(processor.isActive)
        assertEquals(listOf<Pair<Short, Short>>(100.toShort() to (-200).toShort()), run(processor, 100.toShort() to (-200).toShort()))
        processor.side = AudioSide.Main
        assertEquals(listOf<Pair<Short, Short>>(100.toShort() to 100.toShort()), run(processor, 100.toShort() to (-200).toShort()))
        // 繋ぎ直さなくても、替えたらすぐ効く
        processor.side = AudioSide.Sub
        assertEquals(listOf<Pair<Short, Short>>((-200).toShort() to (-200).toShort()), run(processor, 100.toShort() to (-200).toShort()))
    }

    @Test
    fun 二チャンネルの_PCM_でなければ掛けない() {
        val mono = DualMonoProcessor()
        mono.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        mono.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertFalse(mono.isActive)
        val surround = DualMonoProcessor()
        surround.configure(AudioProcessor.AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT))
        surround.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertFalse(surround.isActive)
    }
}
