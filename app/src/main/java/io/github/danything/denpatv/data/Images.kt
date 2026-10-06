package io.github.danything.denpatv.data

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.core.graphics.scale
import java.net.URI
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 局ロゴと録画のポスターを読む。**画像のライブラリは入れない** — 出すのは局ロゴと録画のポスター (小さい絵。
 * 一覧の上に敷く大きな絵もポスターをごく小さく読んでぼかしたもの) だけで、要るのは「縮めて読む」と「読んだものを覚えておく」だけ
 * (docs/libraries.md)。
 *
 * - **出す大きさちょうどに読む** (2 の冪で縮めたあと、BitmapFactory の拡大率で出す大きさまで縮める。大きく読んで描くときに縮めない)
 * - ポスター (透けない絵) は **RGB_565** で読む (1点 2 バイト。ARGB の半分)。ロゴは透けるので ARGB のまま
 * - 覚えておく量は端末のメモリの級 (`ActivityManager.memoryClass`) の 1/8 (`init`)
 */
object Images {
    /** 覚えておく量 (バイト)。`init` の前は 24MB */
    private var cache = newCache(DEFAULT_CACHE_BYTES)

    /** 端末のメモリに合わせて覚えておく量を決める (アプリの起動時に1回) */
    fun init(context: Context) {
        val memoryClass = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.memoryClass ?: return
        cache = newCache(cacheBytes(memoryClass))
    }

    private fun newCache(bytes: Int) = object : LruCache<String, Bitmap>(bytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(url: String, widthPx: Int, heightPx: Int, opaque: Boolean = false): Bitmap? = cache.get(key(url, widthPx, heightPx, opaque))

    /**
     * 取ってきて、出す大きさ (`widthPx`×`heightPx` を覆う大きさ) に縮めて読む。読めなければ null。IO の上で呼ぶ。
     * `opaque` なら RGB_565 で読む (透けない絵だけ。ポスター)
     */
    fun load(url: String, widthPx: Int, heightPx: Int, token: String? = null, opaque: Boolean = false): Bitmap? {
        val key = key(url, widthPx, heightPx, opaque)
        cache.get(key)?.let { return it }
        val bitmap = decode(fetch(url, token) ?: return null, widthPx, heightPx, opaque) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    /**
     * **ぼかした絵** (一覧の上の段・詳しくの後ろに敷く)。ごく小さく (`BLUR_WIDTH`×`BLUR_HEIGHT`) 読んで、さらに箱でぼかす。
     * 描く側は引き伸ばすだけで、絵がぼけて見える (描くたびにぼかす RenderEffect が要らず、Android 12 より前でも同じ見た目)
     */
    fun loadBlurred(url: String, token: String? = null): Bitmap? {
        val key = "$url@blur"
        cache.get(key)?.let { return it }
        val small = decode(fetch(url, token) ?: return null, BLUR_WIDTH, BLUR_HEIGHT, opaque = false) ?: return null
        // 覆う大きさに読んであるので、真ん中を切り出す (16:9 でない絵も縦横比を崩さない)。足りなければ引き伸ばす
        val exact = when {
            small.width == BLUR_WIDTH && small.height == BLUR_HEIGHT -> small
            small.width >= BLUR_WIDTH && small.height >= BLUR_HEIGHT ->
                Bitmap.createBitmap(small, (small.width - BLUR_WIDTH) / 2, (small.height - BLUR_HEIGHT) / 2, BLUR_WIDTH, BLUR_HEIGHT)
            else -> small.scale(BLUR_WIDTH, BLUR_HEIGHT)
        }
        val pixels = IntArray(BLUR_WIDTH * BLUR_HEIGHT)
        exact.getPixels(pixels, 0, BLUR_WIDTH, 0, 0, BLUR_WIDTH, BLUR_HEIGHT)
        repeat(BLUR_PASSES) { boxBlur(pixels, BLUR_WIDTH, BLUR_HEIGHT, BLUR_RADIUS) }
        val blurred = Bitmap.createBitmap(pixels, BLUR_WIDTH, BLUR_HEIGHT, Bitmap.Config.ARGB_8888)
        cache.put(key, blurred)
        return blurred
    }

    fun cachedBlurred(url: String): Bitmap? = cache.get("$url@blur")

    private fun fetch(url: String, token: String?): ByteArray? {
        val res = runCatching { Http.request(URI(url), token = token) }.getOrNull() ?: return null
        return res.body.takeIf { res.ok && it.isNotEmpty() }
    }

    private fun decode(body: ByteArray, widthPx: Int, heightPx: Int, opaque: Boolean): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(body, 0, body.size, bounds)
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, widthPx, heightPx)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            if (opaque) inPreferredConfig = Bitmap.Config.RGB_565
            // 2 の冪で縮めたぶんより、さらに出す大きさまで縮める (拡大率を密度の比で渡す)
            decodeScale(bounds.outWidth / sample, bounds.outHeight / sample, widthPx, heightPx)?.let { (from, to) ->
                inScaled = true
                inDensity = from
                inTargetDensity = to
            }
        }
        return BitmapFactory.decodeByteArray(body, 0, body.size, options)
    }

    private fun key(url: String, w: Int, h: Int, opaque: Boolean) = "$url@${w}x$h" + if (opaque) ":565" else ""
}

/** 覚えておく量 (バイト)。メモリの級 (MB) の 1/8、8MB〜64MB */
fun cacheBytes(memoryClassMb: Int): Int = (memoryClassMb / 8).coerceIn(8, 64) * 1024 * 1024

private const val DEFAULT_CACHE_BYTES = 24 * 1024 * 1024

/** ぼかした絵の大きさと、ぼかし (箱の半径と回数) */
const val BLUR_WIDTH = 64
const val BLUR_HEIGHT = 36
private const val BLUR_RADIUS = 2
private const val BLUR_PASSES = 2

/**
 * BitmapFactory の `inSampleSize`。**出す大きさを下回らない範囲で**、2 の冪で縮める
 * (Android の「大きな画像を効率よく読み込む」の決まりどおり)
 */
fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
    if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
    var size = 1
    while (width / (size * 2) >= targetWidth && height / (size * 2) >= targetHeight) size *= 2
    return size
}

/**
 * 2 の冪で縮めた絵 (`width`×`height`) を、出す大きさを覆う大きさまでさらに縮める拡大率を、BitmapFactory の
 * `inDensity` → `inTargetDensity` の組で返す。縮める要が無ければ (1 割も大きくない・出す大きさより小さい) null
 */
fun decodeScale(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Pair<Int, Int>? {
    if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) return null
    val scale = max(targetWidth.toFloat() / width, targetHeight.toFloat() / height)
    if (scale >= 0.9f) return null
    return DENSITY_BASE to max(1, (scale * DENSITY_BASE).roundToInt() + 1)
}

private const val DENSITY_BASE = 10_000

/** 箱でぼかす (横と縦に `radius` の平均)。小さな絵だけに使う */
fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
    val out = IntArray(pixels.size)
    fun pass(src: IntArray, dst: IntArray, horizontal: Boolean) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        for (line in 0 until lines) {
            for (i in 0 until length) {
                var a = 0; var r = 0; var g = 0; var b = 0; var n = 0
                for (k in -radius..radius) {
                    val j = (i + k).coerceIn(0, length - 1)
                    val p = if (horizontal) src[line * width + j] else src[j * width + line]
                    a += p ushr 24; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
                }
                val index = if (horizontal) line * width + i else i * width + line
                dst[index] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }
    pass(pixels, out, horizontal = true)
    pass(out, pixels, horizontal = false)
}
