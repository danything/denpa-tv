package io.github.danything.denpatv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.Text
import io.github.danything.denpatv.data.ChapterMark
import kotlinx.coroutines.delay

/** 操作の帯の押すもの1つ。`on` は入っているか (塗って出す)。`icon` は札の頭の印 (drawable) */
data class Control(
    val label: String,
    val on: Boolean = false,
    val icon: Int? = null,
    /** 開いたときにここに合わせる (無ければ、入っているものの1つ目) */
    val initial: Boolean = false,
    /** 読み上げの名前。札を短くしたもの (「前へ」) だけ、略さない名前 (「前のチャプター」) を付ける。null なら `label` */
    val description: String? = null,
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
    /** 操作の列から下キーで合わせる先 (ライブの局の列の、いま映している局)。null なら近いものへ */
    down: FocusRequester? = null,
    /** 操作の列の下に足すもの (ライブの局の列) */
    below: (@Composable ColumnScope.() -> Unit)? = null,
    /**
     * 操作の列で押した上キーで閉じる (`closeOnUp`)。**操作の列がいちばん上の列のときだけ渡す** (ライブのメニュー)。
     * 上に合わせられるシークバーがある帯 (録画・追っかけ) では、上キーはシークバーへ上がるので渡さない (シークバーの `onUp` で閉じる)
     */
    onUp: (() -> Unit)? = null,
    /** 題の左に置くもの (ライブの局ロゴ) */
    leading: (@Composable () -> Unit)? = null,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focusActions) runCatching { first.requestFocus() } }
    /*
     * 合わせるものがある帯では、帯の中に合いを持ち続ける。合っていた札が消えた (「最新」に追いついた)・戻るの取り合いで
     * 合いが外れた、のどれでも、帯の中に合いが無ければ操作の列に戻す — 開いたまま合いがどこにも無いとリモコンが効かなくなる
     */
    val holdFocus = groups.any { it.second.isNotEmpty() }
    /** 帯の中 (札・シークバー・局の列) に合いがあるか */
    var inside by remember { mutableStateOf(false) }
    LaunchedEffect(holdFocus) {
        if (!holdFocus) return@LaunchedEffect
        while (true) {
            delay(HOLD_FOCUS_MS)
            if (!inside) runCatching { first.requestFocus() }
        }
    }
    BottomPanel(
        Modifier
            .onFocusChanged { inside = it.hasFocus }
            .onPreviewKeyEvent { onActivity(); false }
            // 決定の長押しで開くので、押し続けている決定の続きと離しで札が押されないように (押し直したら効く)
            .ignoreHeldCenter(),
        spacing = 6.dp,
    ) {
        if (leading == null) {
            TitleLines(title)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                leading()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { TitleLines(title) }
            }
        }
        header?.invoke(this, first)
        /** 開いたときに合わせる札を、もう決めたか (組み直すたびに数え直す) */
        var focused = false
        /*
         * **1080p・720p (どちらも 960dp 幅) で1行に収める。** 札は短く (「前へ」「次へ」)、組の間も詰める。
         * それでもはみ出したら (音声の名前が長い・「もう一度押すと削除」・文字を大きくしたテレビ)、合わせたものが
         * 見えるところまで横に送る
         */
        Row(
            // 合わせた札は少し膨らむ (1.1 倍) ので、横に送る枠で切れないよう両脇を空けておく (左の端は帯の端に揃える)
            Modifier
                .offset(x = (-10).dp)
                .closeOnUp(onUp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(GROUP_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 空の組 (字幕も音声も無いときの組など) は並べない。間が空きすぎる
            groups.filter { it.second.isNotEmpty() }.forEach { (name, controls) ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (name.isNotEmpty()) Text(name, style = MaterialTheme.typography.labelSmall, color = Color(0xFFD0D0D0))
                    controls.forEach { control ->
                        // `initial` のもの、無ければ入っているものの1つ目に合わせる (それも無ければいちばん最初)
                        val take = !focused && when {
                            groups.any { (_, all) -> all.any { it.initial } } -> control.initial
                            else -> control.on || controls.none { it.on }
                        }
                        if (take) focused = true
                        val moves = down?.let { target -> Modifier.focusProperties { this.down = target } } ?: Modifier
                        Chip(control, if (take) moves.focusRequester(first) else moves)
                    }
                }
            }
        }
        below?.invoke(this)
    }
}

/** 帯の中に合いがあるか見張る間 (ミリ秒) */
private const val HOLD_FOCUS_MS = 300L

/** 組 (再生・速さ・CM 飛ばし・字幕と音声・削除) の間。札の間 (6dp) より広く、区切りが分かるだけ */
private val GROUP_GAP = 14.dp

/** 帯の上の透かしの高さ。ここは映像が透けて見えるだけなので、字幕が乗ってもよい (`rememberCoverReport`) */
val SCRIM_TOP = 48.dp

/**
 * 下の端に重ねる板 (操作の帯・知らせ)。下から薄く暗くし、**高さを字幕に知らせる** (`PlayerFrame` の中なら、字幕がこの上へ逃げる。
 * `rememberCoverReport`)。下の端に何か出すときは、これを使う (知らせ忘れると字幕が隠れる)
 */
@Composable
fun BoxScope.BottomPanel(modifier: Modifier = Modifier, spacing: Dp, content: @Composable ColumnScope.() -> Unit) {
    val cover = rememberCoverReport(skipTop = SCRIM_TOP)
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .background(SCRIM)
            .onSizeChanged(cover)
            .then(modifier)
            .padding(start = 48.dp, end = 48.dp, top = SCRIM_TOP, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** 板の題。1行目は白く、2行目からは小さく (番組名・位置など) */
@Composable
fun TitleLines(text: String) {
    text.lines().forEachIndexed { index, line ->
        Text(
            line,
            style = if (index == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
            color = if (index == 0) Color.White else Color(0xFFD0D0D0),
        )
    }
}

/** 下から薄く暗くする (映像の上でも文字が読めるだけ。上は透かす) */
val SCRIM = Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000), Color(0xCC000000)))

/** 印つきの小さな札。入っているものは塗る */
@Composable
private fun Chip(control: Control, modifier: Modifier) {
    val padding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    val content: @Composable RowScope.() -> Unit = {
        control.icon?.let {
            Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        // 1行のまま中身に合わせて伸ばす (「もう一度押すと削除」のように押すと長くなる札が切れないように)
        Text(control.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
    }
    // 入れ切りで部品を替えない (Button と OutlinedButton を替えると、押した札から合いが外れて列の頭に飛ぶ・
    // どこにも合わなくなる)。色だけ変える。入っているものは azure で塗り、合わせたら白く (字は入っていれば azure)
    val colors = OutlinedButtonDefaults.colors(
        containerColor = if (control.on) Palette.Accent else Color(0x33000000),
        contentColor = Color.White,
        focusedContainerColor = Color.White,
        focusedContentColor = if (control.on) Palette.Accent else Palette.Background,
        pressedContainerColor = Palette.Text,
        pressedContentColor = Palette.Background,
    )
    val border = OutlinedButtonDefaults.border(
        border = Border(BorderStroke(1.dp, if (control.on) Palette.Accent else Color(0x99FFFFFF)), shape = CircleShape),
        focusedBorder = Focus.border(CircleShape, 2f),
        pressedBorder = Focus.border(CircleShape, 2f),
    )
    val named = control.description?.let { name -> Modifier.semantics { contentDescription = name } } ?: Modifier
    OutlinedButton(
        onClick = control.onClick,
        modifier = modifier.heightIn(min = 32.dp).wrapContentWidth(unbounded = true).then(named),
        contentPadding = padding,
        colors = colors,
        border = border,
        glow = OutlinedButtonDefaults.glow(focusedGlow = Focus.glow),
        content = content,
    )
}

/**
 * 進み具合の帯。`chapters` の CM は色を変えて出す。`onStep` を渡すと合わせられるシークバーになり、
 * 合っている間は左右で `onStep(-1 / 1)` (呼ぶ側が 10 秒ずつ動かす)。下で操作の列へ、上で `onUp` (帯を閉じる。`closeOnUp`)
 */
@Composable
fun ProgressLine(
    position: Long,
    duration: Long,
    chapters: List<ChapterMark> = emptyList(),
    focus: FocusRequester? = null,
    /** 下で合わせる先 (操作の列の最初。近いものに合うと、真ん中の札に飛んでしまう) */
    down: FocusRequester? = null,
    /** シークバーで押した上キーで閉じる (帯のいちばん上の列なので) */
    onUp: (() -> Unit)? = null,
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
            .closeOnUp(onUp)
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
