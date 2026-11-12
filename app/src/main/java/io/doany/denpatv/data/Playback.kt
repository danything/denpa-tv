package io.doany.denpatv.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import kotlin.math.roundToLong

/**
 * 録画を何で観られるか (`GET api/recordings/<id>/playback`。denpa の docs/api.md「録画の観方」)。
 *
 * **時刻は `axis` の物差しで数える** (エンコード済みがあればそのファイルの時刻、無ければ元の TS の頭を 0)。尺・続きの位置・
 * チャプター (`api/recordings/<id>/chapters`)・字幕 (`captions.json`)・`stream?from=` とストリームの時刻は、どれもこの物差しの上にある
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
    /** エンコードしたものの物差しを元の TS に直すもの (`axis` が `encoded` のときだけ。実況の放送時刻に使う) */
    val ruler: Ruler? = null,
    /** denpa が「自動」で選んだ観方 (`playable` を付けて聞いたときだけ)。選べなければ null */
    val auto: PlayAuto? = null,
)

/** 出せるもの1つ。`way` は `file` (エンコード済みのファイルそのまま) / `stream` (fMP4 で流す) / `raw` (元の TS を流す)。使う鍵だけ (`codecs` は読まない。デコードできるかは端末のデコーダで見る) */
@Immutable
@Serializable
data class PlayOffer(val codec: String, val way: String, val source: String? = null)

/**
 * denpa が選んだ観方 (`playback?playable=` の `auto`)。`way` は `file` (`source` のファイルをそのまま) / `stream` (`codec` の fMP4) /
 * `raw` (元の TS を `stream?codec=mpeg2` で)
 */
@Immutable
@Serializable
data class PlayAuto(val way: String, val codec: String, val source: String? = null) {
    /** どれで観るか。知らない `way`・`source` の無いファイルは null (版のずれ) */
    fun choice(): PlayChoice? = when (way) {
        "file" -> source?.let { PlayChoice.File(codec, it) }
        "stream" -> PlayChoice.Stream(codec)
        "raw" -> PlayChoice.Stream("mpeg2")
        else -> null
    }
}

@Immutable
@Serializable
data class PlayAudio(val id: String, val label: String = "")

/** どれで観るか (`PlayAuto.choice`) */
sealed interface PlayChoice {
    /** 映像のコーデック (`av1` / `hevc` / `h264` / `mpeg2`) */
    val codec: String

    /** 端末の側で再生できなかったときに `playable` から外すもの */
    val quality: LiveQuality? get() = LiveQuality.ofPlayback(codec)

    /** エンコード済みのファイルそのまま (`file?source=`)。Range で読み、シークはプレーヤーがする */
    data class File(override val codec: String, val source: String) : PlayChoice

    /**
     * denpa に流してもらう (`stream?codec=`)。`codec` は `h264` / `hevc` / `av1` (fMP4) か `mpeg2` (元の TS)。
     * 流しっぱなしの1本なので、**シークは `from` を変えてリクエストし直す**
     */
    data class Stream(override val codec: String) : PlayChoice {
        val mime: String get() = if (codec == "mpeg2") "video/mp2t" else "video/mp4"
    }
}

/**
 * 観た位置を預けるときの `source` (`POST api/recordings/<id>/resume`)。ファイルはそのファイル、流してもらうときは元にしたファイル
 * (`axis`。変換・詰め替え・元の TS をそのまま、どれも時刻は物差しの上にある)
 */
fun PlayChoice.resumeSource(info: PlaybackInfo): String = when (this) {
    is PlayChoice.File -> source
    is PlayChoice.Stream -> info.axis
}

/** 観方まわりのエンドポイント (denpa のルートからの相対) */
object PlaybackPaths {
    /** `playable` は端末が再生できる形式。denpa が選んだ観方を `auto` に入れる */
    fun info(id: Long, playable: Playable): String = "api/recordings/$id/playback?playable=${playable.query}"

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
 * **ストリームの時刻を物差しに戻す。** リクエストした位置 (`from`) とストリームが実際に始まる位置はずれる (元の TS から変換するときと、元の TS をそのまま流すときは 1 秒手前の映像から)
 * ので、Extractor が頭を 0 に寄せた幅 (`TsClock`) から戻す。プレーヤーの位置 0 が、物差しの何ミリ秒にあたるかを返す
 */
object StreamClock {
    /** fMP4。denpa は塊の時刻 (tfdt) を物差しの時刻のまま書くので、寄せた幅を戻すだけ (サンプルの時刻 = 元の時刻 + 幅) */
    fun mp4BaseMs(offsetUs: Long): Long = Math.floorDiv(-offsetUs, 1000L)

    /**
     * 元の TS。プレーヤーの 0 にあたる PTS (`Pts.broadcast`) から、物差しの 0 にあたる PTS (`X-Denpa-Origin`) を引く。
     * 一周 (33 ビット) の半分の中で**符号付きで**取る — ストリームは物差しの 0 より手前から始まることがある (頭からリクエストすると 1 秒手前から流れる・
     * 先に来た音声の PTS が映像の頭より小さい) ので、そのときは負になる
     */
    fun tsBaseMs(offsetUs: Long, origin: Long): Long = Math.floorDiv(Pts.delta(Pts.broadcast(0, offsetUs), origin), 90L)
}

/** `DenpaApi.playback` の結果 */
sealed interface PlaybackLookup {
    data class Found(val info: PlaybackInfo) : PlaybackLookup

    /** 観られない (404)。録っている最中・ファイルが無い録画 */
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
    /** 実況 (チャンネルと元の TS の頭の放送時刻)。実況の無い局・世帯の設定がオフなら null */
    val jikkyo: RecordingJikkyo? = null,
) {
    val recording: Boolean get() = state == "recording"

    /** 録り終えた (`recorded`) か、ファイルが揃った (`available`) */
    val finished: Boolean get() = state == "recorded" || state == "available"
}

/**
 * 元の TS のストリーム (`stream?codec=mpeg2`) の応答ヘッダ。**録画の時刻の 0 にあたる PTS** (90kHz、0〜2^33-1)。
 * 録画の時刻 = (PTS − これ) を一周の中で取って 90000 で割ったもの (denpa の docs/api.md「録画を流す」)
 */
const val ORIGIN_HEADER = "X-Denpa-Origin"
