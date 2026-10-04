package io.github.danything.denpatv.data

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/**
 * この端末が解ける映像の形。`*Hardware` はハードウェアのデコーダがあるか。
 *
 * **ライブはハードで解けるものから選ぶ。** ソフトの AV1 / MPEG-2 デコーダを持つ端末もあるが、
 * 1080i を流しっぱなしで解くには非力なことが多い。録画は解けさえすれば開く (`pickFile`)
 */
data class Decoders(
    val av1: Boolean,
    val h264: Boolean,
    val mpeg2: Boolean,
    val av1Hardware: Boolean = av1,
    val mpeg2Hardware: Boolean = mpeg2,
) {
    companion object {
        fun detect(): Decoders {
            val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }
            fun any(mime: String) = decoders.any { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            fun hardware(mime: String) = decoders.any { info ->
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } && isHardware(info)
            }
            // MIMETYPE_VIDEO_AV1 は API 29 から。文字列は前から同じなので、そのまま書く
            val av1 = "video/av01"
            return Decoders(
                av1 = any(av1),
                h264 = any(MediaFormat.MIMETYPE_VIDEO_AVC),
                mpeg2 = any(MediaFormat.MIMETYPE_VIDEO_MPEG2),
                av1Hardware = hardware(av1),
                mpeg2Hardware = hardware(MediaFormat.MIMETYPE_VIDEO_MPEG2),
            )
        }

        /**
         * ハードのデコーダか。API 29 からは端末が答える。それより前は名前で見る
         * (AOSP のソフトのものは `OMX.google.` か `c2.android.` で始まる)
         */
        private fun isHardware(info: MediaCodecInfo): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                val name = info.name.lowercase()
                !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
            }
    }
}

/**
 * ライブの画質。`codec` は denpa の `?codec=` にそのまま渡す値。
 *
 * - **低遅延** (`raw`) — 焼かずに1局に絞っただけの TS (MPEG-2)。denpa が焼くのを待たないぶん
 *   いちばん早く、denpa の CPU も使わない。ARIB の字幕は出ない
 * - **H.264** — どの端末でも解ける
 * - **AV1** — 同じ画質で軽い。ハードで解ける端末だけ
 */
enum class LiveQuality(val codec: String, val label: String, val mime: String) {
    Raw("raw", "低遅延 (MPEG-2)", "video/mp2t"),
    H264("h264", "H.264", "video/mp4"),
    Av1("av1", "AV1", "video/mp4"),
    ;

    companion object {
        fun available(decoders: Decoders): List<LiveQuality> = buildList {
            if (decoders.mpeg2Hardware) add(Raw)
            add(H264)
            if (decoders.av1Hardware) add(Av1)
        }

        /** 覚えている画質が、この端末で選べればそれ。無ければ選べる中の先頭 (低遅延 → H.264) */
        fun choose(saved: String?, decoders: Decoders): LiveQuality {
            val choices = available(decoders)
            return choices.firstOrNull { it.name == saved } ?: choices.first()
        }
    }
}

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
