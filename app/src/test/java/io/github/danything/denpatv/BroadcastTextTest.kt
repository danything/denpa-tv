package io.github.danything.denpatv

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import io.github.danything.denpatv.ui.appendBroadcast
import io.github.danything.denpatv.ui.broadcastPrefix
import io.github.danything.denpatv.ui.splitLines
import io.github.danything.denpatv.ui.withFont
import io.github.danything.denpatv.ui.withNotice
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
    fun 放送の字だけ全角の英数字と記号を詰める() {
        val text = buildAnnotatedString {
            append("011  ")
            appendBroadcast("ＮＨＫ総合１")
            append("  高画質")
        }.withFont(font)
        // 字体を当てたところにだけ palt が付き、こちらの字 (チャンネル番号・画質) には付かない。字そのもの (全角) は替えない
        assertEquals(listOf("ＮＨＫ総合１" to "palt"), text.spanStyles.map { text.text.substring(it.start, it.end) to it.item.fontFeatureSettings })
        assertEquals("011  ＮＨＫ総合１  高画質", text.text)
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

    /** 帯を出している間の知らせは、題の2行目の後ろに (1行の題なら2行目に)。何行かの知らせは1行に繋ぐ。放送の字の印は残る */
    @Test
    fun 帯の題の2行目に知らせを添える() {
        val title = buildAnnotatedString {
            appendBroadcast("番組")
            append("\n0:01:00 / 0:30:00")
        }
        val shown = withNotice(title, "CM 飛ばし 入")
        assertEquals(listOf("番組", "0:01:00 / 0:30:00   CM 飛ばし 入"), shown.splitLines().map { it.text })
        assertEquals(listOf(listOf("番組"), emptyList()), broadcastParts(shown))
        // 知らせのところだけ色を変える
        assertEquals(listOf("CM 飛ばし 入"), shown.spanStyles.map { shown.text.substring(it.start, it.end) })
        // 1行の題
        assertEquals(listOf("最後まで観ました", "CM を飛ばしました"), withNotice("最後まで観ました", "CM を飛ばしました").splitLines().map { it.text })
        // 何行かの知らせ・3行目のある題
        assertEquals(
            listOf("一時停止", "0:01:00   局   番組", "手引き"),
            withNotice("一時停止\n0:01:00\n手引き", "局\n番組").splitLines().map { it.text },
        )
        // 知らせが無ければ題のまま
        assertEquals(title, withNotice(title, null))
        assertEquals(title, withNotice(title, ""))
    }
}
