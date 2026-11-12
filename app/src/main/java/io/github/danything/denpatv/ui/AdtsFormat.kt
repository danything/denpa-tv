package io.github.danything.denpatv.ui

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.AdtsReader
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * **元の TS のパーサの作り。`DefaultTsPayloadReaderFactory` と同じで、AAC (ADTS) だけ `AdtsFormatReader` で包む。**
 *
 * 放送は番組の境目で、同じ PID のまま AAC のチャンネルの並び (モノラル・ステレオ・5.1ch) を替える。
 * Media3 の `AdtsReader` は最初の ADTS ヘッダから `Format` を 1 度だけ作るので、替わったあとのフレームをデコーダが読めず、
 * 音が出なくなる (denpa-tv#139)
 */
@OptIn(UnstableApi::class)
class RawTsPayloadReaderFactory(
    private val inner: DefaultTsPayloadReaderFactory = DefaultTsPayloadReaderFactory(0, emptyList()),
) : TsPayloadReader.Factory {
    override fun createInitialPayloadReaders() = inner.createInitialPayloadReaders()

    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? =
        if (streamType == TsExtractor.TS_STREAM_TYPE_AAC_ADTS) {
            // DefaultTsPayloadReaderFactory が ADTS に作るものと同じ AdtsReader
            PesReader(AdtsFormatReader { AdtsReader(false, esInfo.language, esInfo.roleFlags, MimeTypes.VIDEO_MP2T) })
        } else {
            inner.createPayloadReader(streamType, esInfo)
        }
}

/**
 * **PES の先頭の ADTS ヘッダが、デコーダに伝えた形式 (サンプリング周波数とチャンネルの並び) と違えば、`AdtsReader` を作り直す。**
 * 作り直した `AdtsReader` は同じトラックへ新しい `Format` を出すので、デコーダが新しい形式で作り直される。
 *
 * - ヘッダを見るのは、data_alignment_indicator が立っている (PES の先頭が ADTS のフレームの先頭にそろっている) PES だけ。
 *   立っていない PES は `AdtsReader` にそのまま渡す
 * - 作り直した `AdtsReader` が出す `Format` には、最初の `Format` と同じ id を付ける (同じトラックの形式が替わったものとして扱わせる)
 */
@OptIn(UnstableApi::class)
class AdtsFormatReader(private val make: () -> AdtsReader) : ElementaryStreamReader {
    private var reader = make()
    /** 最初の `createTracks` で `AdtsReader` が作ったトラック。作り直したものにも同じものを渡す */
    private val tracks = mutableListOf<TrackOutput>()
    /** デコーダに伝えた形式 (`adtsKey`)。まだ伝えていなければ null */
    private var announced: Int? = null
    /** 最初に伝えた `Format` の id */
    private var formatId: String? = null
    /** いまの PES の先頭をまだ見ていない (data_alignment_indicator が立っていた) */
    private var checkHead = false
    private var pesTimeUs = 0L
    private var pesFlags = 0

    override fun seek() {
        reader.seek()
        checkHead = false
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        reader.createTracks(
            object : ExtractorOutput by extractorOutput {
                override fun track(id: Int, type: Int): TrackOutput =
                    Announcing(extractorOutput.track(id, type)).also { tracks += it }
            },
            idGenerator,
        )
    }

    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        this.pesTimeUs = pesTimeUs
        pesFlags = flags
        checkHead = flags and TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR != 0
        reader.packetStarted(pesTimeUs, flags)
    }

    override fun consume(data: ParsableByteArray) {
        if (checkHead) {
            checkHead = false
            val head = adtsKey(data)
            val sent = announced
            if (head != null && sent != null && head != sent) rebuild()
        }
        reader.consume(data)
    }

    override fun packetFinished() = reader.packetFinished()

    override fun endOfInputReached() = reader.endOfInputReached()

    /** 新しい `AdtsReader` に、前と同じトラックを渡す (トラックの id は使わないので、`TrackIdGenerator` は何でもよい) */
    private fun rebuild() {
        val next = make()
        val replay = tracks.iterator()
        next.createTracks(
            object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = replay.next()
                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) = Unit
            },
            TsPayloadReader.TrackIdGenerator(0, 1),
        )
        next.packetStarted(pesTimeUs, pesFlags)
        reader = next
    }

    /** 出す `Format` から、伝えた形式を控える。作り直したあとの `Format` には最初の id を付ける */
    private inner class Announcing(private val out: TrackOutput) : TrackOutput by out {
        override fun format(format: Format) {
            if (format.sampleMimeType != MimeTypes.AUDIO_AAC) return out.format(format)
            announced = format.initializationData.firstOrNull()?.let(::ascKey)
            val id = formatId ?: format.id.also { formatId = it }
            out.format(if (format.id == id) format else format.buildUpon().setId(id).build())
        }
    }

    companion object {
        /**
         * ADTS ヘッダ (`data` の今の位置から) のサンプリング周波数の番号とチャンネルの並びを、1 つの値にまとめる。
         * ADTS の同期語で始まっていない・4 バイトに満たなければ null。`data` の位置は動かさない
         */
        fun adtsKey(data: ParsableByteArray): Int? {
            if (data.bytesLeft() < 4) return null
            val bytes = data.data
            val at = data.position
            val b0 = bytes[at].toInt() and 0xFF
            val b1 = bytes[at + 1].toInt() and 0xFF
            if (b0 != 0xFF || !AdtsReader.isAdtsSyncWord((b0 shl 8) or b1)) return null
            val b2 = bytes[at + 2].toInt() and 0xFF
            val b3 = bytes[at + 3].toInt() and 0xFF
            val rate = (b2 shr 2) and 0x0F
            val channels = ((b2 and 0x01) shl 2) or (b3 shr 6)
            return (rate shl 4) or channels
        }

        /** AudioSpecificConfig (`AdtsReader` が `Format` に付けるもの) から、`adtsKey` と同じ値を取り出す */
        fun ascKey(asc: ByteArray): Int? {
            if (asc.size < 2) return null
            val a0 = asc[0].toInt() and 0xFF
            val a1 = asc[1].toInt() and 0xFF
            val rate = ((a0 and 0x07) shl 1) or (a1 shr 7)
            val channels = (a1 shr 3) and 0x0F
            return (rate shl 4) or channels
        }
    }
}
