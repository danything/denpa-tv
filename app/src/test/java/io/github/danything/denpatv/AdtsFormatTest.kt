package io.github.danything.denpatv

import androidx.annotation.OptIn
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader
import io.github.danything.denpatv.ui.AdtsFormatReader
import io.github.danything.denpatv.ui.RawTsPayloadReaderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * 番組の境目で、同じ PID のまま AAC の形式 (チャンネルの並び・サンプリング周波数) が替わる TS を、
 * `RawTsPayloadReaderFactory` が作る AAC のパーサに 188 バイトの TS パケットの中身ずつ流す (denpa-tv#139)
 */
@OptIn(UnstableApi::class)
class AdtsFormatTest {
    /**
     * `ParsableByteArray` はテストの中かを `Build.FINGERPRINT` で調べるが、単体テストの android.jar では null で読めない。
     * テストの中と同じ扱い (読む範囲を確かめる) を先に指定しておく
     */
    @Before
    fun enforceLimit() = ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)

    private class Track : TrackOutput {
        val formats = mutableListOf<Format>()
        val samples = mutableListOf<Long>()

        override fun format(format: Format) {
            formats += format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            error("ParsableByteArray で渡すはず")

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            samples += timeUs
        }
    }

    private class Output : ExtractorOutput {
        val tracks = mutableListOf<Track>()

        override fun track(id: Int, type: Int): TrackOutput = Track().also { tracks += it }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    /** AAC-LC の ADTS のフレーム 1 つ (CRC なし)。中身は同期語に見えないバイト */
    private fun adts(rate: Int, channels: Int, payload: Int = 40): ByteArray {
        val length = 7 + payload
        val header = byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            ((1 shl 6) or (rate shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (length shr 11)).toByte(),
            ((length shr 3) and 0xFF).toByte(),
            (((length and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
        return header + ByteArray(payload) { 0x11 }
    }

    /** 音声の PES 1 つ (PTS だけ付ける) */
    private fun pes(pts: Long, aligned: Boolean, vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0)) { all, frame -> all + frame }
        val header = byteArrayOf(
            0, 0, 1, 0xC0.toByte(),
            ((body.size + 8) shr 8).toByte(), (body.size + 8).toByte(),
            (0x80 or if (aligned) 0x04 else 0).toByte(),
            0x80.toByte(),
            5,
            (0x21 or ((pts shr 29) and 0x0E).toInt()).toByte(),
            (pts shr 22).toByte(),
            (((pts shr 14) and 0xFE) or 1).toByte(),
            (pts shr 7).toByte(),
            (((pts shl 1) and 0xFE) or 1).toByte(),
        )
        return header + body
    }

    /** PES を TS パケットの中身 (184 バイト) ずつに分けて流し、出たトラックを返す */
    private fun run(vararg packets: ByteArray): Track {
        val esInfo = TsPayloadReader.EsInfo(
            TsExtractor.TS_STREAM_TYPE_AAC_ADTS,
            null,
            TsPayloadReader.EsInfo.AUDIO_TYPE_UNDEFINED,
            emptyList(),
            ByteArray(0),
        )
        val reader = RawTsPayloadReaderFactory().createPayloadReader(TsExtractor.TS_STREAM_TYPE_AAC_ADTS, esInfo)
        assertNotNull(reader)
        val output = Output()
        reader!!.init(TimestampAdjuster(0), output, TsPayloadReader.TrackIdGenerator(1, 0x110, 0x2000))
        for (packet in packets) {
            packet.toList().chunked(184).forEachIndexed { i, chunk ->
                val flags = if (i == 0) TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR else 0
                reader.consume(ParsableByteArray(chunk.toByteArray()), flags)
            }
        }
        return output.tracks.single()
    }

    private val mono = adts(rate = 3, channels = 1)
    private val stereo = adts(rate = 3, channels = 2)

    @Test
    fun モノラルからステレオに替わると_同じトラックへ_Format_を出し直す() {
        val track = run(
            pes(0, aligned = true, mono, mono, mono),
            pes(5760, aligned = true, stereo, stereo, stereo),
            pes(11520, aligned = true, stereo, stereo, stereo),
        )
        assertEquals(listOf(1, 2), track.formats.map { it.channelCount })
        assertEquals(listOf(48_000, 48_000), track.formats.map { it.sampleRate })
        // 同じトラックの形式が替わったものとして扱わせる
        assertEquals(1, track.formats.map { it.id }.distinct().size)
        // 替わり目のフレームも落とさない
        assertEquals(9, track.samples.size)
    }

    @Test
    fun ステレオからモノラルに戻っても出し直す() {
        val track = run(
            pes(0, aligned = true, stereo, stereo),
            pes(3840, aligned = true, mono, mono),
            pes(7680, aligned = true, stereo, stereo),
        )
        assertEquals(listOf(2, 1, 2), track.formats.map { it.channelCount })
    }

    @Test
    fun サンプリング周波数が替わっても出し直す() {
        val track = run(
            pes(0, aligned = true, stereo, stereo),
            pes(3840, aligned = true, adts(rate = 4, channels = 2), adts(rate = 4, channels = 2)),
        )
        assertEquals(listOf(48_000, 44_100), track.formats.map { it.sampleRate })
        assertEquals(4, track.samples.size)
    }

    @Test
    fun 形式が同じなら出し直さない() {
        val track = run(
            pes(0, aligned = true, stereo, stereo),
            pes(3840, aligned = true, stereo, stereo),
        )
        assertEquals(1, track.formats.size)
        assertEquals(4, track.samples.size)
    }

    @Test
    fun data_alignment_indicator_が立っていない_PES_では見ない() {
        val track = run(
            pes(0, aligned = true, mono, mono),
            pes(3840, aligned = false, stereo, stereo),
        )
        assertEquals(listOf(1), track.formats.map { it.channelCount })
    }

    @Test
    fun ADTS_ヘッダと_AudioSpecificConfig_から同じ値を取り出す() {
        // AAC-LC・48kHz・ステレオの AudioSpecificConfig は 0x11 0x90
        assertEquals(AdtsFormatReader.ascKey(byteArrayOf(0x11, 0x90.toByte())), AdtsFormatReader.adtsKey(ParsableByteArray(stereo)))
        assertEquals(null, AdtsFormatReader.adtsKey(ParsableByteArray(byteArrayOf(0x47, 0x40, 0x11, 0x10))))
    }
}
