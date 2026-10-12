package io.github.danything.denpatv.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * **元の TS (MPEG-2) の字幕の1枚。** 放送の字幕は Media3 ではデコードしないので、denpa がデコードした**文字の配置** (0x22。`CaptionPage`) を
 * `GET api/services/<id>/captions` (ライブ) / `GET api/recordings/<id>/captions?from=<秒>` (追っかけ・録画) で受け取る
 * (denpa の docs/api.md「元のTSの字幕」)。
 *
 * **放送の PTS** が付いてくる。映像の PTS がそこを過ぎたら重ね、次が来るまで出しておく (`runs` が空の1枚が来たら消える)。
 * 映像の PTS は Media3 が 0 に寄せているので、足し戻して比べる (`Pts`)
 *
 * @property pts 放送の PTS (90kHz、33 ビットで一周)
 * @property page null は読めなかった1枚 (知らない版など)。前の1枚は消す
 */
class CaptionCue(val pts: Long, val page: CaptionPage?)

/** 字幕のエンドポイントから読んだ1フレーム */
sealed interface CaptionFrame {
    class Cue(val cue: CaptionCue) : CaptionFrame

    /** 選べる字幕の知らせ。字幕を持たない放送では来ない */
    class Tracks(val count: Int) : CaptionFrame

    /** ほかの知らせ (20 秒おきの ping など)。読み捨てる */
    data object Other : CaptionFrame
}

/**
 * 字幕のエンドポイントの読み方。本文は WebSocket の1フレームの頭に長さを付けたものが並ぶ:
 *
 *     [4: 後ろの長さ (BE)][1: 種別][8: 時刻 (90kHz, BE)][中身]
 *     0x22 字幕の文字の配置  JSON (`CaptionPage`)
 *     0x40 知らせ           JSON
 */
object CaptionFeed {
    const val TEXT = 0x22
    const val CONTROL = 0x40

    /** 1フレームの上限。壊れた長さで何百 MB も取りにいかない */
    private const val MAX_FRAME = 16 * 1024 * 1024
    /** denpa は 20 秒おきに ping を送る。2 回来なければ切れたとみなす */
    private const val READ_TIMEOUT_MS = 45_000

    /** 1フレーム読む。終わり (きれいに閉じた) なら null */
    fun read(input: DataInputStream): CaptionFrame? {
        val length = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (length < 9 || length > MAX_FRAME) throw IOException("字幕のフレームの長さが不正です: $length")
        val kind = input.readUnsignedByte()
        val pts = input.readLong()
        val payload = ByteArray(length - 9).also { input.readFully(it) }
        return when (kind) {
            TEXT -> text(pts, payload)
            CONTROL -> notice(payload)
            else -> CaptionFrame.Other
        }
    }

    /** JSON として読めなければ読み捨てる。読めても知らない版・形式なら、描かない1枚 (前の1枚は消す) */
    private fun text(pts: Long, payload: ByteArray): CaptionFrame {
        val json = runCatching { lenientJson.parseToJsonElement(payload.toString(Charsets.UTF_8)) }.getOrNull() ?: return CaptionFrame.Other
        return CaptionFrame.Cue(CaptionCue(pts, parseCaptionPage(json)))
    }

    /** 形式の違う知らせは読み捨てる (denpa の版の違い。止めるほどのことではない) */
    private fun notice(payload: ByteArray): CaptionFrame {
        val obj = runCatching { lenientJson.parseToJsonElement(payload.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: return CaptionFrame.Other
        if ((obj["type"] as? JsonPrimitive)?.content != "captions") return CaptionFrame.Other
        return CaptionFrame.Tracks((obj["tracks"] as? JsonArray)?.size ?: 0)
    }

    /** 断られた。404 はライブなら「まだ流していない」(映像を開いた直後)、録画なら「元のTSが無い」 */
    class Refused(val code: Int) : IOException("字幕を取得できません (HTTP $code)")

    /** 繋ぐ。`token` があれば `Authorization: Bearer` を付ける。401 は `Unauthorized`、ほかの失敗は `Refused` */
    fun connect(url: URI, token: String?): HttpURLConnection {
        val connection = Http.connection(url, token, readTimeoutMs = READ_TIMEOUT_MS)
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

    /**
     * 時計 `clock` (放送の PTS) で出ているもの。まだ1枚も過ぎていなければ null。
     * 出しておく長さ (`durationMs`) が決まっていれば、過ぎたら次を待たずに null
     */
    @Synchronized
    fun at(clock: Long): CaptionCue? {
        while (cues.size >= 2 && Pts.delta(cues[1].pts, clock) <= 0) cues.removeFirst()
        val cue = cues.firstOrNull()?.takeIf { Pts.delta(it.pts, clock) <= 0 } ?: return null
        val duration = cue.page?.durationMs ?: return cue
        return cue.takeIf { Pts.delta(clock, it.pts) < duration * 90 }
    }
}

/** 字幕のエンドポイントの場所 (denpa のルートからの相対) */
object CaptionPaths {
    /** ライブ。局の一覧の `live` (`api/services/<id>/live`) の隣。形式が違えば null (字幕は出さない) */
    fun live(live: String): String? =
        live.substringBefore('?').takeIf { it.endsWith("/live") }?.let { it.removeSuffix("/live") + "/captions" }

    /** 追っかけ・録画 (元のTS)。リクエストするときに `query` を付ける */
    fun recording(id: Long): String = "api/recordings/$id/captions"

    /** エンコード済みの録画の字幕まるごと (文字の配置)。字幕を持たない録画は 404 */
    fun recordingText(id: Long): String = "api/recordings/$id/captions.json"

    /** 字幕のエンドポイントに付けるもの。録画は読ませる位置 (秒)、ライブは何も付けない */
    fun query(fromSeconds: Long?): String = fromSeconds?.let { "?from=$it" } ?: ""
}
