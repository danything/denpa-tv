package io.github.danything.denpatv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/**
 * アプリの色。**ブラウザの denpa (Pico の azure・ダーク) と同じ段から取る** (denpa の src/app.css)。
 * 色はここにだけ書き、画面ごとに色を書かない。
 *
 * - 地は slate-950、面 (カード・ボタン) は slate-850 / 800、線は slate-700
 * - 主の色は azure ひとつ。**塗る (主のボタン・オンのボタン・選んだ行き先)** のは濃い #0172AD (白い字が読める)、
 *   **光る (合わせた縁・進み・見出し)** のは明るい #01AAFF (暗い地で目に入る)
 * - 状態の色は録画中の赤と予約の琥珀だけ (ブラウザと同じく、塗るのは「いま動いているもの」だけ)
 */
object Palette {
    val Background = Color(0xFF13171F)
    val Surface = Color(0xFF202632)
    val SurfaceRaised = Color(0xFF2A3140)
    val Line = Color(0xFF525F7A)
    val Accent = Color(0xFF0172AD)
    val AccentBright = Color(0xFF01AAFF)
    val OnAccent = Color.White
    val Text = Color(0xFFE6E9EF)
    val TextMuted = Color(0xFFB0B9D0)
    val Recording = Color(0xFFD93526)
    val Reserved = Color(0xFFFECC63)
}

/** tv-material の色に当てる。部品の既定 (Surface の地・本文の色・進行バー・見出し) がこれで揃う */
private val Colors = darkColorScheme(
    primary = Palette.AccentBright,
    onPrimary = Palette.Background,
    primaryContainer = Palette.Accent,
    onPrimaryContainer = Palette.OnAccent,
    secondary = Palette.AccentBright,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Background,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Surface,
    onSurfaceVariant = Palette.TextMuted,
    inverseSurface = Palette.Text,
    inverseOnSurface = Palette.Background,
    border = Palette.AccentBright,
    borderVariant = Palette.Line,
    error = Palette.Recording,
)

/**
 * 字の大きさ。**離れて (2〜3 m) 読むので、tv-material の既定より小さいほうの段を上げる** (操作バーのタイトル 14→16sp・2行目 12→14sp、
 * ボタン 12→14sp、組の名前・局のカードの印 11→13sp)。大きいほう (見出し・本文) は既定のまま
 */
private val Type = Typography().let { base ->
    base.copy(
        titleSmall = base.titleSmall.copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodySmall = base.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = base.labelMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelSmall = base.labelSmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
    )
}

/**
 * アプリの字は端末の字のまま。**放送から来た字だけ** `broadcastFonts` (Denpa Font。`TextStyle.broadcast()` で当てる) で描く。
 * null (まだ読めていない・読めない) なら端末の字
 */
@Composable
fun DenpaTheme(broadcastFonts: BroadcastFonts?, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalBroadcastFont provides broadcastFonts) { MaterialTheme(colorScheme = Colors, typography = Type, content = content) }

/**
 * **合わせたものの見せ方 (アプリ全体で同じ)。** Google TV と同じく、合わせたものは少し膨らみ (カードは 1.05、ボタンは 1.1。
 * tv-material の既定の幅)、**azure の縁と淡い光** を付ける。ボタンは白く塗って字を地の色にする (離れても、どれに合っているか分かる)
 */
object Focus {
    /** カード (録画・局) の膨らみ。格子で隣と重ならない程度 */
    const val CARD_SCALE = 1.05f

    fun border(shape: Shape, width: Float = 3f) = Border(BorderStroke(width.dp, Palette.AccentBright), inset = 0.dp, shape = shape)

    /** 合わせたものの下の淡い光 (影の色を azure にしたもの。色の付いた影は Android 9 から。それより前は影だけ) */
    val glow = Glow(elevationColor = Palette.AccentBright.copy(alpha = 0.45f), elevation = 12.dp)
}

/**
 * 形。Android TV のデザインの指針 ([ボタン](https://developer.android.com/design/ui/tv/guides/components/buttons)) どおり、
 * **文字・印のボタンは丸いボタン (両端が半円)**、**幅いっぱいのボタン (設定の行) と絵のカードは角 12dp の四角**。
 * 押せないラベル (ジャンル・形・音声などの情報) は角の小さな四角にして、押せるボタンと見分ける (`TagShape`)
 */
val PillShape = RoundedCornerShape(percent = 50)
val CardShape = RoundedCornerShape(12.dp)
val TagShape = RoundedCornerShape(4.dp)

/**
 * ボタン。`primary` は主な操作・決める操作 (azure で塗る。「続きから再生」「録画」)、そうでなければ枠だけ
 * (並べる二の次の操作。「最初から」「閉じる」、戻せない「削除」も塗らない)。**合わせると白く塗り、azure の縁と光** (`Focus`)。
 * 入れ替えても合いが外れないよう、どちらも同じ部品 (OutlinedButton) の色だけ変える
 */
@Composable
fun DenpaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    contentPadding: PaddingValues = OutlinedButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = PillShape
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = OutlinedButtonDefaults.shape(shape),
        colors = OutlinedButtonDefaults.colors(
            containerColor = if (primary) Palette.Accent else Color.Transparent,
            contentColor = if (primary) Palette.OnAccent else Palette.Text,
            focusedContainerColor = Color.White,
            focusedContentColor = Palette.Background,
            pressedContainerColor = Palette.Text,
            pressedContentColor = Palette.Background,
        ),
        border = OutlinedButtonDefaults.border(
            border = Border(BorderStroke(1.5.dp, if (primary) Palette.Accent else Palette.Line), shape = shape),
            focusedBorder = Focus.border(shape, 2f),
            pressedBorder = Focus.border(shape, 2f),
        ),
        glow = OutlinedButtonDefaults.glow(focusedGlow = Focus.glow),
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * 幅いっぱいのボタン (設定の行)。角 12dp の四角に、見出しと、その下に小さく今の様子 (`supporting`)。
 * 合わせ方はボタンと同じ (白く塗り、azure の縁と光)。幅が広いので膨らみは小さく
 */
@Composable
fun WideButton(
    headline: String,
    supporting: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = CardShape
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            contentColor = Palette.Text,
            focusedContainerColor = Color.White,
            focusedContentColor = Palette.Background,
            pressedContainerColor = Palette.Text,
            pressedContentColor = Palette.Background,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Focus.border(shape, 2f)),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Focus.glow),
        scale = ClickableSurfaceDefaults.scale(focusedScale = WIDE_SCALE),
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(headline, style = MaterialTheme.typography.titleMedium)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.72f)) }
        }
    }
}

/** 幅いっぱいのボタンの膨らみ (幅が広いので、小さくても目に付く) */
private const val WIDE_SCALE = 1.02f
