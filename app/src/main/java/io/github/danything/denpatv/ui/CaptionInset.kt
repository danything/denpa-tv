package io.github.danything.denpatv.ui

import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import kotlinx.coroutines.flow.collectLatest
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import io.github.danything.denpatv.data.captionLift
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * **字幕を、下に重ねたものの上へ逃がす。** 操作の帯・ライブのメニュー・知らせ (下の端に出すもの) が自分の高さを知らせ
 * (`rememberCoverReport`)、字幕の層 (Media3 の `SubtitleView`・生の TS の `RawCaptionLayer`) はいちばん高いものの上へ
 * 持ち上がる (`liftCaptions`)。決め方は `captionLift`。`PlayerFrame` が1つ持って、上に重ねるものへ渡す (`LocalOverlayInsets`)
 */
@Stable
class OverlayInsets {
    private val heights = mutableStateMapOf<Any, Int>()

    /** 下に重ねているもののうち、いちばん高いもの (px)。何も出ていなければ 0 */
    val bottom: Int get() = heights.values.maxOrNull() ?: 0

    internal fun report(key: Any, px: Int) {
        if (heights[key] != px) heights[key] = px
    }

    internal fun remove(key: Any) {
        heights.remove(key)
    }
}

/** `PlayerFrame` の中で上に重ねるものが、高さを知らせる先。`PlayerFrame` の外では null (知らせない) */
val LocalOverlayInsets = staticCompositionLocalOf<OverlayInsets?> { null }

/**
 * 下の端に重ねるもの (`BottomPanel`) の大きさを受けて、字幕を逃がす高さとして知らせる (`Modifier.onSizeChanged` に渡す)。
 * `skipTop` は上の透かし (`SCRIM` の上の空き) — 暗くしはじめたばかりで映像がほぼ透けて見えるので、字幕が乗ってもよい
 * (字幕は自分の縁取り・下地を持っている)。消えたら (画面から外れたら) 知らせを下ろす
 */
@Composable
internal fun rememberCoverReport(skipTop: Dp): (IntSize) -> Unit {
    val insets = LocalOverlayInsets.current
    val key = remember { Any() }
    val skip = with(LocalDensity.current) { skipTop.roundToPx() }
    DisposableEffect(insets) { onDispose { insets?.remove(key) } }
    return remember(insets, skip) { { size: IntSize -> insets?.report(key, (size.height - skip).coerceAtLeast(0)) } }
}

/**
 * 字幕の層が読む、下に重ねたものの高さ (px)。**出したときは同じこまで上へ** (重なるこまを作らない)、**閉じたときは滑らかに下ろす**。
 * 読むのは配置の段 (`liftCaptions`) だけなので、帯を開け閉めしても組み直さない
 */
@Composable
fun rememberCaptionInset(insets: OverlayInsets): () -> Float {
    val easing = remember { Animatable(0f) }
    LaunchedEffect(insets) {
        // 下ろしている間に高さが変わったら、その高さへ向け直す (前の動きを待たない)
        snapshotFlow { insets.bottom.toFloat() }.collectLatest { target ->
            if (target >= easing.value) easing.snapTo(target) else easing.animateTo(target, tween(CAPTION_EASE_MS))
        }
    }
    return remember(insets) { { maxOf(insets.bottom.toFloat(), easing.value) } }
}

/** 字幕を下ろすのにかける時間 (ミリ秒) */
private const val CAPTION_EASE_MS = 250

/** 字幕と重ねたものの間 */
private val CAPTION_GAP = 12.dp

/**
 * 字幕の層を、下に重ねたもの (`inset`、px) の上へ持ち上げる。`span(width, height)` は層の大きさに対する字幕の上の端と下の端 (px)。
 * 字幕が無ければ null。どちらもこま (配置) のたびに読むので、帯の開け閉め・字幕の替わりで組み直さない
 */
fun Modifier.liftCaptions(inset: () -> Float, span: (Int, Int) -> Pair<Float, Float>?): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        val lift = span(placeable.width, placeable.height)?.let { (top, bottom) ->
            captionLift(placeable.height.toFloat(), inset(), top, bottom, CAPTION_GAP.toPx())
        } ?: 0f
        placeable.place(0, -lift.roundToInt())
    }
}

/**
 * Media3 の字幕 (`SubtitleView` に渡すもの) の、層の上からの上の端と下の端 (px)。**`SubtitleView` (SubtitlePainter) の置き方を
 * なぞる** — 絵の字幕 (焼いたものの PGS) は置き場所と大きさがそのまま入っている。文字の字幕は高さが描くまで分からないので、
 * 字の大きさと行の数 (折り返しも) で見積もる。
 *
 * `SubtitleView` に下の余白 (padding) を付けて逃がさないのは、**絵の字幕は余白を除いた高さに合わせて縮むから** (PGS の高さは
 * 層の高さに対する割合で入っている)。層ごと持ち上げれば、絵の字幕も大きさを変えずに動く
 */
@OptIn(UnstableApi::class)
fun cueSpan(cues: List<Cue>, width: Int, height: Int): Pair<Float, Float>? {
    if (cues.isEmpty() || height <= 0) return null
    val spans = cues.map { cue -> cueSpan(cue, width.toFloat(), height.toFloat()) }
    return spans.minOf { it.first } to spans.maxOf { it.second }
}

@OptIn(UnstableApi::class)
private fun cueSpan(cue: Cue, width: Float, height: Float): Pair<Float, Float> {
    val bitmap = cue.bitmap
    // 文字の1行の高さ。大きさの指定があればそれ (SubtitleView は既定で入っている大きさも使う)、無ければ既定の大きさ
    val textSize = when (cue.textSizeType) {
        Cue.TEXT_SIZE_TYPE_ABSOLUTE -> cue.textSize
        Cue.TEXT_SIZE_TYPE_FRACTIONAL, Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING -> cue.textSize * height
        else -> height * SubtitleView.DEFAULT_TEXT_SIZE_FRACTION
    }.takeIf { it > 0f }.let { it ?: (height * SubtitleView.DEFAULT_TEXT_SIZE_FRACTION) } * relativeSize(cue.text)
    val row = textSize * LINE_SPACING
    val tall = when {
        cue.bitmapHeight != Cue.DIMEN_UNSET -> height * cue.bitmapHeight
        bitmap != null && bitmap.width > 0 && cue.size != Cue.DIMEN_UNSET -> width * cue.size * bitmap.height / bitmap.width
        bitmap != null -> row
        // SubtitlePainter は字の大きさの 1/4 を左右の余白に取る
        else -> textLines(cue.text, textSize, width * (if (cue.size != Cue.DIMEN_UNSET) cue.size else 1f) - textSize * 0.25f) * row
    }
    val line = cue.line
    val top = when {
        // 置き場所の無い字幕は、下の端から少し上 (SubtitleView の bottomPaddingFraction) に下揃え
        line == Cue.DIMEN_UNSET -> height * (1 - SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION) - tall
        cue.lineType == Cue.LINE_TYPE_NUMBER -> if (line >= 0) line * row else height + (line + 1) * row - tall
        else -> {
            val anchor = height * line
            when (cue.lineAnchor) {
                Cue.ANCHOR_TYPE_END -> anchor - tall
                Cue.ANCHOR_TYPE_MIDDLE -> anchor - tall / 2
                else -> anchor
            }
        }
    }
    return top to top + tall
}

/**
 * 文字の字幕が何行になるか。改行に加えて、**幅に収まらず折り返すぶんも数える** (字の幅は大きさと同じとみなす。日本語の全角)。
 * 多めに見積もるほうが安全 (下揃えなら下の端は変わらず、上揃えなら下の端が下に出て、よけいに持ち上げるだけ)
 */
private fun textLines(text: CharSequence?, textSize: Float, width: Float): Int {
    if (text.isNullOrEmpty()) return 1
    val perLine = (width / textSize).toInt().coerceAtLeast(1)
    return text.split('\n').sumOf { ceil(it.length.toFloat() / perLine).toInt().coerceAtLeast(1) }
}

/**
 * 字幕の中で字を大きくしている (TTML・WebVTT の字の大きさ → `RelativeSizeSpan`) ときの、いちばん大きい倍率。
 * 1 より小さくはしない (多めに見積もる)
 */
private fun relativeSize(text: CharSequence?): Float {
    if (text !is Spanned) return 1f
    val spans = text.getSpans(0, text.length, RelativeSizeSpan::class.java)
    return spans.maxOfOrNull { it.sizeChange }?.coerceAtLeast(1f) ?: 1f
}

/** 字の大きさに対する行の高さの見積もり */
private const val LINE_SPACING = 1.3f
