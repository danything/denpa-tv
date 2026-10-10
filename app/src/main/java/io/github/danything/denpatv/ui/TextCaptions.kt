package io.github.danything.denpatv.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withScale
import io.github.danything.denpatv.data.CAPTION_FLASH_MS
import io.github.danything.denpatv.data.CAPTION_STROKE_WIDTH
import io.github.danything.denpatv.data.CaptionDrcs
import io.github.danything.denpatv.data.CaptionPage
import io.github.danything.denpatv.data.CaptionRun
import io.github.danything.denpatv.data.captionBaseline
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

/**
 * **字幕の文字の配置を描く** (`CaptionPage`)。denpa のブラウザの描き方 (`src/lib/components/player/caption-draw.ts`) に揃えてある。
 * あちらは絵にしていた頃の libaribcaption に揃えてあるので、焼いていた字幕と同じ所・同じ形に出る:
 *
 * - 背景は区画 (`w`x`h`) ごとに塗る。空白も塗る
 * - 字は字の枠の左端をペンの位置にして、**縦は「永」の墨の上下を枠の真ん中に置く**。横は `scaleX` で縮める
 * - 縁取りは字の外側へ `CAPTION_STROKE_WIDTH`、字より先に塗る
 * - 置き換えられなかった外字は、点の絵を字の枠いっぱいに**なめらかに** (双線形で) 引き伸ばす
 *
 * 面は層 (映像の枠。映像は枠いっぱいに伸ばして出している) いっぱいに縦横それぞれ伸ばす。
 * **描くのは替わったときだけ** (1枚が替わった・字が届いた・点滅の切り替わり)。Paint や外字の絵は使い回し、描くたびに作らない
 */
@Composable
internal fun TextCaptionLayer(page: CaptionPage, font: CaptionFont, inset: () -> Float) {
    val painter = remember { TextCaptionPainter() }
    /** 点滅で消えている間か */
    var dark by remember { mutableStateOf(false) }
    LaunchedEffect(page) {
        dark = false
        if (!page.flashes) return@LaunchedEffect
        // 点滅がある間だけ、切り替わりの時刻に合わせて起きる
        while (true) {
            val now = SystemClock.uptimeMillis()
            dark = (now / CAPTION_FLASH_MS) % 2 == 1L
            delay(CAPTION_FLASH_MS - now % CAPTION_FLASH_MS)
        }
    }
    val modifier = Modifier
        .fillMaxSize()
        // 下に重ねたもの (帯・メニュー) があれば、字のある行がその上に来るまで持ち上げる
        .liftCaptions(inset) { height -> page.top * height / page.planeHeight to page.bottom * height / page.planeHeight }
        // 読み上げ (と smoke が出たかを見るの) に、字幕の文
        .semantics { contentDescription = page.text }
    androidx.compose.foundation.Canvas(modifier) {
        val typeface = font.typeface
        val off = dark
        drawIntoCanvas { painter.draw(it.nativeCanvas, page, size.width, size.height, typeface, off) }
    }
}

/** 1枚を android の Canvas に描く係。画面ごとに1つ持って使い回す */
internal class TextCaptionPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    /** 外字の絵 (濃さだけの絵) を、この色で塗る */
    private val drcsPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = Rect()
    private val target = RectF()

    /** 「永」の墨の上下 (字の大きさ 1 あたり) を測った字。字が届いたら測り直す */
    private var measuredFor: Typeface? = null
    private var measured = false
    private var ascent = 0f
    private var descent = 0f

    /** 外字の絵。1枚が替わったら作り直す */
    private var drcsPage: CaptionPage? = null
    private val drcsBitmaps = HashMap<String, Bitmap>()

    fun draw(canvas: Canvas, page: CaptionPage, width: Float, height: Float, typeface: Typeface?, dark: Boolean) {
        if (page.runs.isEmpty() || width <= 0f || height <= 0f) return
        measure(typeface)
        if (drcsPage !== page) {
            drcsPage = page
            drcsBitmaps.clear()
        }
        val sx = width / page.planeWidth
        val sy = height / page.planeHeight
        for (run in page.runs) drawRun(canvas, page, run, sx, sy, dark)
    }

    private fun measure(typeface: Typeface?) {
        if (measured && measuredFor === typeface) return
        val face = typeface ?: Typeface.DEFAULT
        glyph.typeface = face
        edge.typeface = face
        glyph.textSize = INK_SIZE
        glyph.getTextBounds(INK, 0, INK.length, bounds)
        ascent = -bounds.top / INK_SIZE
        descent = bounds.bottom / INK_SIZE
        measuredFor = typeface
        measured = true
    }

    private fun drawRun(canvas: Canvas, page: CaptionPage, run: CaptionRun, sx: Float, sy: Float, dark: Boolean) {
        val fontPx = run.size * sy
        val glyphW = run.size * run.scaleX * sx
        val strokePx = CAPTION_STROKE_WIDTH * sx
        // 「永」の墨を字の枠の高さの真ん中に置いたときの基準線 (枠の上から)
        val baseline = captionBaseline(fontPx, ascent, descent)
        glyph.textSize = fontPx
        edge.textSize = fontPx
        val top = run.y * sy
        val bottom = (run.y + run.h) * sy
        // 塗る枠だけ画素の境目にそろえる。半端な位置のまま塗ると、隣の字との境の1画素が両方から
        // 半分ずつしか塗られず、半透明の背景に縦の筋が出ていた。隣どうしは同じ値に丸まる。
        // 字の位置は丸めない (字間が不揃いになる)
        val t = round(top)
        val b = round(bottom)
        for (i in 0 until run.count) {
            val left = (run.x + i * run.w) * sx
            val right = (run.x + (i + 1) * run.w) * sx
            val l = round(left)
            val r = round(right)
            if (run.bg ushr 24 != 0) {
                fill.color = run.bg
                canvas.drawRect(l, t, r, b, fill)
            }
            if (run.box != 0) {
                fill.color = run.fg
                val w = max(1f, floor(sx))
                val h = max(1f, floor(sy))
                if (run.box and 4 != 0) canvas.drawRect(l, t, r, t + h, fill)
                if (run.box and 1 != 0) canvas.drawRect(l, b - h, r, b, fill)
                if (run.box and 8 != 0) canvas.drawRect(l, t, l + w, b, fill)
                if (run.box and 2 != 0) canvas.drawRect(r - w, t, r, b, fill)
            }
            if (run.flash && dark) continue
            val x = left + run.fx * sx
            val y = top + run.fy * sy
            if (run.drcs != null) {
                val bitmap = drcsBitmap(page, run.drcs) ?: continue
                if (run.stroke != null) {
                    drcsPaint.color = run.stroke
                    drawDrcs(canvas, bitmap, x - strokePx, y, glyphW, fontPx)
                    drawDrcs(canvas, bitmap, x + strokePx, y, glyphW, fontPx)
                    drawDrcs(canvas, bitmap, x, y - strokePx, glyphW, fontPx)
                    drawDrcs(canvas, bitmap, x, y + strokePx, glyphW, fontPx)
                }
                drcsPaint.color = run.fg
                drawDrcs(canvas, bitmap, x, y, glyphW, fontPx)
                continue
            }
            if (run.spaces[i]) continue
            if (run.underline) {
                fill.color = run.fg
                val thick = max(1f, fontPx * 0.05f)
                val at = y + baseline + fontPx * 0.135f
                canvas.drawRect(left, at, right, at + thick, fill)
            }
            val start = run.bounds[i]
            val end = run.bounds[i + 1]
            // 横は字の枠の幅に合わせて縮める (FreeType に幅と高さを別々に渡していたのと同じ)
            canvas.withScale(glyphW / fontPx, 1f, x, y + baseline) {
                if (run.stroke != null) {
                    // 縦の太さを合わせる (横に縮めた字では横が少し細くなる。ブラウザと同じ)
                    edge.strokeWidth = strokePx * 2
                    edge.color = run.stroke
                    drawText(run.text, start, end, x, y + baseline, edge)
                }
                glyph.color = run.fg
                drawText(run.text, start, end, x, y + baseline, glyph)
            }
        }
    }

    private fun drawDrcs(canvas: Canvas, bitmap: Bitmap, x: Float, y: Float, w: Float, h: Float) {
        target.set(x, y, x + w, y + h)
        canvas.drawBitmap(bitmap, null, target, drcsPaint)
    }

    /** 外字の絵を濃さだけの絵 (ALPHA_8) にする。塗るときの Paint の色で出る */
    private fun drcsBitmap(page: CaptionPage, key: String): Bitmap? {
        drcsBitmaps[key]?.let { return it }
        val drcs = page.drcs[key] ?: return null
        return alphaBitmap(drcs).also { drcsBitmaps[key] = it }
    }

    private companion object {
        const val INK = "永"
        const val INK_SIZE = 100f

        fun alphaBitmap(drcs: CaptionDrcs): Bitmap {
            val bitmap = createBitmap(drcs.width, drcs.height, Bitmap.Config.ALPHA_8)
            // 行の頭は揃え直されることがある (rowBytes は幅より広いことがある)
            val row = bitmap.rowBytes
            val bytes = ByteArray(row * drcs.height)
            for (y in 0 until drcs.height) System.arraycopy(drcs.alpha, y * drcs.width, bytes, y * row, drcs.width)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            return bitmap
        }
    }
}
