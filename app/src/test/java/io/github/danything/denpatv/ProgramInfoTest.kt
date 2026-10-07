package io.github.danything.denpatv

import io.github.danything.denpatv.data.BaseUrl
import io.github.danything.denpatv.data.DenpaApi
import io.github.danything.denpatv.data.Genre
import io.github.danything.denpatv.data.ProgramAudio
import io.github.danything.denpatv.data.ProgramLookup
import io.github.danything.denpatv.data.Unauthorized
import io.github.danything.denpatv.data.audioLabel
import io.github.danything.denpatv.data.genreLabel
import io.github.danything.denpatv.data.parseProgramInfo
import io.github.danything.denpatv.data.programMeta
import io.github.danything.denpatv.data.videoLabel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.TimeZone

/** 番組の中身 (`GET api/programs/<id>`、ライブの詳しく)。denpa の `ProgramDetail` そのままの snake_case */
class ProgramInfoTest {
    private val denpa = FakeDenpa()
    private val warnings = mutableListOf<String>()
    private val api = DenpaApi(warn = { warnings += it })

    @After fun stop() = denpa.close()

    @Test
    fun 番組の中身を読む() = runTest {
        denpa.enqueue(
            """{"name":"[新]ドラマ 第3話[字]","service_name":"フジテレビ","start_at":1790000000000,"end_at":1790003600000,
               "description":"あらすじ","extended":{"番組内容":"内容","出演者":"誰か"},
               "genre_detail":[{"lv1":3,"lv2":0},{"lv1":3,"lv2":0},{"lv1":14,"lv2":15}],
               "audios":[{"componentType":3,"langs":["jpn"],"main":true},{"componentType":3,"langs":["jpn"],"text":"解説ステレオ"}],
               "video_type":"mpeg2","video_resolution":"1080i","is_free":false,"new_key":1}""",
        )
        val base = BaseUrl.normalize(denpa.url("/denpa"))!!
        val info = (api.program(base, 32740010640) as ProgramLookup.Found).info
        assertEquals("/denpa/api/programs/32740010640", denpa.requests.take().target)
        assertEquals(listOf("番組内容" to "内容", "出演者" to "誰か"), info.extended)
        // ブラウザの denpa の札と同じ並び。同じジャンルは1つに
        assertEquals(
            listOf("ドラマ > 国内ドラマ", "拡張", "1080i MPEG-2", "ステレオ (日本語)", "解説ステレオ (日本語)", "有料"),
            info.chips,
        )
        assertTrue(warnings.isEmpty())
    }

    /** 番組表から消えた (404)・口の無い古い denpa は出さないだけ。トークンが効かなければ繋ぐ画面へ */
    @Test
    fun 無い番組と断られたとき() = runTest {
        val base = BaseUrl.normalize(denpa.url("/"))!!
        denpa.enqueue("""{"message":"番組が見つかりません"}""", 404)
        assertEquals(ProgramLookup.Missing, api.program(base, 1))
        denpa.enqueue("<html>", 200)
        assertEquals(ProgramLookup.Missing, api.program(base, 1))
        denpa.enqueue("", 401)
        try {
            api.program(base, 1)
            fail("401 で Unauthorized になりません")
        } catch (_: Unauthorized) {
        }
    }

    /** 形がずれていても (denpa の版のずれ) 読めるところは読み、ずれた鍵だけ捨てて言う */
    @Test
    fun 形のずれは言って続ける() {
        val info = parseProgramInfo(
            """{"name":"番組","description":42,"extended":{"出演者":"誰か","数":3},"genre_detail":[{"lv1":"3"},{"lv1":7,"lv2":0}],
               "audios":[{"componentType":2,"langs":["jpn","eng"]},"ステレオ"],"video_type":null,"video_resolution":1080,"is_free":1}""",
        ) { warnings += it }!!
        assertEquals("番組", info.name)
        assertEquals("", info.description)
        assertNull(info.videoType)
        assertNull(info.videoResolution)
        assertEquals(listOf("出演者" to "誰か"), info.extended)
        assertEquals(listOf(Genre(7, 0)), info.genres)
        assertEquals(listOf(ProgramAudio(2, listOf("jpn", "eng"))), info.audios)
        assertEquals(listOf("アニメ／特撮 > 国内アニメ", "デュアルモノ (日本語/英語)"), info.chips)
        val keys = listOf("description", "extended.数", "genre_detail", "audios", "video_resolution")
        assertEquals(keys, warnings.map { w -> keys.first { it in w } })
        assertNull(parseProgramInfo("[]") { warnings += it })
    }

    @Test
    fun 札の名前() {
        assertEquals("アニメ／特撮 > 国内アニメ", genreLabel(Genre(7, 0)))
        assertEquals("アニメ／特撮", genreLabel(Genre(7, 9)))
        assertEquals("", genreLabel(Genre(12, 0)))
        assertEquals("主音声ステレオ", audioLabel(ProgramAudio(3, text = "主音声ステレオ")))
        assertEquals("種別99 (xyz)", audioLabel(ProgramAudio(99, listOf("xyz"))))
        assertEquals("1080i MPEG-2", videoLabel("1080i", "mpeg2"))
        assertEquals("H.264", videoLabel(null, "h.264"))
        assertEquals("", videoLabel(null, null))
    }

    @Test
    fun 局と日時の1行() {
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        // 2026-10-06 (火) 21:00 JST
        val start = 1791288000000
        assertEquals("フジテレビ ・ 10/6(火) 21:00〜21:54 (54分)", programMeta("フジテレビ", start, start + 54 * 60_000, tokyo))
        assertEquals("10/6(火) 21:00〜22:30 (1時間30分)", programMeta(null, start, start + 90 * 60_000, tokyo))
        assertEquals("局 ・ 10/6(火) 21:00", programMeta("局", start, null, tokyo))
    }
}
