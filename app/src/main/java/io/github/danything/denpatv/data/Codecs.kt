package io.github.danything.denpatv.data

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/**
 * この端末がデコードできる映像の形式。`*Hardware` はハードウェアのデコーダがあるか。
 *
 * **denpa にはハードでデコードできるものを伝えて選ばせる** (`Playable`)。ソフトの AV1 / MPEG-2 デコーダを持つ端末もあるが、
 * 1080i を流しっぱなしでデコードするには非力なことが多い。録画で denpa が選べなかったときだけ、ソフトのものも足して聞き直す (`Playable.withSoftware`)
 */
data class Decoders(
    val av1: Boolean,
    val h264: Boolean,
    val mpeg2: Boolean,
    val av1Hardware: Boolean = av1,
    val mpeg2Hardware: Boolean = mpeg2,
    val hevcHardware: Boolean = false,
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
                hevcHardware = hardware(MediaFormat.MIMETYPE_VIDEO_HEVC),
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
 * ライブ・追っかけの画質 (コーデック)。`codec` は denpa の `?codec=`・`playable=` と応答ヘッダ `X-Denpa-Codec` の値
 *
 * - **AV1**: 同じ画質で軽い。ハードでデコードできる端末だけ
 * - **H.265**: denpa が GPU でエンコードできるときだけ、denpa が「自動」で選ぶ。ハードでデコードできる端末だけ。メニューには並べない
 * - **H.264**: どの端末でもデコードできる
 * - **MPEG-2** (`raw`): エンコードせずに1局に絞っただけの TS。denpa がエンコードを待たないぶんいちばん遅れが少なく、
 *   denpa の CPU も使わない。字幕は denpa がデコードした文字の配置を描いて重ねる (`ui/RawCaptions.kt`)。ハードでデコードできる端末だけ
 *
 * メニューには「自動」も並ぶ (`LiveSelection`。選択が null)。自動は denpa に選ばせ (`codec=auto`)、選ばれたものを応答ヘッダで受け取る
 */
enum class LiveQuality(val codec: String, val label: String, val mime: String) {
    Av1("av1", "AV1", "video/mp4"),
    Hevc("hevc", "H.265", "video/mp4"),
    H264("h264", "H.264", "video/mp4"),
    Raw("raw", "MPEG-2", "video/mp2t"),
    ;

    companion object {
        /** メニューで選べるもの。H.265 は並べない (選ぶのは denpa の「自動」だけ) */
        fun available(decoders: Decoders): List<LiveQuality> = buildList {
            if (decoders.av1Hardware) add(Av1)
            add(H264)
            if (decoders.mpeg2Hardware) add(Raw)
        }

        /**
         * 画質のメニューに並べるもの (「自動」の後ろ)。並びはブラウザの denpa のコーデックのメニュー (自動 → AV1 → H.265 → H.264 → MPEG-2) から、
         * この端末で選べないもの (H.265 はいつも) を外したもの。`hidden` (世帯の設定の「コーデックの選択肢に H.264 と H.265 を出さない」) なら H.264 も外す
         */
        fun menu(decoders: Decoders, hidden: Boolean): List<LiveQuality> =
            available(decoders).filterNot { hidden && it == H264 }

        /**
         * 覚えている選択 (`Settings.liveQuality`。`name`) を読む。**この端末で選べない・知らない値・メニューから外した H.264 は自動 (null)**
         * (ブラウザの `readChoice` と同じ)
         */
        fun read(saved: String?, decoders: Decoders, hidden: Boolean): LiveQuality? =
            menu(decoders, hidden).firstOrNull { it.name == saved }

        /** 応答ヘッダ `X-Denpa-Codec` の値 (`h264` / `hevc` / `av1` / `raw`)。音声だけ (`audio`)・知らない値・無いときは null */
        fun ofHeader(value: String?): LiveQuality? {
            val codec = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.codec == codec }
        }

        /** 録画の観方 (`playback` の `auto`・`offers`) の `codec`。元の TS は `mpeg2` と書く */
        fun ofPlayback(codec: String): LiveQuality? = if (codec == "mpeg2") Raw else ofHeader(codec)
    }
}

/** 選んだ形式を返す応答ヘッダ (denpa の docs/api.md「形式をサーバに選ばせる `codec=auto`」) */
const val CODEC_HEADER = "X-Denpa-Codec"

/**
 * **denpa に伝える、この端末が再生できる形式** (`playable=`)。並べるのはハードでデコードできる AV1・H.265・MPEG-2 と、H.264。
 * どれを流すかは denpa が「自動」の順で選ぶ (家の中か・GPU があるかも denpa が見る)。
 *
 * 端末の側で再生できなかったもの (デコーダを作れない・最初のフレームが来ない) は、その画面を開いている間だけ外す (`without`。覚えない)。
 * 外したものは `dropped` に残し、ソフトのものを足すとき (`withSoftware`) も足し直さない
 */
data class Playable(val formats: List<LiveQuality>, val dropped: Set<LiveQuality> = emptySet()) {
    /** 再生できるものが1つも無い (「再生できる形式がありません」) */
    val empty: Boolean get() = formats.isEmpty()

    /** `playable=` の値。並びは denpa が見ないので、ここで並べた順のまま */
    val query: String get() = formats.joinToString(",") { it.codec }

    /** 端末の側で再生できなかった形式を外す */
    fun without(quality: LiveQuality): Playable = Playable(formats - quality, dropped + quality)

    /**
     * 録画で denpa が選べなかった (`auto` が null) ときに足す、ソフトでデコードできるもの (AV1・MPEG-2)。
     * 流しっぱなしで回すには非力なことがあるが、観られないよりよい。外したものは足さない
     */
    fun withSoftware(decoders: Decoders): Playable {
        val soft = buildList {
            if (decoders.av1) add(LiveQuality.Av1)
            if (decoders.mpeg2) add(LiveQuality.Raw)
        }
        return copy(formats = formats + soft.filter { it !in formats && it !in dropped })
    }

    companion object {
        fun of(decoders: Decoders): Playable = Playable(
            buildList {
                if (decoders.mpeg2Hardware) add(LiveQuality.Raw)
                if (decoders.av1Hardware) add(LiveQuality.Av1)
                if (decoders.hevcHardware) add(LiveQuality.Hevc)
                add(LiveQuality.H264)
            },
        )
    }
}

/**
 * ライブ・追っかけのリクエストに足す形式 (`codec=`)。選んでいれば (`choice`) それ、自動 (null) なら `codec=auto&playable=…` で denpa に選ばせる
 */
fun codecQuery(choice: LiveQuality?, playable: Playable): String =
    choice?.let { "codec=${it.codec}" } ?: "codec=auto&playable=${playable.query}"

/**
 * 選んでいるものの名前。自動 (`choice` が null) は、いま流れている形式 (`playing`。応答ヘッダで受け取ったもの) を添える
 * (「自動 (MPEG-2)」。ブラウザと同じ)。まだ分からなければ「自動」だけ
 */
fun choiceLabel(choice: LiveQuality?, playing: LiveQuality?): String =
    choice?.label ?: playing?.let { "${LiveSelection.AUTO} (${it.label})" } ?: LiveSelection.AUTO

/** 名前に文を続ける。名前が半角で終わるときだけ空白を挟む (「AV1 にしました」「自動にしました」) */
fun labelThen(label: String, rest: String): String = if (label.isNotEmpty() && label.last().code < 128) "$label $rest" else "$label$rest"

/**
 * メニューで選んでいるもの (`choice`。null は「自動」) と、メニューに並べるもの。**選んだものは家の中でも外でもそのまま**、
 * 自動なら denpa が選ぶ (`codecQuery`)
 */
data class LiveSelection(
    val choice: LiveQuality?,
    /** メニューの「自動」の後ろに並べるもの (`LiveQuality.menu`) */
    val menu: List<LiveQuality> = emptyList(),
) {
    /** メニューの「自動」の名前。自動のときは、いま流れている形式を添える (ブラウザの「自動 (MPEG-2)」と同じ) */
    fun autoLabel(playing: LiveQuality?): String = if (choice == null) choiceLabel(null, playing) else AUTO

    companion object {
        const val AUTO = "自動"

        fun of(choice: LiveQuality?, decoders: Decoders, hideH264: Boolean) = LiveSelection(choice, LiveQuality.menu(decoders, hideH264))
    }
}
