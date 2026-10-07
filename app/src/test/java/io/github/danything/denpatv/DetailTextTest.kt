package io.github.danything.denpatv

import io.github.danything.denpatv.data.DetailKind
import io.github.danything.denpatv.data.DetailText
import io.github.danything.denpatv.data.arrangeDetail
import io.github.danything.denpatv.data.detailKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailTextTest {
    /** 実際の録画にあった見出しの揺れ */
    @Test
    fun 見出しの種類() {
        val cases = mapOf(
            DetailKind.Story to listOf("番組内容", "番組内容1", "番組内容2", "番組内容①", "番組内容②", "今回の番組内容", "あらすじ◇", "◇あらすじ", "【あらすじ】", "内容", "みどころ"),
            DetailKind.Cast to listOf("出演者", "声の出演", "【出演】", "ゲスト", "キャスト", "語り"),
            DetailKind.Credit to listOf("制作", "スタッフ", "スタッフ2", "音楽", "主題歌", "楽曲", "原作", "原作脚本", "原作・脚本", "監督・演出", "脚本", "知らない見出し", ""),
            DetailKind.Notice to listOf("おしらせ", "お知らせ", "◇おしらせ", "ご案内"),
            DetailKind.Link to listOf("ホームページ", "HP", "番組HP", "公式ホームページ", "公式サイト", "hp"),
        )
        cases.forEach { (kind, headings) ->
            headings.forEach { assertEquals("「$it」", kind, detailKind(it)) }
        }
    }

    /** 番組内容 → 出演者 → ほか → おしらせ。ホームページは出さない */
    @Test
    fun 並べる() {
        val text = arrangeDetail(
            "十年ぶりに故郷へ戻った絵描き。",
            listOf(
                "ホームページ" to "https://www.example.jp/drama/",
                "おしらせ" to "この番組は字幕放送です。",
                "制作" to "【スタッフ】\n脚本：野々村 灯\n演出：上原 健一",
                "出演者" to "朝倉…東山 隼人\n凛…水瀬 ひかり",
                "番組内容" to "十年ぶりに故郷へ戻った絵描き。港町で古い友人と再会する。",
                "主題歌" to "「鍵」ソラノネ",
            ),
        )
        assertEquals(
            DetailText(
                story = listOf("十年ぶりに故郷へ戻った絵描き。港町で古い友人と再会する。"),
                cast = listOf("出演者" to "朝倉…東山 隼人 ／ 凛…水瀬 ひかり"),
                notes = listOf(
                    "制作" to "【スタッフ】　脚本：野々村 灯 ／ 演出：上原 健一",
                    "主題歌" to "「鍵」ソラノネ",
                    "おしらせ" to "この番組は字幕放送です。",
                ),
            ),
            text,
        )
    }

    /** 番組内容1・2 (①②) は放送の順に段落で。説明が番組内容の一部 (途中で切れて「…」) なら出さない */
    @Test
    fun 番組内容をまとめる() {
        val text = arrangeDetail(
            "港町で古い友人と\n再会…",
            listOf("番組内容①" to "第3話。港町で古い友人と再会する。", "あらすじ◇" to "友人には秘密があった。", "番組内容②" to "友人には秘密があった。"),
        )
        assertEquals(listOf("第3話。港町で古い友人と再会する。", "友人には秘密があった。"), text.story)
    }

    /** 説明のほうが長く番組内容を含むなら、説明だけ。重ならなければ説明 → 番組内容 */
    @Test
    fun 説明と番組内容() {
        assertEquals(listOf("説明の文。番組内容の文。"), arrangeDetail("説明の文。番組内容の文。", listOf("番組内容" to "番組内容の文。")).story)
        assertEquals(listOf("説明の文。", "番組内容の文。"), arrangeDetail("説明の文。", listOf("今回の番組内容" to "番組内容の文。")).story)
        assertEquals(listOf("説明だけ。"), arrangeDetail(" 説明だけ。\n", emptyList()).story)
    }

    /** URL だけの行は外し、空になった見出しは出さない */
    @Test
    fun URLの行を外す() {
        val text = arrangeDetail(
            "",
            listOf("おしらせ" to "詳しくは番組のサイトで。\nhttps://example.jp/a", "HP" to "番組サイト\nhttps://example.jp", "スタッフ2" to "www.example.jp"),
        )
        assertEquals(listOf("おしらせ" to "詳しくは番組のサイトで。"), text.notes)
        assertTrue(arrangeDetail("", listOf("ホームページ" to "https://example.jp")).isEmpty)
    }
}
