package io.github.danything.denpatv.data

import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * この端末が解ける映像の形。**AV1 は解ける端末にだけ選ぶ** (ソフトの AV1 デコーダは同梱しない。
 * docs/libraries.md)。
 */
data class Decoders(val av1: Boolean, val h264: Boolean, val mpeg2: Boolean) {
    companion object {
        fun detect(): Decoders {
            val types = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filterNot { it.isEncoder }
                .flatMap { it.supportedTypes.asList() }
                .map { it.lowercase() }
                .toSet()
            return Decoders(
                // MIMETYPE_VIDEO_AV1 は API 29 から。文字列は前から同じなので、そのまま書く
                av1 = "video/av01" in types,
                h264 = MediaFormat.MIMETYPE_VIDEO_AVC in types,
                mpeg2 = MediaFormat.MIMETYPE_VIDEO_MPEG2 in types,
            )
        }
    }
}

/** ライブで頼む形 (`?codec=`)。AV1 を解ければ AV1、それ以外は H.264 */
fun liveCodec(decoders: Decoders): String = if (decoders.av1) "av1" else "h264"

/**
 * 録画のどのファイルを開くか。解ける中で軽いほうから — AV1、H.264、生の TS (MPEG-2) の順。
 * 解けるものが無ければ null
 */
fun pickFile(files: List<RecordingFile>, decoders: Decoders): RecordingFile? {
    fun can(codec: String) = when (codec) {
        "av1" -> decoders.av1
        "h264" -> decoders.h264
        "mpeg2" -> decoders.mpeg2
        else -> false
    }
    return listOf("av1", "h264", "mpeg2").firstNotNullOfOrNull { codec ->
        files.firstOrNull { it.codec == codec && can(codec) }
    }
}
