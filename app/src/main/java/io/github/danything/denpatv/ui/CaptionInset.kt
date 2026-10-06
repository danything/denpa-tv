package io.github.danything.denpatv.ui

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import io.github.danything.denpatv.data.captionLift
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
 * 下の端に重ねるもの (`ControlBar`・知らせ) の大きさを受けて、字幕を逃がす高さとして知らせる (`Modifier.onSizeChanged` に渡す)。
 * `skipTop` は上の透かし (`SCRIM` の上の空き) — そこは映像が透けて見えるだけなので、字幕が乗ってもよい。
 * 消えたら (画面から外れたら) 知らせを下ろす
 */
@Composable
fun rememberCoverReport(skipTop: Dp): (IntSize) -> Unit {
    val insets = LocalOverlayInsets.current
    val key = remember { Any() }
    val skip = with(LocalDensity.current) { skipTop.roundToPx() }
    DisposableEffect(insets) { onDispose { insets?.remove(key) } }
    return remember(insets, skip) { { size: IntSize -> insets?.report(key, (size.height - skip).coerceAtLeast(0)) } }
}

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
 * 既定の字の大きさ (層の高さの 5.33%) の行の数で見積もる
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
    val tall = when {
        cue.bitmapHeight != Cue.DIMEN_UNSET -> height * cue.bitmapHeight
        bitmap != null && bitmap.width > 0 && cue.size != Cue.DIMEN_UNSET -> width * cue.size * bitmap.height / bitmap.width
        else -> {
            val lines = (cue.text?.count { it == '\n' } ?: 0) + 1
            lines * height * TEXT_SIZE_FRACTION * LINE_SPACING
        }
    }
    val line = cue.line
    val top = when {
        // 置き場所の無い文字の字幕は、下の端から少し上 (SubtitleView の bottomPaddingFraction) に下揃え
        line == Cue.DIMEN_UNSET -> height * (1 - BOTTOM_PADDING_FRACTION) - tall
        cue.lineType == Cue.LINE_TYPE_NUMBER -> {
            val row = height * TEXT_SIZE_FRACTION * LINE_SPACING
            if (line >= 0) line * row else height + (line + 1) * row - tall
        }
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

/** `SubtitleView` の既定の字の大きさ (層の高さに対して。`SubtitleView.DEFAULT_TEXT_SIZE_FRACTION`) */
private const val TEXT_SIZE_FRACTION = 0.0533f

/** 字の大きさに対する行の高さの見積もり */
private const val LINE_SPACING = 1.3f

/** `SubtitleView` の既定の下の空き (`SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION`) */
private const val BOTTOM_PADDING_FRACTION = 0.08f
