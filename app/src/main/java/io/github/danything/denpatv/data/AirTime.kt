package io.github.danything.denpatv.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToLong

/**
 * **いま映しているコマの放送時刻 W** (unix ms)。実況のコメントはこれに合わせて流す (`JikkyoFeed.take`)。
 * まだ分からなければ null (流さずに待つ)。ライブ・追っかけ・録画で出し方が違うので、画面がこの形で渡す
 */
fun interface AirClock {
    fun now(): Long?
}

/**
 * ライブの字幕のストリームが送る時計 (`clock` の通知。denpa の docs/api.md「ライブの時計」)。**映像の時刻 `at` (秒) のところを、サーバが `unixMs` に受け取った。**
 * `at` は、元の TS なら放送の PTS を 90000 で割ったもの (26.5 時間で一周)、変換したライブなら fMP4 の時刻
 */
data class ClockNotice(val at: Double, val unixMs: Long) {
    /** `at` を 90kHz の時刻 (33 ビットで畳む) にしたもの */
    val atPts: Long get() = Math.floorMod((at * 90_000).roundToLong(), Pts.WRAP)
}

object AirTime {
    /**
     * ライブ。いま映しているコマの時刻 (`Pts.broadcast`。元の TS は放送の PTS、変換したライブは fMP4 の時刻。どちらも 90kHz) と、時計の通知から。
     * W = `unixMs` + (いまのコマの時刻 − `at`) / 90 (33 ビットの一周の半分の中で符号付きで取る)
     */
    fun live(clock: ClockNotice, pts: Long): Long = clock.unixMs + Math.floorDiv(Pts.delta(pts, clock.atPts), 90L)

    /** 追っかけ。元の TS の頭の放送時刻 (`head`) + 元の TS の頭からの位置 (リクエストした `from` + プレーヤーの位置) */
    fun chase(head: Long, positionMs: Long): Long = head + positionMs

    /** 録画。元の TS の頭の放送時刻 (`head`) + 物差しの位置を元の TS の時刻に直したもの (`Ruler.toTs`。物差しが元の TS なら `ruler` は null) */
    fun recording(head: Long, positionMs: Long, ruler: Ruler?): Long =
        head + ((ruler?.toTs(positionMs / 1000.0) ?: (positionMs / 1000.0)) * 1000).roundToLong()
}

/**
 * **エンコードしたものの物差しを、元の TS の物差しに直すもの** (`GET api/recordings/<id>/playback` の `ruler`。`axis` が `encoded` のときだけ)。
 * 形と直し方は denpa の `ts/watch.ts` の `Ruler`・`toTsTime` と同じ。
 *
 * @property kept CM を切ったときに残した区間 (元の TS の秒)。切っていなければ null
 * @property skip エンコードで頭から捨てた長さ (秒)
 */
@Immutable
@Serializable
data class Ruler(val kept: List<KeptRange>? = null, val skip: Double = 0.0) {
    @Immutable
    @Serializable
    data class KeptRange(val start: Double, val end: Double)

    /** 物差しの位置 (秒) を、元の TS の物差しへ */
    fun toTs(at: Double): Double {
        val ranges = kept.orEmpty().filter { it.end > it.start }
        if (ranges.isEmpty()) return at + skip
        var rest = maxOf(0.0, at)
        for (range in ranges) {
            val length = range.end - range.start
            if (rest < length) return range.start + rest
            rest -= length
        }
        return ranges.last().end
    }
}

/**
 * 録画の実況 (`GET api/recordings/<id>` の `jikkyo`)。実況の無い局と、世帯の設定がオフなら無い。
 *
 * @property channel 実況のチャンネル (`jk1` など)
 * @property head 元の TS の頭 (再生位置 0 の手前) の放送時刻 (unix ms)
 * @property end 録画の終わりの放送時刻 (予定。unix ms)。無ければ null
 */
data class RecordingJikkyo(val channel: String, val head: Long, val end: Long?)

/** `jikkyo` を読む。null・無いものは null。形がずれていれば `warn` に書いて null */
fun parseRecordingJikkyo(json: JsonObject?, warn: (String) -> Unit = {}): RecordingJikkyo? {
    val raw = json?.get("jikkyo") ?: return null
    if (raw is kotlinx.serialization.json.JsonNull) return null
    val obj = raw as? JsonObject
    val channel = (obj?.get("channel") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    val head = (obj?.get("head") as? JsonPrimitive)?.doubleOrNull
    if (channel == null || head == null) {
        warn("録画の jikkyo の形式が違います (denpa の版が違う?): ${raw.toString().take(200)}")
        return null
    }
    val end = (obj["end"] as? JsonPrimitive)?.doubleOrNull
    return RecordingJikkyo(channel, head.roundToLong(), end?.roundToLong())
}
