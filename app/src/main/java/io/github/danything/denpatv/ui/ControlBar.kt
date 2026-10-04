package io.github.danything.denpatv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.tv.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.Text
import io.github.danything.denpatv.data.ChapterMark

/** 操作の帯の押すもの1つ。`on` は入っているか (塗って出す)。`icon` は札の頭の印 (drawable) */
data class Control(
    val label: String,
    val on: Boolean = false,
    val icon: Int? = null,
    /** 開いたときにここに合わせる (無ければ、入っているものの1つ目) */
    val initial: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 映像の下に出す帯。**ブラウザの denpa の再生の操作列にあたるもの** (画質・速さ・CM 飛ばしなど)。
 * 設定の画面に置かないのは、ブラウザと同じく「観ながら変えて、その端末で覚える」ものだから。
 *
 * **映像を隠さないよう、下の端に小さく。** 重い板ではなく下から薄く暗くし (上と真ん中は空けたまま)、
 * 文字は離れて読める大きさのまま小さめ、操作は印つきの小さな札にする。
 *
 * 上から: 題 (2行目からは小さく。番組名・位置など)、`header` (録画のシークバー・ライブの番組の進み)、操作の列。
 * `focusActions` なら開いたときに操作の列の最初の (入っている) ものに合わせる。そうでなければ `header` が自分で合わせる。
 * 戻るで閉じる (呼ぶ側)。キーが来るたびに `onActivity` (しばらく触らなければ閉じるため)
 */
@Composable
fun BoxScope.ControlBar(
    title: String,
    groups: List<Pair<String, List<Control>>>,
    /** 引数は操作の列の最初のもの (シークバーから下で、そこへ行けるように) */
    header: (@Composable ColumnScope.(FocusRequester) -> Unit)? = null,
    focusActions: Boolean = true,
    onActivity: () -> Unit = {},
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focusActions) runCatching { first.requestFocus() } }
    var focused = false
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .background(SCRIM)
            .onPreviewKeyEvent { onActivity(); false }
            // 決定の長押しで開くので、押し続けている決定の続きと離しで札が押されないように (押し直したら効く)
            .ignoreHeldCenter()
            .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        title.lines().forEachIndexed { index, line ->
            Text(
                line,
                style = if (index == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                color = if (index == 0) Color.White else Color(0xFFD0D0D0),
            )
        }
        header?.invoke(this, first)
        // 狭い画面ではみ出したら、合わせたものが見えるところまで横に送る
        Row(
            Modifier.offset(x = (-6).dp).horizontalScroll(rememberScrollState()).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            groups.forEach { (name, controls) ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (name.isNotEmpty()) Text(name, style = MaterialTheme.typography.labelSmall, color = Color(0xFFD0D0D0))
                    controls.forEach { control ->
                        // `initial` のもの、無ければ入っているものの1つ目に合わせる (それも無ければいちばん最初)
                        val take = !focused && when {
                            groups.any { (_, all) -> all.any { it.initial } } -> control.initial
                            else -> control.on || controls.none { it.on }
                        }
                        if (take) focused = true
                        Chip(control, if (take) Modifier.focusRequester(first) else Modifier)
                    }
                }
            }
        }
    }
}

/** 下から薄く暗くする (映像の上でも文字が読めるだけ。上は透かす) */
val SCRIM = Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000), Color(0xCC000000)))

/** 印つきの小さな札。入っているものは塗る */
@Composable
private fun Chip(control: Control, modifier: Modifier) {
    val padding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    val content: @Composable RowScope.() -> Unit = {
        control.icon?.let {
            Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        // 1行のまま中身に合わせて伸ばす (「もう一度押すと削除」のように押すと長くなる札が切れないように)
        Text(control.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
    }
    // 入れ切りで部品を替えない (Button と OutlinedButton を替えると、押した札から合いが外れて列の頭に飛ぶ・
    // どこにも合わなくなる)。色だけ変える
    val colors = if (control.on) {
        OutlinedButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        )
    } else {
        OutlinedButtonDefaults.colors()
    }
    OutlinedButton(
        onClick = control.onClick,
        modifier = modifier.heightIn(min = 32.dp).wrapContentWidth(unbounded = true),
        contentPadding = padding,
        colors = colors,
        content = content,
    )
}

/**
 * 進み具合の帯。`chapters` の CM は色を変えて出す。`onStep` を渡すと合わせられるシークバーになり、
 * 合っている間は左右で `onStep(-1 / 1)` (呼ぶ側が 10 秒ずつ動かす)。下で操作の列へ
 */
@Composable
fun ProgressLine(
    position: Long,
    duration: Long,
    chapters: List<ChapterMark> = emptyList(),
    focus: FocusRequester? = null,
    /** 下で合わせる先 (操作の列の最初。近いものに合うと、真ん中の札に飛んでしまう) */
    down: FocusRequester? = null,
    onStep: ((Int) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val seekable = onStep != null && focus != null
    val modifier = if (seekable) {
        Modifier
            .focusRequester(focus!!)
            .onFocusChanged { focused = it.isFocused }
            .focusProperties { down?.let { this.down = it } }
            .onKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (it.key) {
                    Key.DirectionLeft -> { onStep!!(-1); true }
                    Key.DirectionRight -> { onStep!!(1); true }
                    else -> false
                }
            }
            .focusable()
    } else {
        Modifier
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(16.dp)
            .drawBehind {
                val thick = (if (focused) 6.dp else 4.dp).toPx()
                val top = (size.height - thick) / 2
                val radius = CornerRadius(thick / 2)
                drawRoundRect(Color(0x66FFFFFF), Offset(0f, top), Size(size.width, thick), radius)
                if (duration > 0) {
                    fun x(ms: Long) = size.width * (ms.coerceIn(0, duration).toFloat() / duration)
                    chapters.filter { it.isCm }.forEach { cm ->
                        drawRect(Color(0xCCFFB300), Offset(x(cm.startMs), top), Size(x(cm.endMs) - x(cm.startMs), thick))
                    }
                    drawRoundRect(accent, Offset(0f, top), Size(x(position), thick), radius)
                    if (focused) drawCircle(Color.White, size.height / 2, Offset(x(position), size.height / 2))
                }
            },
    )
}
