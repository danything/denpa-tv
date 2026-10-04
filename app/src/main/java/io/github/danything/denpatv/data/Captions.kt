package io.github.danything.denpatv.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * **生の TS (MPEG-2) の字幕。** 放送の字幕は文字と指定だけで絵が乗っていないので、denpa が描いた絵を
 * `GET api/services/<id>/captions` (ライブ) / `GET api/recordings/<id>/captions?from=<秒>` (追っかけ・録画) で受け取る。
 * ブラウザの denpa の生の道と同じもの (denpa の docs/api.md「生TSの字幕」)。
 *
 * 絵には**放送の PTS** が付いてくる。映像の PTS がそこを過ぎたら重ね、次の絵が来るまで出しておく
 * (全部透明な絵が来たら消える)。映像の PTS は Media3 が 0 に寄せているので、足し戻して比べる (`Pts`)
 */
class CaptionCue(
    /** 放送の PTS (90kHz、33 ビットで一周) */
    val pts: Long,
    /** 置き場所。1920x1080 の上の座標 (いまは画面まるごと) */
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** 絵 (RGBA の PNG)。**解くのは出す番が来てから** (1枚解くと 8MB になる) */
    val png: ByteArray,
)

/** 字幕の口から読んだ1こま */
sealed interface CaptionFrame {
    class Cue(val cue: CaptionCue) : CaptionFrame

    /** 選べる字幕の知らせ。字幕を持たない放送では来ない */
    class Tracks(val count: Int) : CaptionFrame

    /** ほかの知らせ (20 秒おきの ping など)。読み捨てる */
    data object Other : CaptionFrame
}

/**
 * 字幕の口の読み方。本文は WebSocket の1こまの頭に長さを付けたものが並ぶ:
 *
 *     [4: 後ろの長さ (BE)][1: 種別][8: 時刻 (90kHz, BE)][中身]
 *     0x20 字幕の絵  [2:x][2:y][2:w][2:h][PNG]
 *     0x40 知らせ    JSON
 */
object CaptionFeed {
    const val SUBTITLE = 0x20
    const val CONTROL = 0x40

    /** 1こまの上限。壊れた長さで何百 MB も取りにいかない (字幕の絵は 1 枚 数十 KB) */
    private const val MAX_FRAME = 16 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000
    /** denpa は 20 秒おきに ping を送る。2 回来なければ切れたとみなす */
    private const val READ_TIMEOUT_MS = 45_000
    private val json = Json { ignoreUnknownKeys = true }

    /** 1こま読む。終わり (きれいに閉じた) なら null */
    fun read(input: DataInputStream): CaptionFrame? {
        val length = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (length < 9 || length > MAX_FRAME) throw IOException("字幕のこまの長さがおかしい: $length")
        val kind = input.readUnsignedByte()
        val pts = input.readLong()
        val payload = ByteArray(length - 9).also { input.readFully(it) }
        return when (kind) {
            SUBTITLE -> cue(pts, payload)
            CONTROL -> notice(payload)
            else -> CaptionFrame.Other
        }
    }

    private fun cue(pts: Long, payload: ByteArray): CaptionFrame {
        if (payload.size < 8) return CaptionFrame.Other
        fun u16(at: Int) = ((payload[at].toInt() and 0xff) shl 8) or (payload[at + 1].toInt() and 0xff)
        return CaptionFrame.Cue(CaptionCue(pts, u16(0), u16(2), u16(4), u16(6), payload.copyOfRange(8, payload.size)))
    }

    /** 形の違う知らせは読み捨てる (denpa の版の違い。止めるほどのことではない) */
    private fun notice(payload: ByteArray): CaptionFrame {
        val obj = runCatching { json.parseToJsonElement(payload.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: return CaptionFrame.Other
        if ((obj["type"] as? JsonPrimitive)?.content != "captions") return CaptionFrame.Other
        return CaptionFrame.Tracks((obj["tracks"] as? JsonArray)?.size ?: 0)
    }

    /** 断られた。404 はライブなら「まだ流していない」(映像を開いた直後)、録画なら「生TSが無い」 */
    class Refused(val code: Int) : IOException("字幕の口が $code を返しました")

    /** 繋ぐ。`token` があれば `Authorization: Bearer` を付ける。401 は `Unauthorized`、ほかの失敗は `Refused` */
    fun connect(url: URI, token: String?): HttpURLConnection {
        val connection = url.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        Http.bearer(token)?.let { connection.setRequestProperty("Authorization", it) }
        val code = connection.responseCode
        if (code == 401) {
            connection.disconnect()
            throw Unauthorized(url)
        }
        if (code !in 200..299) {
            connection.disconnect()
            throw Refused(code)
        }
        return connection
    }
}

/**
 * 放送の PTS (90kHz、33 ビット)。**26.5 時間で一周する**ので、比べるときは近いほうへ伸ばす
 */
object Pts {
    const val WRAP = 1L shl 33
    private const val HALF = 1L shl 32

    /** `a − b` を一周の半分の中に収めたもの。正なら a が後 */
    fun delta(a: Long, b: Long): Long {
        val d = Math.floorMod(a - b, WRAP)
        return if (d >= HALF) d - WRAP else d
    }

    /**
     * 再生位置を放送の PTS に戻す。Media3 の TS の読み手は最初の PTS を 0 (シークして始めたならその位置) に寄せ、
     * 寄せた幅を `TimestampAdjuster.getTimestampOffsetUs()` に持っている (サンプルの時刻 = 放送の時刻 + 幅)。
     * 読み手は一周をまたいでも伸ばし続けるので、ここで 33 ビットに畳む
     */
    fun broadcast(positionMs: Long, offsetUs: Long): Long = Math.floorMod(Math.floorDiv((positionMs * 1000 - offsetUs) * 9, 100), WRAP)
}

/**
 * 届いた字幕を時刻の順に持つ。読み手 (IO) と出す側 (画面) から触るので、まとめて鍵を掛ける。
 *
 * 出すのは「時計を過ぎた中で最後の1枚」。それより前のものはもう出番が無いので捨てる
 * (録画は倍速で先へ読むので、残しておくと溜まる)
 */
class CueTimeline {
    private val cues = ArrayDeque<CaptionCue>()

    @Synchronized
    fun add(cue: CaptionCue) {
        cues.addLast(cue)
    }

    @Synchronized
    fun clear() = cues.clear()

    @Synchronized
    fun size(): Int = cues.size

    /** 時計 `clock` (放送の PTS) で出ているもの。まだ1枚も過ぎていなければ null */
    @Synchronized
    fun at(clock: Long): CaptionCue? {
        while (cues.size >= 2 && Pts.delta(cues[1].pts, clock) <= 0) cues.removeFirst()
        val first = cues.firstOrNull() ?: return null
        return first.takeIf { Pts.delta(it.pts, clock) <= 0 }
    }
}

/** 字幕の口の場所 (denpa の根からの相対) */
object CaptionPaths {
    /** ライブ。局の一覧の `live` (`api/services/<id>/live`) の隣。形が違えば null (字幕は出さない) */
    fun live(live: String): String? =
        live.substringBefore('?').takeIf { it.endsWith("/live") }?.let { it.removeSuffix("/live") + "/captions" }

    /** 追っかけ・録画 (生TS)。`?from=<秒>` は頼むときに付ける */
    fun recording(id: Long): String = "api/recordings/$id/captions"
}
