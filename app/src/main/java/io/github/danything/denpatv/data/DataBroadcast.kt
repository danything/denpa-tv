package io.github.danything.denpatv.data

import android.view.KeyEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.DataInputStream
import java.net.URI
import kotlin.math.roundToLong

/*
 * **データ放送。** 描くのは denpa のページ (`<denpa>/embed/data`) で、アプリは映像の上に透明な WebView で重ねて、
 * キー・時刻・ライブのデータを MessagePort で渡す (denpa の docs/api.md「データ放送のページ `/embed/data`」)。
 * ここはそのやり取りの形 (画面に依らないもの)。WebView を持つのは ui/DataBroadcast.kt
 */

/**
 * リモコンのキーを、ページへ渡すキー (AribKeyCode。STD-B24 第二編 付属 2 表 5-9) に直したもの。
 * 決定 (`ENTER`) は短押し・長押しを分けてから (`PlayerFrame` の `onCenter`)、戻る (`BACK`) は戻るの受け口 (`BackHandler`) から渡す
 */
object AribKey {
    const val UP = 1
    const val DOWN = 2
    const val LEFT = 3
    const val RIGHT = 4
    const val ENTER = 18
    const val BACK = 19
    const val BLUE = 21
    const val RED = 22
    const val GREEN = 23
    const val YELLOW = 24

    /** 数字 (0〜12)。0〜9 が 5〜14、10・11・12 が 15・16・17 */
    fun digit(n: Int): Int = 5 + n
}

/** データ放送を開いている間に、ページへ渡すキー (`dataKey`) */
sealed interface DataKey {
    /** 押した・離したをそのまま渡す */
    data class Key(val code: Int) : DataKey

    /** d。開く・閉じるはページが決める (文書が d を受け付けていれば文書へ) */
    data object D : DataKey
}

/**
 * **開いている間にページへ渡すキー。** d (`KEYCODE_TV_DATA_SERVICE`)・十字・数字・色。null は TV に残すもの
 * (CH±・Menu・番組表・情報・再生のキー)。d は閉じている間も受ける (開く)。
 * 数字の 0 はデータ放送では 0 (ライブの選局では 10)。色は録画の緑 (速さ) より先にページへ
 */
fun dataKey(keyCode: Int): DataKey? = when (keyCode) {
    KeyEvent.KEYCODE_TV_DATA_SERVICE -> DataKey.D
    KeyEvent.KEYCODE_DPAD_UP -> DataKey.Key(AribKey.UP)
    KeyEvent.KEYCODE_DPAD_DOWN -> DataKey.Key(AribKey.DOWN)
    KeyEvent.KEYCODE_DPAD_LEFT -> DataKey.Key(AribKey.LEFT)
    KeyEvent.KEYCODE_DPAD_RIGHT -> DataKey.Key(AribKey.RIGHT)
    KeyEvent.KEYCODE_PROG_BLUE -> DataKey.Key(AribKey.BLUE)
    KeyEvent.KEYCODE_PROG_RED -> DataKey.Key(AribKey.RED)
    KeyEvent.KEYCODE_PROG_GREEN -> DataKey.Key(AribKey.GREEN)
    KeyEvent.KEYCODE_PROG_YELLOW -> DataKey.Key(AribKey.YELLOW)
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> DataKey.Key(AribKey.digit(keyCode - KeyEvent.KEYCODE_0))
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> DataKey.Key(AribKey.digit(keyCode - KeyEvent.KEYCODE_NUMPAD_0))
    KeyEvent.KEYCODE_11 -> DataKey.Key(AribKey.digit(11))
    KeyEvent.KEYCODE_12 -> DataKey.Key(AribKey.digit(12))
    else -> null
}

/** 映像を置く場所 (ページの `video`)。割合は WebView (= 映像の枠) に対するもの */
sealed interface VideoPlace {
    /** 枠いっぱい (ふだん) */
    data object Full : VideoPlace

    /** 映像を隠す (Hybridcast のアプリが求めた) */
    data object Hidden : VideoPlace

    /** 映像をここへ縮める (0〜1、左上が 0)。字幕も一緒に縮める */
    data class Rect(val left: Float, val top: Float, val width: Float, val height: Float) : VideoPlace
}

/** ページから届いたもの (`DataPage.parse`) */
sealed interface PageMessage {
    data object Ready : PageMessage
    data class Failed(val reason: String) : PageMessage
    data class Open(val on: Boolean) : PageMessage
    data class Video(val place: VideoPlace) : PageMessage

    /** 文書が別の局への選局を求めた。`service` は局の `id` */
    data class Tune(val service: Long) : PageMessage

    /** 文書が主音声を替えた。`index` は PMT の音声の ES の何本目か。null はふだんの音声に戻す */
    data class Audio(val index: Int?) : PageMessage
    data class Captions(val on: Boolean) : PageMessage

    /** 映像の上に出しておく短い通知。空なら消す */
    data class Note(val text: String) : PageMessage

    /** 1 回だけ出す通知 */
    data class Toast(val text: String) : PageMessage
}

/**
 * **ページとのやり取り** (1 通は JSON の文字列 1 つ)。アプリ → ページのものを組み立て、ページ → アプリのものを読む。
 * 形の合わないものは読み捨てる (denpa の版のずれ。止めるほどのことではない)
 */
object DataPage {
    /** ページの場所 (denpa のルートからの相対) */
    const val PATH = "embed/data"

    /** ポートを渡すメッセージの中身。ページはこれ以外の中身で届いたポートを受けない */
    const val GREETING = "denpa-tv"

    fun initLive(service: Long, open: Boolean, receive: Boolean, captions: Boolean): String = buildJsonObject {
        put("type", "init")
        put("mode", "live")
        put("service", service)
        put("open", open)
        put("receive", receive)
        put("captions", captions)
    }.toString()

    fun initRecording(recording: Long, open: Boolean, receive: Boolean, captions: Boolean): String = buildJsonObject {
        put("type", "init")
        put("mode", "recording")
        put("recording", recording)
        put("open", open)
        put("receive", receive)
        put("captions", captions)
    }.toString()

    /**
     * ライブのデータ放送 (0x30) と通知 (0x40)。`json` は denpa から届いた JSON のオブジェクトをそのまま埋める。
     * 読み直して組み立て直さないのは、繋いだときにまとめて届くモジュール (数十 MB) をテレビの CPU で読み直さないため。
     * 届いたのが JSON のオブジェクトの形か (`DataFeed` が頭と尻を見る) だけ確かめてある
     */
    fun data(json: String): String = """{"type":"data","message":$json}"""

    fun notice(json: String): String = """{"type":"notice","message":$json}"""

    /** 再生の時刻。`air` は映しているコマの放送の時刻 (UNIX ミリ秒。分からなければ null)、`pos` は録画の再生位置 (ミリ秒) */
    fun time(air: Long?, pos: Long, rate: Float, paused: Boolean): String = buildJsonObject {
        put("type", "time")
        if (air == null) put("air", JsonNull) else put("air", air)
        put("pos", pos)
        put("rate", rate)
        put("paused", paused)
    }.toString()

    fun key(code: Int, down: Boolean): String = """{"type":"key","code":$code,"down":$down}"""

    const val D = """{"type":"d"}"""
    const val CLOSE = """{"type":"close"}"""

    fun receive(on: Boolean): String = """{"type":"receive","on":$on}"""

    fun channel(service: Long): String = """{"type":"channel","service":$service}"""

    fun captions(on: Boolean): String = """{"type":"captions","on":$on}"""

    /** ページから届いた1通を読む。読めない・知らない `type` は null */
    fun parse(text: String?): PageMessage? {
        val obj = runCatching { lenientJson.parseToJsonElement(text ?: return null) as? JsonObject }.getOrNull() ?: return null
        fun bool(key: String): Boolean? = (obj[key] as? JsonPrimitive)?.booleanOrNull
        fun string(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
            "ready" -> PageMessage.Ready
            "failed" -> PageMessage.Failed(string("reason") ?: "")
            "open" -> bool("on")?.let { PageMessage.Open(it) }
            "video" -> place(obj["rect"])?.let { PageMessage.Video(it) }
            "tune" -> (obj["service"] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.roundToLong() }?.let { PageMessage.Tune(it) }
            "audio" -> when (val index = obj["index"]) {
                null, JsonNull -> PageMessage.Audio(null)
                is JsonPrimitive -> index.doubleOrNull?.takeIf { it >= 0 && it == Math.floor(it) }?.let { PageMessage.Audio(it.toInt()) }
                else -> null
            }
            "captions" -> bool("on")?.let { PageMessage.Captions(it) }
            "note" -> PageMessage.Note(string("text") ?: "")
            "toast" -> string("text")?.takeIf { it.isNotBlank() }?.let { PageMessage.Toast(it) }
            else -> null
        }
    }

    /** `rect` を読む。null は枠いっぱい、`"hidden"` は隠す、割合は 0〜1 に収める。幅・高さが 0 以下なら読み捨てる */
    private fun place(rect: kotlinx.serialization.json.JsonElement?): VideoPlace? = when (rect) {
        null, JsonNull -> VideoPlace.Full
        is JsonPrimitive -> if (rect.contentOrNull == "hidden") VideoPlace.Hidden else null
        is JsonObject -> {
            fun part(key: String): Float? = (rect[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }?.toFloat()
            val left = part("left")
            val top = part("top")
            val width = part("width")
            val height = part("height")
            if (left == null || top == null || width == null || height == null || width <= 0f || height <= 0f) {
                null
            } else {
                val l = left.coerceIn(0f, 1f)
                val t = top.coerceIn(0f, 1f)
                VideoPlace.Rect(l, t, width.coerceAtMost(1f - l), height.coerceAtMost(1f - t))
            }
        }
        else -> null
    }
}

/** ライブのデータ放送のエンドポイント (`GET api/services/<id>/data`) から読んだ1フレーム */
sealed interface DataFrame {
    /** データ放送 (0x30)。JSON のオブジェクト (`ResponseMessage`) をそのまま */
    class Data(val json: String) : DataFrame

    /** ページが読む通知 (0x40 の `card`・`hybridcast`)。JSON のオブジェクトをそのまま */
    class Notice(val json: String) : DataFrame

    /** ほかの通知 (20 秒おきの ping など)。読み捨てる */
    data object Other : DataFrame
}

/**
 * ライブのデータ放送のエンドポイントの読み方。並び方は字幕と同じ (`CaptionFeed`。時刻の欄はいつも 0):
 *
 *     [4: 後ろの長さ (BE)][1: 種別][8: 時刻 (0)][中身]
 *     0x30 データ放送  JSON
 *     0x40 通知       JSON (`card`・`hybridcast`・`ping`)
 */
object DataFeed {
    const val DATA = 0x30

    /** ページへ渡す通知の `type` */
    private val PAGE_NOTICES = setOf("card", "hybridcast")

    /** 1フレーム読む。終わり (きれいに閉じた) なら null */
    fun read(input: DataInputStream): DataFrame? {
        val frame = CaptionFeed.frame(input) ?: return null
        val text = frame.payload.toString(Charsets.UTF_8)
        return when (frame.kind) {
            DATA -> if (looksLikeObject(text)) DataFrame.Data(text) else DataFrame.Other
            CaptionFeed.CONTROL -> {
                val type = runCatching { (lenientJson.parseToJsonElement(text) as? JsonObject)?.get("type") as? JsonPrimitive }.getOrNull()?.contentOrNull
                if (type in PAGE_NOTICES) DataFrame.Notice(text) else DataFrame.Other
            }
            else -> DataFrame.Other
        }
    }

    /** JSON のオブジェクトの形か (頭が `{`、尻が `}`)。中身は読み直さない (`DataPage.data`) */
    private fun looksLikeObject(text: String): Boolean = text.trimStart().startsWith('{') && text.trimEnd().endsWith('}')
}

/** データ放送のエンドポイントとページの場所 (denpa のルートからの相対) */
object DataPaths {
    /** ライブ。局の一覧の `live` (`api/services/<id>/live`) の隣。形式が違えば null */
    fun live(live: String): String? =
        live.substringBefore('?').takeIf { it.endsWith("/live") }?.let { it.removeSuffix("/live") + "/data" }

    /** WebView の Cookie (`POST api/device/web`) */
    const val COOKIE = "api/device/web"
}

/** 録画のデータ放送 (`GET api/recordings/<id>/playback` の `data`)。取り出してあれば付く */
@Serializable
data class PlayData(
    /** 入口に自動起動の指定があるか。自動で受信する端末は、これが true の録画でだけ開く前から受信する */
    val autoStart: Boolean = false,
)

/**
 * WebView の Cookie (`POST api/device/web` の答え。denpa の docs/api.md「WebView の Cookie」)。
 * WebView は `Authorization` を付けられないので、アクセストークンと引き換えた Cookie でページを開く
 */
data class AppCookie(val name: String, val value: String, val maxAge: Long) {
    /**
     * `CookieManager.setCookie` に渡す値。denpa を開いている URL のパス (前段の接頭辞の下なら接頭辞) に、`HttpOnly; SameSite=Strict`
     * (https なら `Secure` も) で入れる
     */
    fun header(base: URI): String = buildString {
        append("$name=$value; Max-Age=$maxAge; Path=${base.rawPath?.ifEmpty { "/" } ?: "/"}; HttpOnly; SameSite=Strict")
        if (base.scheme.equals("https", ignoreCase = true)) append("; Secure")
    }

    companion object {
        /** 読めない・名前か値が空なら null */
        fun parse(json: String): AppCookie? {
            val obj = runCatching { lenientJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            val value = (obj["value"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            // Cookie の区切りの文字が入っていれば受けない (ほかの属性を書き足せないように)
            if (listOf(name, value).any { part -> part.any { it == ';' || it == ',' || it.isWhitespace() } }) return null
            val maxAge = (obj["maxAge"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return null
            return AppCookie(name, value, maxAge)
        }
    }
}

/** URL の組 (スキーム・ホスト・ポート) が同じか。ポートの既定 (http の 80・https の 443) は書いていないのと同じ */
object WebOrigin {
    /** `postWebMessage` の相手に渡すオリジン (`http://192.168.1.10:3000`)。既定のポートは書かない */
    fun of(base: URI): String {
        val scheme = base.scheme.lowercase()
        val port = base.port.takeUnless { it == defaultPort(scheme) } ?: -1
        return URI(scheme, null, base.host.lowercase(), port, null, null, null).toString()
    }

    /** `url` が `base` と同じオリジンか。読めない URL は違うとみなす */
    fun same(base: URI, url: String): Boolean {
        val target = runCatching { URI(url) }.getOrNull() ?: return false
        if (target.scheme == null || target.host == null) return false
        return of(target) == of(base)
    }

    private fun defaultPort(scheme: String): Int = when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> -2
    }
}
