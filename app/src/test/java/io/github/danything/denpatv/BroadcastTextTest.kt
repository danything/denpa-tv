package io.github.danything.denpatv

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import io.github.danything.denpatv.ui.appendBroadcast
import io.github.danything.denpatv.ui.broadcastPrefix
import io.github.danything.denpatv.ui.splitLines
import io.github.danything.denpatv.ui.withFont
import org.junit.Assert.assertEquals
import org.junit.Test

/** 帯の題で、放送から来た字 (`appendBroadcast`) だけを放送の字にし、行ごとに分けても字体がずれない */
class BroadcastTextTest {
    private val font = FontFamily.Monospace

    /** 行ごとに、放送の字になっているところ */
    private fun broadcastParts(text: AnnotatedString) =
        text.withFont(font).splitLines().map { line ->
            line.spanStyles.filter { it.item.fontFamily == font }.map { line.text.substring(it.start, it.end) }
        }

    @Test
    fun 局名と番組名だけが放送の字になる() {
        val text = buildAnnotatedString {
            append("011  ")
            appendBroadcast("ＮＨＫ総合１・東京")
            append("  高画質\n")
            appendBroadcast("ニュース７")
            append("  あと12分")
        }
        assertEquals(listOf(listOf("ＮＨＫ総合１・東京"), listOf("ニュース７")), broadcastParts(text))
        assertEquals(listOf("011  ＮＨＫ総合１・東京  高画質", "ニュース７  あと12分"), text.splitLines().map { it.text })
    }

    @Test
    fun 行をまたぐ印は行ごとに切る() {
        val text = buildAnnotatedString {
            append("一時停止  ")
            appendBroadcast("題\n副題")
            append("\n\n0:01:00")
        }
        assertEquals(listOf(listOf("題"), listOf("副題"), emptyList(), emptyList()), broadcastParts(text))
    }

    @Test
    fun 印が無ければそのまま() {
        val text = buildAnnotatedString { append("CM を飛ばしました") }
        assertEquals(text, text.withFont(font))
        assertEquals(listOf("CM を飛ばしました"), text.splitLines().map { it.text })
    }

    @Test
    fun 局と日時の行は頭の局名だけ() {
        assertEquals(listOf(listOf("ＢＳ日テレ")), broadcastParts(broadcastPrefix("ＢＳ日テレ ・ 10/6(火) 21:00", "ＢＳ日テレ")))
        // 局名が無い・頭に無ければ印を付けない
        assertEquals(listOf(emptyList<String>()), broadcastParts(broadcastPrefix("10/6(火) 21:00", null)))
        assertEquals(listOf(emptyList<String>()), broadcastParts(broadcastPrefix("番組表にいまの番組がありません", "ＮＨＫ")))
    }
}
