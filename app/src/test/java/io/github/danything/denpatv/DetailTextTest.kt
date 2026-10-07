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
            DetailKind.Story to listOf("番組内容", "番組内容1", "番組内容2", "番組内容①", "番組内容②", "番組内容１", "今回の番組内容", "あらすじ◇", "◇あらすじ", "【あらすじ】", "内容", "みどころ", "番組概要", "放送内容", "作品紹介", "ｱﾗｽｼﾞ", "今週の見どころ", "≪番組内容≫"),
            DetailKind.Cast to listOf("出演者", "声の出演", "【出演】", "ゲスト", "キャスト", "語り", "出演者紹介"),
            DetailKind.Credit to listOf("制作", "スタッフ", "スタッフ2", "音楽", "主題歌", "楽曲", "原作", "原作脚本", "原作・脚本", "監督・演出", "脚本", "解説", "実況", "スタッフ紹介", "知らない見出し", ""),
            DetailKind.Notice to listOf("おしらせ", "お知らせ", "◇おしらせ", "ご案内", "番組のご案内", "お知らせ・ホームページ"),
            DetailKind.Link to listOf("ホームページ", "HP", "番組HP", "公式ホームページ", "公式サイト", "hp", "ＨＰ", "ホームページ：", "ﾎｰﾑﾍﾟｰｼﾞ", "〔番組HP〕"),
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
        // 「・・・」で切れた説明・全角の数字でも重なりと見る
        assertEquals(listOf("第3話。港町で再会する。"), arrangeDetail("第３話。港町で・・・", listOf("番組内容" to "第3話。港町で再会する。")).story)
    }

    /** 説明のほうが長く番組内容を含むなら、説明だけ。重ならなければ説明 → 番組内容 */
    @Test
    fun 説明と番組内容() {
        assertEquals(listOf("説明の文。番組内容の文。"), arrangeDetail("説明の文。番組内容の文。", listOf("番組内容" to "番組内容の文。")).story)
        assertEquals(listOf("説明の文。", "番組内容の文。"), arrangeDetail("説明の文。", listOf("今回の番組内容" to "番組内容の文。")).story)
        assertEquals(listOf("説明だけ。"), arrangeDetail(" 説明だけ。\n", emptyList()).story)
        assertEquals(listOf("番組内容の文。"), arrangeDetail("…", listOf("番組内容" to "番組内容の文。")).story)
        assertTrue(arrangeDetail("…", emptyList()).isEmpty)
    }

    /** 「／」で詰める。見出しだけの行 (「【スタッフ】」「演出：」) は次と空白で繋ぎ、【】で終わるだけの行は区切る */
    @Test
    fun 詰める() {
        val notes = arrangeDetail("", listOf("主題歌" to "「鍵」【ソラノネ】\n「海」【ミナト】", "制作" to "【スタッフ】\n演出：\n上原 健一\n\n音楽：森永 響")).notes
        assertEquals(listOf("主題歌" to "「鍵」【ソラノネ】 ／ 「海」【ミナト】", "制作" to "【スタッフ】　演出：　上原 健一 ／ 音楽：森永 響"), notes)
    }

    /** URL を含む行は外し、空になった見出しは出さない */
    @Test
    fun URLの行を外す() {
        val text = arrangeDetail(
            "",
            listOf("おしらせ" to "詳しくは番組のサイトで。\nhttps://example.jp/a", "HP" to "番組サイト\nhttps://example.jp", "スタッフ2" to "www.example.jp", "制作" to "ｈｔｔｐｓ：／／ｅｘａｍｐｌｅ．ｊｐ\n　https://example.jp/b　"),
        )
        assertEquals(listOf("おしらせ" to "詳しくは番組のサイトで。"), text.notes)
        // 「番組HP：」だけが残る行は行ごと、文の中の URL は URL だけ
        assertEquals(listOf("おしらせ" to "再放送は来週です。"), arrangeDetail("", listOf("おしらせ" to "再放送は来週です。\n番組HP：https://example.jp")).notes)
        assertEquals(listOf("第3話。詳しくは へ。"), arrangeDetail("", listOf("番組内容" to "第3話。詳しくは https://www.example.jp/p/x へ。")).story)
        assertTrue(arrangeDetail("", listOf("ホームページ" to "https://example.jp")).isEmpty)
    }

    /** 出す見出しからは、まわりの飾りを外す */
    @Test
    fun 見出しの飾りを外す() {
        val text = arrangeDetail("", listOf("【出演】" to "a", "出演者：" to "b", "◇スタッフ2" to "c", "監督・演出" to "d", "＜音楽＞" to "e"))
        assertEquals(listOf("出演" to "a", "出演者" to "b"), text.cast)
        assertEquals(listOf("スタッフ2" to "c", "監督・演出" to "d", "音楽" to "e"), text.notes)
    }
}
