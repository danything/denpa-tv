package io.github.danything.denpatv.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64

/**
 * **字幕の文字の配置** (denpa の docs/api.md「字幕の文字の配置」、`src/lib/caption-text.ts` と同じ形)。
 * denpa が放送の字幕 (ARIB STD-B24) を解いて置き場所・大きさ・色まで決めたもの。こちらは言われたとおりに塗るだけ
 * (`ui/TextCaptions.kt`)。ライブ・追っかけ・生TSの録画は字幕の口の 0x22、焼いた録画は `captions.json` で届く。
 *
 * 座標はすべて**字幕の面の単位** (`planeWidth` × `planeHeight`。ふつう 960x540)。映像の枠に合わせて縦横それぞれ伸ばす
 */
class CaptionPage(
    val planeWidth: Float,
    val planeHeight: Float,
    /** 出しておく長さ (ミリ秒)。null なら次の1枚まで */
    val durationMs: Long?,
    /** 描く順 */
    val runs: List<CaptionRun>,
    /** 置き換えられなかった外字の絵。`CaptionRun.drcs` で引く */
    val drcs: Map<String, CaptionDrcs>,
) {
    /** 点滅するものがあるか (その間だけ描き直す) */
    val flashes: Boolean = runs.any { it.flash }

    /** 何か描いてある縦の範囲 (面の単位)。下に重ねたものの上へ逃がすときに見る。空なら上下とも 0 */
    val top: Float = runs.minOfOrNull { it.y } ?: 0f
    val bottom: Float = runs.maxOfOrNull { it.y + it.h } ?: 0f

    /** 読み上げ用の文 (行が替わるところで改行)。smoke も字幕が出たかをこれで見る */
    val text: String = buildString {
        var row: Float? = null
        for (run in runs) {
            if (run.drcs != null) continue
            if (row != null && run.y != row) append('\n')
            row = run.y
            append(run.text)
        }
    }.trim()
}

/**
 * 同じ見た目で横に並ぶ字。`text` の1字 (コードポイント) ごとに、幅 `w`・高さ `h` の区画を `x` から右へ1つずつ使う
 */
class CaptionRun(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    /** 区画の左上から字の枠の左上まで */
    val fx: Float,
    val fy: Float,
    /** 字の大きさ (em)。字の枠の高さでもある */
    val size: Float,
    /** 横の縮め方。字の枠の幅は `size * scaleX` */
    val scaleX: Float,
    val text: String,
    /** 置き換えられなかった外字。`text` は「〓」で、この絵を字の枠いっぱいに描く */
    val drcs: String?,
    /** ARGB */
    val fg: Int,
    val bg: Int,
    /** 縁取りの色。null なら縁取らない */
    val stroke: Int?,
    val underline: Boolean,
    /** 囲み。1 下・2 右・4 上・8 左の和 (0 なら無し) */
    val box: Int,
    val flash: Boolean,
) {
    /**
     * 字ごとの `text` の中の位置 (UTF-16)。i 字目は `bounds[i]` から `bounds[i + 1]` まで。
     * 描くたびにコードポイントを数え直さない (サロゲートペアの字もある)
     */
    val bounds: IntArray = codePointBounds(text)

    /** 字の数 */
    val count: Int get() = bounds.size - 1

    /** i 字目が空白か (背景だけ塗って字は描かない) */
    val spaces: BooleanArray = BooleanArray(count) { isCaptionSpace(text.codePointAt(bounds[it])) }
}

/** 外字の絵。`alpha` は左上から1画素ずつの濃さ (0〜255) */
class CaptionDrcs(val width: Int, val height: Int, val alpha: ByteArray)

/** 縁取りの太さ (面の単位、字の外側へ)。denpa の `STROKE_WIDTH` */
const val CAPTION_STROKE_WIDTH = 1.5f

/** 点滅の間 (ミリ秒)。出ている時間と消えている時間。denpa の `FLASH_MS` */
const val CAPTION_FLASH_MS = 500L

/** 読める形の版。**知らない版は描かない** (denpa は互換の無い変え方をしたら上げる) */
const val CAPTION_TEXT_VERSION = 1

/** 空白として字を描かない字。libaribcaption の IsSpaceCharacter (denpa の `isSpace`) と同じ */
fun isCaptionSpace(code: Int): Boolean =
    code == 0x09 || code == 0x20 || code == 0xa0 || code == 0x1680 || code == 0x3000 ||
        code == 0x202f || code == 0x205f || code in 0x2000..0x200a

private fun codePointBounds(text: String): IntArray {
    val out = IntArray(text.codePointCount(0, text.length) + 1)
    var at = 0
    for (i in 1 until out.size) {
        at += Character.charCount(text.codePointAt(at))
        out[i] = at
    }
    return out
}

/**
 * 字の枠の上から基準線までの高さ (px)。**「永」の墨の上下が字の枠の真ん中に来るように置く** (libaribcaption と同じ決め方。
 * denpa の caption-draw.ts と同じ式)。`ascent`・`descent` は字の大きさ 1 あたりの「永」の墨の上と下 (どちらも正)
 */
fun captionBaseline(fontPx: Float, ascent: Float, descent: Float): Float =
    (fontPx - (ascent + descent) * fontPx) / 2 + ascent * fontPx

/** `#rrggbbaa` を ARGB に。読めなければ null */
fun captionColor(text: String?): Int? {
    if (text == null || text.length != 9 || text[0] != '#') return null
    val n = text.substring(1).toLongOrNull(16) ?: return null
    val rgb = (n ushr 8).toInt() and 0xffffff
    val a = (n and 0xff).toInt()
    return (a shl 24) or rgb
}

/**
 * 外字の絵を濃さに開く。`data` は base64 で、左上から1画素 `bits` ビットずつ上の桁から詰めたもの。値 v の濃さは v / (depth − 1)
 */
fun decodeDrcs(width: Int, height: Int, depth: Int, bits: Int, data: String): CaptionDrcs? {
    if (width !in 1..MAX_DRCS || height !in 1..MAX_DRCS || depth < 2 || bits !in 1..8) return null
    val bytes = runCatching { Base64.Default.decode(data) }.getOrNull() ?: return null
    if (bytes.size * 8 < width * height * bits) return null
    val mask = (1 shl bits) - 1
    val alpha = ByteArray(width * height)
    for (i in alpha.indices) {
        val bit = i * bits
        val value = ((bytes[bit shr 3].toInt() and 0xff) shr (8 - (bit and 7) - bits)) and mask
        alpha[i] = ((value.coerceAtMost(depth - 1) * 255 + (depth - 1) / 2) / (depth - 1)).toByte()
    }
    return CaptionDrcs(width, height, alpha)
}

/** 外字の絵の大きさの上限 (ふつう 16〜36 画素) */
private const val MAX_DRCS = 256

/**
 * 1枚を読む。**知らない版・形の崩れた1枚は null** (描かない)。足された鍵は読み捨てる。
 * 読めない run (色や座標が無い) は、その run だけ捨てる (denpa の版の違い。止めるほどのことではない)
 */
fun parseCaptionPage(element: JsonElement?): CaptionPage? {
    val obj = element as? JsonObject ?: return null
    if (obj.int("v") != CAPTION_TEXT_VERSION) return null
    val plane = obj["plane"] as? JsonArray ?: return null
    val planeWidth = (plane.getOrNull(0) as? JsonPrimitive)?.floatOrNull?.takeIf { it > 0f } ?: return null
    val planeHeight = (plane.getOrNull(1) as? JsonPrimitive)?.floatOrNull?.takeIf { it > 0f } ?: return null
    val drcs = (obj["drcs"] as? JsonObject)?.mapNotNull { (key, value) ->
        val d = value as? JsonObject ?: return@mapNotNull null
        decodeDrcs(d.int("w") ?: 0, d.int("h") ?: 0, d.int("depth") ?: 0, d.int("bits") ?: 0, d.string("data") ?: "")?.let { key to it }
    }?.toMap().orEmpty()
    val runs = (obj["runs"] as? JsonArray ?: return null).mapNotNull { parseRun(it) }
    return CaptionPage(planeWidth, planeHeight, (obj["duration"] as? JsonPrimitive)?.longOrNull, runs, drcs)
}

private fun parseRun(element: JsonElement): CaptionRun? {
    val run = element as? JsonObject ?: return null
    fun float(key: String) = run.float(key)
    return CaptionRun(
        x = float("x") ?: return null,
        y = float("y") ?: return null,
        w = float("w") ?: return null,
        h = float("h") ?: return null,
        fx = float("fx") ?: 0f,
        fy = float("fy") ?: 0f,
        size = float("size")?.takeIf { it > 0f } ?: return null,
        scaleX = float("scaleX")?.takeIf { it > 0f } ?: 1f,
        text = run.string("text") ?: return null,
        drcs = run.string("drcs"),
        fg = captionColor(run.string("fg")) ?: return null,
        bg = captionColor(run.string("bg")) ?: 0,
        stroke = captionColor(run.string("stroke")),
        underline = run.bool("underline"),
        box = run.int("box") ?: 0,
        flash = run.bool("flash"),
    )
}

private fun JsonObject.primitive(key: String) = this[key] as? JsonPrimitive
private fun JsonObject.int(key: String) = primitive(key)?.intOrNull
private fun JsonObject.float(key: String) = primitive(key)?.floatOrNull
private fun JsonObject.bool(key: String) = primitive(key)?.booleanOrNull == true
private fun JsonObject.string(key: String) = primitive(key)?.takeIf { it.isString }?.content

/**
 * 焼いた録画の字幕まるごと (`GET api/recordings/<id>/captions.json`)。`atMs` は動画の頭からのミリ秒で、並びは時刻の順
 */
class CaptionPages(private val atMs: LongArray, private val pages: List<CaptionPage>) {
    val size: Int get() = pages.size

    /**
     * 再生位置 `positionMs` で出ているもの。**位置を追い越していない中の最後の1枚**。
     * 出しておく長さ (`durationMs`) を過ぎていれば、次を待たずに null
     */
    fun at(positionMs: Long): CaptionPage? {
        // positionMs 以下で最後のもの
        var lo = 0
        var hi = atMs.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (atMs[mid] <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0) return null
        val page = pages[found]
        val duration = page.durationMs
        if (duration != null && positionMs - atMs[found] >= duration) return null
        return page.takeIf { it.runs.isNotEmpty() }
    }

    companion object {
        /** `captions.json` を読む。知らない版・形が違えば null。読めない1枚だけは捨てる */
        fun parse(json: String): CaptionPages? {
            val obj = runCatching { lenientJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
            if (obj.int("v") != CAPTION_TEXT_VERSION) return null
            val entries = (obj["pages"] as? JsonArray ?: return null).mapNotNull { entry ->
                val e = entry as? JsonObject ?: return@mapNotNull null
                val at = e.primitive("at")?.doubleOrNull ?: return@mapNotNull null
                val page = parseCaptionPage(e["page"]) ?: return@mapNotNull null
                Math.round(at * 1000) to page
            }.sortedBy { it.first }
            return CaptionPages(LongArray(entries.size) { entries[it].first }, entries.map { it.second })
        }
    }
}
