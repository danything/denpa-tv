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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
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

/** 操作バーの押すもの1つ。`on` はオンか (塗って出す)。`icon` はボタンの頭の印 (drawable) */
data class Control(
    val label: String,
    val on: Boolean = false,
    val icon: Int? = null,
    /** 開いたときにここに合わせる (無ければ、オンのものの1つ目) */
    val initial: Boolean = false,
    /** 読み上げの名前。ボタンを短くしたもの (「前へ」) だけ、略さない名前 (「前のチャプター」) を付ける。null なら `label` */
    val description: String? = null,
    val onClick: () -> Unit,
)

/**
 * 映像の下に出す操作バー。**ブラウザの denpa の再生の操作列にあたるもの** (画質・速さ・CM 飛ばしなど)。
 * 設定の画面に置かないのは、ブラウザと同じく「観ながら変えて、その端末で覚える」ものだから。
 *
 * **映像を隠さないよう、下の端に小さく。** 重い板ではなく下から薄く暗くし (上と真ん中は空けたまま)、
 * 文字は離れて読める大きさのまま小さめ、操作は印つきの小さなボタンにする。
 *
 * 上から: 題 (2行目からは小さく。番組名・位置など)、`header` (録画のシークバー・ライブの番組の進み)、操作の列。
 * `focusActions` なら開いたときに操作の列の最初の (オンの) ものに合わせる。そうでなければ `header` が自分で合わせる。
 * 戻るで閉じる (呼ぶ側)。キーが来るたびに `onActivity` (しばらく触らなければ閉じるため)
 */
@Composable
fun BoxScope.ControlBar(
    title: CharSequence,
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
     * 上に合わせられるシークバーがある操作バー (録画・追っかけ) では、上キーはシークバーへ上がるので渡さない (シークバーの `onUp` で閉じる)
     */
    onUp: (() -> Unit)? = null,
    /** 題の左に置くもの (ライブの局ロゴ) */
    leading: (@Composable () -> Unit)? = null,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focusActions) runCatching { first.requestFocus() } }
    /*
     * 合わせるものがある操作バーでは、操作バーの中に合いを持ち続ける。合っていたボタンが消えた (「最新」に追いついた)・戻るの取り合いで
     * 合いが外れた、のどれでも、操作バーの中に合いが無ければ操作の列に戻す — 開いたまま合いがどこにも無いとリモコンが効かなくなる
     */
    val holdFocus = groups.any { it.second.isNotEmpty() }
    /** 操作バーの中 (ボタン・シークバー・局の列) に合いがあるか */
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
            // 決定の長押しで開くので、押し続けている決定の続きと離しでボタンが押されないように (押し直したら効く)
            .ignoreHeldCenter(),
        spacing = 6.dp,
    ) {
        // 操作バーを出している間の知らせ (操作バーの中で押したものの返事など) は、下の端に別に出さずに題の2行目へ (`PlayerFrame` の `covered`)
        val notice = LocalBarNotice.current
        if (leading == null) {
            TitleLines(title, notice)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                leading()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { TitleLines(title, notice) }
            }
        }
        header?.invoke(this, first)
        /** 開いたときに合わせるボタンを、もう決めたか (組み直すたびに数え直す) */
        var focused = false
        /*
         * **1080p・720p (どちらも 960dp 幅) で1行に収める。** ボタンは短く (「前へ」「次へ」)、組の間も詰める。
         * それでもはみ出したら (音声の名前が長い・「もう一度押すと削除」・文字を大きくしたテレビ)、合わせたものが
         * 見えるところまで横に送る
         */
        Row(
            // 合わせたボタンは少し膨らむ (1.1 倍) ので、横に送る枠で切れないよう両脇を空けておく (左の端は操作バーの端に揃える)
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
                    if (name.isNotEmpty()) Text(name, style = MaterialTheme.typography.labelSmall, color = Palette.OnScrimMuted)
                    controls.forEach { control ->
                        // `initial` のもの、無ければオンのものの1つ目に合わせる (それも無ければいちばん最初)
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

/** 印だけのボタン (丸) の大きさ。ほかのボタンの高さ (tv-material の Button の最小の高さ) と同じ */
private val ICON_CHIP = 40.dp

/** 操作バーの中に合いがあるか見張る間 (ミリ秒) */
private const val HOLD_FOCUS_MS = 300L

/** 組 (再生・速さ・CM 飛ばし・字幕と音声・削除) の間。ボタンの間 (6dp) より広く、区切りが分かるだけ */
private val GROUP_GAP = 14.dp

/** 操作バーの上の透かしの高さ。ここは映像が透けて見えるだけなので、字幕が乗ってもよい (`rememberCoverReport`) */
val SCRIM_TOP = 48.dp

/**
 * 下の端に重ねる板 (操作バー・知らせ)。下から薄く暗くし、**高さを字幕に知らせる** (`PlayerFrame` の中なら、字幕がこの上へ逃げる。
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
            .padding(start = SAFE_HORIZONTAL, end = SAFE_HORIZONTAL, top = SCRIM_TOP, bottom = PANEL_BOTTOM),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/**
 * 板の題。1行目は白く、2行目からは小さく (番組名・位置など)。
 * AnnotatedString なら、印 (`appendBroadcast`) の付いたところ (番組名・局名) を放送の字にする。
 * `notice` (操作バーを出している間の知らせ) は2行目の後ろに明るい azure で添える (`withNotice`)
 */
@Composable
fun TitleLines(text: CharSequence, notice: CharSequence? = null) {
    val styled = withNotice(text, notice).withBroadcastFont()
    styled.splitLines().forEachIndexed { index, line ->
        Text(
            line,
            style = if (index == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
            color = if (index == 0) Color.White else Palette.OnScrimMuted,
        )
    }
}

/**
 * 題の2行目の後ろに知らせを添える (操作バーを出している間の知らせ。`LocalBarNotice`)。題が1行なら知らせが2行目になる。
 * 知らせが何行かあれば1行に繋ぐ。印 (`appendBroadcast`) はそのまま残る
 */
internal fun withNotice(text: CharSequence, notice: CharSequence?): AnnotatedString {
    val title = text as? AnnotatedString ?: AnnotatedString(text.toString())
    val parts = (notice as? AnnotatedString ?: notice?.let { AnnotatedString(it.toString()) })
        ?.splitLines()?.filter { it.isNotBlank() }.orEmpty()
    if (parts.isEmpty()) return title
    val lines = title.splitLines()
    return buildAnnotatedString {
        append(lines[0])
        append("\n")
        if (lines.size > 1) {
            append(lines[1])
            append(NOTICE_GAP)
        }
        withStyle(SpanStyle(color = Palette.AccentBright)) {
            parts.forEachIndexed { index, part ->
                if (index > 0) append(NOTICE_GAP)
                append(part)
            }
        }
        lines.drop(2).forEach {
            append("\n")
            append(it)
        }
    }
}

/** 題の2行目と知らせの間 */
private const val NOTICE_GAP = "   "

/**
 * 下から暗くする (映像の上でも文字が読めるように)。上の透かし (`SCRIM_TOP`) を過ぎた題のあたりから、もう十分に暗く
 * (明るい場面でも題の白い字が沈まないように)。上の端だけ透かして映像に溶かす
 */
val SCRIM = Brush.verticalGradient(0f to Color.Transparent, 0.25f to Color(0xB3000000), 0.5f to Color(0xCC000000), 1f to Color(0xE0000000))

/**
 * テレビの縁で切れない所 (オーバースキャンの安全域。Android TV の指針の 5%: 横 48dp・縦 27dp)。
 * 下の端に出す板の横の余白と、下の余白 (合わせて膨らんだボタンの縁と光も入るよう、少し多めに)
 */
val SAFE_HORIZONTAL = 48.dp
val SAFE_VERTICAL = 27.dp
private val PANEL_BOTTOM = 32.dp

/**
 * 印つきの小さなボタン。オンのものは塗る。
 * **`label` が空なら印だけの丸** (等速の速さのボタン。`speedControl`)。読み上げの名前は `description` に書く
 */
@Composable
private fun Chip(control: Control, modifier: Modifier) {
    val iconOnly = control.label.isEmpty() && control.icon != null
    val padding = if (iconOnly) PaddingValues(0.dp) else PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    val content: @Composable RowScope.() -> Unit = {
        control.icon?.let {
            Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(18.dp))
            if (!iconOnly) Spacer(Modifier.width(6.dp))
        }
        // 1行のまま中身に合わせて伸ばす (「もう一度押すと削除」のように押すと長くなるボタンが切れないように)
        if (!iconOnly) Text(control.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
    }
    // オン・オフで部品を替えない (Button と OutlinedButton を替えると、押したボタンから合いが外れて列の頭に飛ぶ・
    // どこにも合わなくなる)。色だけ変える。オンのものは azure で塗り、合わせたら白く (字は入っていれば azure)
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
        // 印だけのボタンは縦横をそろえて丸くする (`ICON_CHIP`)。何もしないと Button の最小の幅 (58dp) で横長になる
        modifier = (if (iconOnly) modifier.size(ICON_CHIP) else modifier.heightIn(min = 32.dp).wrapContentWidth(unbounded = true))
            .then(named),
        contentPadding = padding,
        colors = colors,
        border = border,
        glow = OutlinedButtonDefaults.glow(focusedGlow = Focus.glow),
        content = content,
    )
}

/**
 * 進行バー。`chapters` の CM は琥珀で塗る (`Palette.Cm`)。`onStep` を渡すと合わせられるシークバーになり、
 * 合っている間は左右で `onStep(-1 / 1)` (呼ぶ側が 10 秒ずつ動かす)。下で操作の列へ、上で `onUp` (操作バーを閉じる。`closeOnUp`)。
 *
 * **合っているのが離れても分かるように**、合っている間はバーを太くし、位置に大きな白い丸を明るい azure の縁で置き、
 * 丸の上に位置 (1:02:03) を出す。位置の字のぶん、シークバーは上を空けておく (合わせてもバーの高さが変わらないように)
 */
@Composable
fun ProgressLine(
    position: Long,
    duration: Long,
    chapters: List<ChapterMark> = emptyList(),
    focus: FocusRequester? = null,
    /** 下で合わせる先 (操作の列の最初。近いものに合うと、真ん中のボタンに飛んでしまう) */
    down: FocusRequester? = null,
    /** シークバーで押した上キーで閉じる (操作バーのいちばん上の列なので) */
    onUp: (() -> Unit)? = null,
    onStep: ((Int) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val seekable = onStep != null && focus != null
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge.copy(color = Color.White)
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
            .height(if (seekable) LINE_HEIGHT + SEEK_LABEL_HEIGHT else LINE_HEIGHT)
            .drawBehind {
                val thick = (if (focused) 6.dp else 4.dp).toPx()
                // バーは下の端の `LINE_HEIGHT` の真ん中 (シークバーなら、上は位置の字を置く所)
                val middle = size.height - LINE_HEIGHT.toPx() / 2
                val top = middle - thick / 2
                val radius = CornerRadius(thick / 2)
                drawRoundRect(Color(0x66FFFFFF), Offset(0f, top), Size(size.width, thick), radius)
                if (duration > 0) {
                    fun x(ms: Long) = size.width * (ms.coerceIn(0, duration).toFloat() / duration)
                    chapters.filter { it.isCm }.forEach { cm ->
                        drawRect(Palette.Cm, Offset(x(cm.startMs), top), Size(x(cm.endMs) - x(cm.startMs), thick))
                    }
                    drawRoundRect(accent, Offset(0f, top), Size(x(position), thick), radius)
                    if (focused) {
                        val at = Offset(x(position), middle)
                        val knob = KNOB_RADIUS.toPx()
                        drawCircle(Palette.AccentBright, knob, at)
                        drawCircle(Color.White, knob - KNOB_RING.toPx(), at)
                        // 丸の上に位置。端ではバーの幅に収める
                        val label = measurer.measure(clockLabel(position), labelStyle)
                        val left = (at.x - label.size.width / 2f).coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f))
                        drawText(label, topLeft = Offset(left, middle - knob - KNOB_GAP.toPx() - label.size.height))
                    }
                }
            },
    )
}

/** 位置の字 (`position`。`ProgressLine` の引数と名前が重なるので分けておく) */
private fun clockLabel(ms: Long): String = position(ms)

/** 進行バーの行の高さと、シークバーの上に空けておく位置の字の高さ */
private val LINE_HEIGHT = 16.dp
private val SEEK_LABEL_HEIGHT = 26.dp

/** 合っているシークバーの丸 (半径)、その明るい azure の縁の太さ、丸と位置の字の間 */
private val KNOB_RADIUS = 10.dp
private val KNOB_RING = 3.dp
private val KNOB_GAP = 2.dp
