package io.github.danything.denpatv.data

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/**
 * この端末がデコードできる映像の形式。`*Hardware` はハードウェアのデコーダがあるか。
 *
 * **ライブはハードでデコードできるものから選ぶ。** ソフトの AV1 / MPEG-2 デコーダを持つ端末もあるが、
 * 1080i を流しっぱなしでデコードするには非力なことが多い。録画はデコードできさえすれば開く (`pickPlayback`)
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
 * **「自動」の順** (どの形式で観るか)。ライブ・追っかけ・録画で、この1か所から出す。決まりは denpa と同じ
 * (denpa の `live-choice.ts` の `autoOrder`、docs/stream.md の「どの形式で流すか」):
 *
 * | 場面                                                  | 家の中                       | 外                  |
 * | ----------------------------------------------------- | ---------------------------- | ------------------- |
 * | その場でエンコード (ライブ・追っかけ・録画しか無い録画) | MPEG-2 → H.264 → AV1 → H.265 | AV1 → H.265 → H.264 |
 * | エンコード済み (録画のファイル)                        | AV1 → H.265 → H.264          | AV1 → H.265 → H.264 |
 *
 * - 家の中は開くまでの速さを採る (MPEG-2 はエンコードせずに済む。次は立ち上がりの速い H.264)。外は回線を採り、同じ画質で小さい順
 * - **画面ごとの例外は作らない。** その画面で出せない形式は、呼ぶ側が並びから外すだけ (テレビは H.265 を流さないので、いつも外れる)
 * - 家の中かは denpa が開いたホスト名で決める (`GET api/playback/options` の `indoor`。`Repository.playbackOptions()` の `PlaybackOptions.indoor`)
 */
object AutoOrder {
    /** その場でエンコードするもの (ライブ・追っかけ・エンコード済みのファイルが無い録画)。`raw` は MPEG-2 (放送のまま) */
    fun onTheFly(indoor: Boolean): List<String> =
        if (indoor) listOf("raw", "h264", "av1", "hevc") else listOf("av1", "hevc", "h264")

    /** エンコード済みのファイル。家の中でも外でも同じ */
    val encoded: List<String> = listOf("av1", "hevc", "h264")
}

/**
 * ライブ・追っかけの画質 (コーデック)。`codec` は denpa の `?codec=` にそのまま渡す値
 *
 * - **AV1** — 同じ画質で軽い。ハードでデコードできる端末だけ
 * - **H.264** — どの端末でもデコードできる
 * - **MPEG-2** (`raw`) — エンコードせずに1局に絞っただけの TS。denpa がエンコードを待たないぶんいちばん遅れが少なく、
 *   denpa の CPU も使わない。字幕は denpa がデコードした文字の配置を描いて重ねる (`ui/RawCaptions.kt`)。ハードでデコードできる端末だけ
 *
 * メニューには「自動」も並ぶ (`LiveSelection`)。H.265 はテレビでは流さない
 */
enum class LiveQuality(val codec: String, val label: String, val mime: String) {
    Av1("av1", "AV1", "video/mp4"),
    H264("h264", "H.264", "video/mp4"),
    Raw("raw", "MPEG-2", "video/mp2t"),
    ;

    companion object {
        /** この端末で流せるもの */
        fun available(decoders: Decoders): List<LiveQuality> = buildList {
            if (decoders.av1Hardware) add(Av1)
            add(H264)
            if (decoders.mpeg2Hardware) add(Raw)
        }

        /**
         * 画質のメニューに並べるもの (「自動」の後ろ)。並びはブラウザの denpa のコーデックのメニュー (自動 → AV1 → H.265 → H.264 → MPEG-2) から、
         * この端末で流せないもの (H.265 はいつも) を外したもの。`hidden` (世帯の設定の「コーデックの選択肢に H.264 と H.265 を出さない」) なら H.264 も外す
         */
        fun menu(decoders: Decoders, hidden: Boolean): List<LiveQuality> =
            available(decoders).filterNot { hidden && it == H264 }

        /**
         * **「自動」で決まる画質。** 自動の順 (`AutoOrder.onTheFly`) で、この端末で流せるものの先頭。どれも無ければ H.264 (どの端末でも流せる)。
         * メニューから外したもの (`hidden`) も使う (AV1 を流せない端末には H.264 を流す)
         */
        fun auto(decoders: Decoders, indoor: Boolean): LiveQuality {
            val choices = available(decoders)
            return AutoOrder.onTheFly(indoor).firstNotNullOfOrNull { codec -> choices.firstOrNull { it.codec == codec } } ?: H264
        }

        /**
         * 覚えている選択 (`Settings.liveQuality`。`name`) を読む。**この端末で選べない・知らない値・メニューから外した H.264 は自動 (null)**
         * (ブラウザの `readChoice` と同じ)
         */
        fun read(saved: String?, decoders: Decoders, hidden: Boolean): LiveQuality? =
            menu(decoders, hidden).firstOrNull { it.name == saved }
    }
}

/**
 * メニューで選んでいるもの (`choice`。null は「自動」) と、それで決まる画質 (`format`)。**選んだものは家の中でも外でもそのまま**、
 * 自動なら開いた場所 (家の中か) と端末で決める (`LiveQuality.auto`)
 */
data class LiveSelection(
    val choice: LiveQuality?,
    /** 自動で決まる画質 */
    val auto: LiveQuality,
    /** メニューの「自動」の後ろに並べるもの (`LiveQuality.menu`) */
    val menu: List<LiveQuality> = emptyList(),
) {
    /** 流す画質 */
    val format: LiveQuality get() = choice ?: auto

    /** メニューの「自動」の名前。自動のときは、いま決まっている画質を添える (ブラウザの「自動 (MPEG-2)」と同じ) */
    val autoLabel: String get() = if (choice == null) "$AUTO (${auto.label})" else AUTO

    companion object {
        const val AUTO = "自動"

        fun of(choice: LiveQuality?, decoders: Decoders, indoor: Boolean, hideH264: Boolean) =
            LiveSelection(choice, LiveQuality.auto(decoders, indoor), LiveQuality.menu(decoders, hideH264))
    }
}
