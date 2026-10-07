package io.github.danything.denpatv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import io.github.danything.denpatv.data.keepEpisode

/**
 * 番組名を `maxLines` 行まで。**収まらなければ話数を残して途中を切る** (`keepEpisode`)。
 *
 * 切りかたは幅が分かってから (組むときに) 決まるので、組むときに測って、出す字を替える (替わったら、その `Text` だけ組み直す。
 * 替わるのは収まらない題を初めて組んだときと、幅が替わったときだけ)。描くのは `Text` に任せる: 測ったものを自分で描くと、
 * API 28 で Native Heap が 4〜5MB ほど増えた (一覧を送ったあと。`Text` で描けば増えない)。
 * 読み上げ (と smoke) には、切っていない題を渡す
 */
@Composable
internal fun EpisodeTitle(
    title: String,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    // 字の大きさ・向き・字体が替わると、別の measurer になる (測り直す)
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val merged = LocalTextStyle.current.merge(style)
    val fitter = remember(title, merged, maxLines, measurer) { TitleFitter(title, merged, maxLines, measurer) }
    var shown by remember(fitter) { mutableStateOf(Shown(title, merged)) }
    Text(
        shown.text,
        style = shown.style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clearAndSetSemantics { text = AnnotatedString(title) }
            .layout { measurable, constraints ->
                fitter.fit(constraints.maxWidth, this)?.let { if (it != shown) shown = it }
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            },
    )
}

/** 出す字と、その組みかた */
private data class Shown(val text: String, val style: TextStyle)

/** 1つの題を、幅に合わせて切る (最後に測った幅と同じなら測り直さない) */
private class TitleFitter(val title: String, val style: TextStyle, val maxLines: Int, val measurer: TextMeasurer) {
    private var width = -1

    /** 収まらない題を詰めて組む字 (文節で折らない。行末の空きのぶん、頭を長く残せる) */
    private val dense = style.copy(lineBreak = LineBreak(LineBreak.Strategy.Simple, LineBreak.Strictness.Strict, LineBreak.WordBreak.Default))

    /** その幅で出すもの。前に測った幅と同じなら null (替えない) */
    fun fit(width: Int, density: Density): Shown? {
        if (width == this.width) return null
        this.width = width
        fun layout(text: String, style: TextStyle = dense, lines: Int = maxLines, overflow: TextOverflow = TextOverflow.Ellipsis) =
            measurer.measure(text, style, overflow = overflow, maxLines = lines, constraints = Constraints(maxWidth = width), density = density)
        // 収まるなら頼まれた折り方 (文節など) で
        if (!layout(title, style).hasVisualOverflow) return Shown(title, style)
        // 収まらなければ詰めて組み、末尾で切ったときに見える字と、最後の行で話数の前に残せる幅から、縮めた題を決める
        val clipped = layout(title, overflow = TextOverflow.Clip)
        val last = clipped.lineCount - 1
        val text = keepEpisode(
            title,
            visible = clipped.getLineEnd(last, visibleEnd = true),
            room = { tail ->
                val tailWidth = layout(tail, lines = 1).multiParagraph.getLineRight(0)
                val y = (clipped.getLineTop(last) + clipped.getLineBottom(last)) / 2
                clipped.getOffsetForPosition(Offset(width - tailWidth, y))
            },
            fits = { !layout(it).hasVisualOverflow },
        )
        return Shown(text, dense)
    }
}
