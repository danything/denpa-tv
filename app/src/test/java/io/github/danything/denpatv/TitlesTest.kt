package io.github.danything.denpatv

import io.github.danything.denpatv.data.keepEpisode
import io.github.danything.denpatv.data.shortServiceName
import org.junit.Assert.assertEquals
import org.junit.Test

/** カードの番組名を、話数を残して途中で切る (`keepEpisode`)。枠は字数で見立てる (画面と同じく、収まらないときだけ呼ぶ) */
class TitlesTest {
    private fun fit(title: String, chars: Int, room: (String) -> Int = { chars - it.length }) =
        if (title.length <= chars) title else keepEpisode(title, visible = chars, room = room) { it.length <= chars }

    @Test
    fun 収まる題はそのまま() {
        assertEquals("NHKニュース7", fit("NHKニュース7", 22))
        assertEquals("葬送のフリーレン #12「本物の勇者」", fit("葬送のフリーレン #12「本物の勇者」", 22))
    }

    @Test
    fun 長い番組名は途中を切って話数を残す() {
        val niah = "凶乱令嬢ニア・リストン 病弱令嬢に転生した神殺しの武人の華麗なる無双録 #1"
        assertEquals("凶乱令嬢ニア・リストン 病弱令嬢に転生した… #1", fit(niah, 25))
        // 同じ番組の続けて録った回が見分けられる
        val exile = "追放されたチート付与魔術師は気ままなセカンドライフを謳歌する。 #"
        assertEquals("追放されたチート付与魔術師は気ままな… #1", fit(exile + "1", 22))
        assertEquals("追放されたチート付与魔術師は気ままな… #2", fit(exile + "2", 22))
    }

    @Test
    fun 見積もりが多すぎても収まるまで減らす() {
        val niah = "凶乱令嬢ニア・リストン 病弱令嬢に転生した神殺しの武人の華麗なる無双録 #1"
        assertEquals("凶乱令嬢ニア・リストン 病弱令嬢に転生した… #1", fit(niah, 25) { 30 })
    }

    @Test
    fun 話数の書き方() {
        assertEquals("時代劇の番組名がとても長いの… 第12話", fit("時代劇の番組名がとても長いのでカードに収まらない 第12話", 20))
        assertEquals("大河ドラマ べらぼう~蔦重栄華… (39)", fit("大河ドラマ べらぼう~蔦重栄華乃夢噺~ (39)「白河の清きに住みかね身上半減」", 21))
        assertEquals("全角の番号の番組名がとても長… ＃3", fit("全角の番号の番組名がとても長くて収まらない ＃3", 18))
        // 期は話数ではない (話数の #25 を残す)
        assertEquals("薬屋のひとりごと 第2期… #25", fit("薬屋のひとりごと 第2期 特別な長い副題がつく #25", 18))
    }

    @Test
    fun 話数までは収まるなら末尾で切る() {
        // 話数の後ろのサブタイトルがはみ出すだけなら、そのまま (描くときに末尾が … になる)
        val title = "片田舎のおっさん、剣聖になる #7「ベリル・ガーデナント、王都を歩く」"
        assertEquals(title, fit(title, 22))
    }

    @Test
    fun 話数が無い_頭が無い_切れないならそのまま() {
        assertEquals("話数の無いとても長い番組名がカードに収まらない", fit("話数の無いとても長い番組名がカードに収まらない", 10))
        assertEquals("#1 から始まるとても長い番組名", fit("#1 から始まるとても長い番組名", 8))
        // 話数だけでも収まらない
        assertEquals("番組名 #1234", fit("番組名 #1234", 4))
    }

    @Test
    fun 局の名前は半角に寄せる() {
        assertEquals("TOKYO MX1", shortServiceName("ＴＯＫＹＯ　ＭＸ１"))
        assertEquals("NHK総合1・東京", shortServiceName("ＮＨＫ総合１・東京"))
        assertEquals("AT-X", shortServiceName("ＡＴ－Ｘ"))
        assertEquals("BS11イレブン", shortServiceName("ＢＳ１１イレブン"))
    }
}
