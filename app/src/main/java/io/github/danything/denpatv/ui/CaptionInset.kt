package io.github.danything.denpatv.ui

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
import kotlinx.coroutines.flow.collectLatest
import io.github.danything.denpatv.data.captionLift
import kotlin.math.roundToInt

/**
 * **字幕を、下に重ねたものの上へ逃がす。** 操作の帯・ライブのメニュー・知らせ (下の端に出すもの) が自分の高さを知らせ
 * (`rememberCoverReport`)、字幕の層 (`CaptionLayer`) はいちばん高いものの上へ
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
 * 字幕の層を、下に重ねたもの (`inset`、px) の上へ持ち上げる。`span(height)` は層の高さに対する字幕の上の端と下の端 (px)。
 * どちらもこま (配置) のたびに読むので、帯の開け閉め・字幕の替わりで組み直さない
 */
fun Modifier.liftCaptions(inset: () -> Float, span: (Int) -> Pair<Float, Float>): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        val (top, bottom) = span(placeable.height)
        val lift = captionLift(placeable.height.toFloat(), inset(), top, bottom, CAPTION_GAP.toPx())
        placeable.place(0, -lift.roundToInt())
    }
}
