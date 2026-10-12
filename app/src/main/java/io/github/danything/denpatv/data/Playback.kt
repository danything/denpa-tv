package io.github.danything.denpatv.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import kotlin.math.roundToLong

/**
 * 録画を何で観られるか (`GET api/recordings/<id>/playback`。denpa の docs/api.md「録画の観方」)。
 *
 * **時刻は `axis` の物差しで数える** (エンコード済みがあればそのファイルの時刻、無ければ元の TS の頭を 0)。尺・続きの位置・
 * チャプター (`api/recordings/<id>/chapters`)・字幕 (`captions.json`)・`stream?from=` と流れの時刻は、どれもこの物差しの上にある
 */
@Immutable
@Serializable
data class PlaybackInfo(
    /** 尺 (秒)。物差しの上の長さ */
    val duration: Double = 0.0,
    /** `encoded` か `ts` */
    val axis: String = "encoded",
    /** `stream?audio=` に渡す音声 */
    val audios: List<PlayAudio> = emptyList(),
    val offers: List<PlayOffer> = emptyList(),
)

/** 出せるもの1つ。`way` は `file` (エンコード済みのファイルそのまま) / `stream` (fMP4 で流す) / `raw` (元の TS を流す) */
@Immutable
@Serializable
data class PlayOffer(val codec: String, val way: String, val source: String? = null, val codecs: String? = null)

@Immutable
@Serializable
data class PlayAudio(val id: String, val label: String = "")

/** どれで観るか (`pickPlayback`) */
sealed interface PlayChoice {
    /** エンコード済みのファイルそのまま (`file?source=`)。Range で読み、シークはプレーヤーがする */
    data class File(val codec: String, val source: String) : PlayChoice

    /**
     * denpa に流してもらう (`stream?codec=`)。`codec` は `h264` / `av1` (fMP4) か `mpeg2` (元の TS)。
     * 流しっぱなしの1本なので、**シークは `from` を変えてリクエストし直す**
     */
    data class Stream(val codec: String) : PlayChoice {
        val mime: String get() = if (codec == "mpeg2") "video/mp2t" else "video/mp4"
    }
}

/**
 * **どれで観るか。** 端末がデコードできる中から:
 *
 * 1. エンコード済みのファイルそのまま (AV1 → H.264。軽いほうから)。denpa は読んで渡すだけ
 * 2. 元の TS をそのまま (MPEG-2 を**ハードで**デコードできる端末)。denpa はエンコードしないので、変換の枠を使わない (混んでいても断られない)
 * 3. denpa が変換して流す fMP4 (H.264 → AV1)。変換の枠を使う
 * 4. 元の TS を、ソフトのデコーダで (最後の手)
 *
 * ブラウザの「自動」は MPEG-2 を最後にする (ブラウザは canvas に自分で描くので重い)。テレビはハードで MPEG-2 をデコードできることが多い
 * (ライブの既定もそれ) ので、変換より先にする。詰め替え (エンコード済みと同じコーデックの `stream`) は選ばない — Media3 は
 * Matroska をそのまま読めるので、ファイルで観られなければ詰め替えても同じコーデックで観られない。何も無ければ null
 */
fun pickPlayback(info: PlaybackInfo, decoders: Decoders): PlayChoice? {
    fun decodes(codec: String) = when (codec) {
        "av1" -> decoders.av1
        "h264" -> decoders.h264
        "mpeg2" -> decoders.mpeg2
        else -> false
    }
    val file = listOf("av1", "h264").firstNotNullOfOrNull { codec ->
        info.offers.firstOrNull { it.way == "file" && it.codec == codec && it.source != null && decodes(codec) }
    }
    if (file != null) return PlayChoice.File(file.codec, file.source!!)
    val raw = info.offers.any { it.way == "raw" && it.codec == "mpeg2" }
    if (raw && decoders.mpeg2Hardware) return PlayChoice.Stream("mpeg2")
    listOf("h264", "av1").firstOrNull { codec -> decodes(codec) && info.offers.any { it.way == "stream" && it.codec == codec } }
        ?.let { return PlayChoice.Stream(it) }
    if (raw && decoders.mpeg2) return PlayChoice.Stream("mpeg2")
    return null
}

/** 観方まわりのエンドポイント (denpa のルートからの相対) */
object PlaybackPaths {
    fun info(id: Long): String = "api/recordings/$id/playback"

    fun chapters(id: Long): String = "api/recordings/$id/chapters"

    fun file(id: Long, source: String): String = "api/recordings/$id/file?source=${URLEncoder.encode(source, "UTF-8")}"

    /** `from` は物差しの位置 (ミリ秒)。秒の小数で渡す。`audio` は変換するときにリクエストする音声 (元の TS では渡さない) */
    fun stream(id: Long, codec: String, fromMs: Long, audio: DenpaAudio? = null): String {
        val at = fromMs.coerceAtLeast(0)
        return "api/recordings/$id/stream?codec=$codec&from=${at / 1000}.${(at % 1000).toString().padStart(3, '0')}${audioQuery(audio)}"
    }
}

/**
 * 変換して流す fMP4 でリクエストする音声の候補 (`rememberBakedAudio` に渡す)。物差しが `ts` (元の TS から変換する) なら一覧の `audios`
 * (`playback.audios` と同じ id で、デュアルモノのどちら側かも分かる)、`encoded` ならエンコード済みのファイルの何本目か (`playback.audios`)
 */
fun streamAudios(info: PlaybackInfo, broadcast: List<DenpaAudio>): List<DenpaAudio> =
    if (info.axis == "ts" && broadcast.isNotEmpty()) broadcast
    else info.audios.mapIndexed { index, audio -> DenpaAudio(id = audio.id, stream = index, label = audio.label, main = index == 0) }

@Serializable
private data class ChaptersBody(val chapters: List<ChapterJson> = emptyList())

@Serializable
private data class ChapterJson(val start: Double = 0.0, val end: Double = 0.0, val title: String = "")

/**
 * `api/recordings/<id>/chapters` の答えを読む (時刻は物差しの秒)。読めなければ空 (CM 飛ばしが効かないだけで、観るのは止めない)
 */
fun parsePlaybackChapters(json: String): List<ChapterMark> =
    runCatching { lenientJson.decodeFromString(ChaptersBody.serializer(), json).chapters }.getOrDefault(emptyList())
        .filter { it.end > it.start && it.start >= 0 }
        .map { ChapterMark((it.start * 1000).roundToLong(), (it.end * 1000).roundToLong(), it.title) }
        .sortedBy { it.startMs }

/**
 * **流れの時刻を物差しに戻す。** リクエストした位置 (`from`) と流れが実際に始まる位置はずれる (元の TS から変換するときと、元の TS をそのまま流すときは 1 秒手前の映像から)
 * ので、読み手が頭を 0 に寄せた幅 (`TsClock`) から戻す。プレーヤーの位置 0 が、物差しの何ミリ秒にあたるかを返す
 */
object StreamClock {
    /** fMP4。denpa は塊の時刻 (tfdt) を物差しの時刻のまま書くので、寄せた幅を戻すだけ (サンプルの時刻 = 元の時刻 + 幅) */
    fun mp4BaseMs(offsetUs: Long): Long = Math.floorDiv(-offsetUs, 1000L)

    /**
     * 元の TS。プレーヤーの 0 にあたる PTS (`Pts.broadcast`) から、物差しの 0 にあたる PTS (`X-Denpa-Origin`) を引く。
     * 一周 (33 ビット) の半分の中で**符号付きで**取る — 流れは物差しの 0 より手前から始まることがある (頭からリクエストすると 1 秒手前から流れる・
     * 先に来た音声の PTS が映像の頭より小さい) ので、そのときは負になる
     */
    fun tsBaseMs(offsetUs: Long, origin: Long): Long = Math.floorDiv(Pts.delta(Pts.broadcast(0, offsetUs), origin), 90L)
}

/** `DenpaApi.playback` の結果 */
sealed interface PlaybackLookup {
    data class Found(val info: PlaybackInfo) : PlaybackLookup

    /** 観られない (404)。録っている最中・ファイルが無い録画、`playback` のエンドポイントの無い古い denpa */
    data object Missing : PlaybackLookup

    /** 届かない・読めない。`message` は画面に出す */
    data class Failed(val message: String) : PlaybackLookup
}

/**
 * 録画の状態 (`GET api/recordings/<id>`。`parseRecordingState`)。使う鍵だけ。**`encoded` は読まない** (エンコード済みがあっても
 * この端末でデコードできるとは限らない。ファイルの再生へ移るかは `playback` で決める。`Chase.handOff`)
 */
data class RecordingState(
    /** `recording` `recorded` `available` `failed` */
    val state: String,
    /** 録れた長さ (ミリ秒。一覧の `durationMs` と同じ)。録画中は null */
    val durationMs: Long? = null,
) {
    val recording: Boolean get() = state == "recording"

    /** 録り終えた (`recorded`) か、ファイルが揃った (`available`) */
    val finished: Boolean get() = state == "recorded" || state == "available"
}

/**
 * 元の TS の流れ (`stream?codec=mpeg2`) の応答ヘッダ。**録画の時刻の 0 にあたる PTS** (90kHz、0〜2^33-1)。
 * 録画の時刻 = (PTS − これ) を一周の中で取って 90000 で割ったもの (denpa の docs/api.md「録画を流す」)
 */
const val ORIGIN_HEADER = "X-Denpa-Origin"
