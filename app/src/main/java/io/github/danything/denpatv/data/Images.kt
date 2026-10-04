package io.github.danything.denpatv.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.net.URI

/**
 * 局ロゴと録画のポスターを読む。**画像のライブラリは入れない** — 出すのは局ロゴと録画のポスター (小さい絵) だけで、
 * 要るのは「縮めて読む」と「読んだものを覚えておく」だけ (docs/libraries.md)
 */
object Images {
    /** 覚えておく量 (バイト)。ロゴと、録画の一覧の数ページぶんのポスターが収まる */
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(url: String, widthPx: Int, heightPx: Int): Bitmap? = cache.get(key(url, widthPx, heightPx))

    /** 取ってきて、出す大きさに縮めて読む。読めなければ null。IO の上で呼ぶ */
    fun load(url: String, widthPx: Int, heightPx: Int, token: String? = null): Bitmap? {
        cached(url, widthPx, heightPx)?.let { return it }
        val res = runCatching { Http.request(URI(url), token = token) }.getOrNull() ?: return null
        if (!res.ok || res.body.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(res.body, 0, res.body.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, widthPx, heightPx)
        }
        val bitmap = BitmapFactory.decodeByteArray(res.body, 0, res.body.size, options) ?: return null
        cache.put(key(url, widthPx, heightPx), bitmap)
        return bitmap
    }

    private fun key(url: String, w: Int, h: Int) = "$url@${w}x$h"
}

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
