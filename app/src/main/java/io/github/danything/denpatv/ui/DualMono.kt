package io.github.danything.denpatv.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioMixingUtil
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import io.github.danything.denpatv.data.AudioSide
import io.github.danything.denpatv.data.dualMonoCoefficients
import java.nio.ByteBuffer

/**
 * **デュアルモノの片側を両耳へ配り直す** (ブラウザの denpa の生の道 `raw/engine.ts` と、焼く道の `-af pan` と同じ)。
 *
 * 2ch の PCM にだけ掛かり、`side` を替えればすぐ (いま出しているぶんの溜めの後から) 効く。`Both` ならそのまま通す。
 *
 * Media3 の `ChannelMixingAudioProcessor` をそのまま使わないのは、**係数を決めるのが繋ぎ直し (`configure`) のときだけ**だから —
 * そのまま (単位行列) で繋ぐと休んだきりになり、観ている途中で主音声に替えても効かない (次に形式が変わるまで)。
 * 係数の形 (`ChannelMixingMatrix`) と混ぜ方 (`AudioMixingUtil.mix`) は同じものを使い、いつも動かしておいて毎回 `side` を読む。
 *
 * **PCM にしか掛からない** — AAC は既定どおり端末のデコーダで PCM に解いてから流すので掛かる (DefaultAudioSink は AAC を
 * そのまま出す道 (passthrough) を使わず、オフロードも既定で切)。焼いた AV1 の Opus も同じ
 */
@OptIn(UnstableApi::class)
class DualMonoProcessor : BaseAudioProcessor() {
    /** どちら側を出すか。画面から替える (再生の糸から読む) */
    @Volatile
    var side: AudioSide = AudioSide.Both

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.channelCount == 2 && AudioMixingUtil.canMix(inputAudioFormat)) inputAudioFormat else AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / inputAudioFormat.bytesPerFrame
        val output = replaceOutputBuffer(frames * outputAudioFormat.bytesPerFrame)
        AudioMixingUtil.mix(inputBuffer, inputAudioFormat, output, outputAudioFormat, MATRICES.getValue(side), frames, false, true)
        output.flip()
    }

    private companion object {
        val MATRICES = AudioSide.entries.associateWith { ChannelMixingMatrix(2, 2, dualMonoCoefficients(it)) }
    }
}

/** 音の出口の手前に `DualMonoProcessor` を挟む。ほかは Media3 の既定のまま (速さを変える Sonic などはこの後ろ) */
@OptIn(UnstableApi::class)
class DualMonoRenderersFactory(context: Context, private val processor: DualMonoProcessor) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessors(arrayOf(processor))
            .build()
}
