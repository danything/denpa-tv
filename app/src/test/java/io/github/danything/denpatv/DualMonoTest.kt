package io.github.danything.denpatv

import io.github.danything.denpatv.data.AudioChoice
import io.github.danything.denpatv.data.AudioSide
import io.github.danything.denpatv.data.AudioTrack
import io.github.danything.denpatv.data.Chase
import io.github.danything.denpatv.data.DenpaAudio
import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.Service
import io.github.danything.denpatv.data.audioChoices
import io.github.danything.denpatv.data.audioQuery
import io.github.danything.denpatv.data.audioTrack
import io.github.danything.denpatv.data.bakedAudio
import io.github.danything.denpatv.data.dualMonoCoefficients
import io.github.danything.denpatv.data.dualMonoLabels
import io.github.danything.denpatv.data.selectedChoice
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualMonoTest {
    /** denpa の `audioTracks` がデュアルモノ1本から出すもの */
    private val dual = listOf(
        DenpaAudio("0:main", 0, "main", "主音声 (日本語)", true),
        DenpaAudio("0:sub", 0, "sub", "副音声 (英語)", true),
        DenpaAudio("0:both", 0, "both", "主+副", true),
    )

    @Test
    fun デュアルモノは主_副_主副の名前を返す() {
        assertEquals(
            mapOf(AudioSide.Main to "主音声 (日本語)", AudioSide.Sub to "副音声 (英語)", AudioSide.Both to "主+副"),
            dualMonoLabels(dual, 0),
        )
    }

    @Test
    fun 名前が無ければ主音声_副音声_主副() {
        val bare = listOf(DenpaAudio(stream = 0, side = "main"), DenpaAudio(stream = 0, side = "sub"))
        assertEquals(mapOf(AudioSide.Main to "主音声", AudioSide.Sub to "副音声", AudioSide.Both to "主+副"), dualMonoLabels(bare, 0))
    }

    @Test
    fun ふつうの音声と別の本はデュアルモノではない() {
        val stereo = listOf(DenpaAudio("0:both", 0, "both", "ステレオ (日本語)"))
        assertNull(dualMonoLabels(stereo, 0))
        assertNull(dualMonoLabels(dual, 1))
        assertNull(dualMonoLabels(emptyList(), 0))
        // 知らない側の書き方は読み捨てる
        assertNull(dualMonoLabels(listOf(DenpaAudio(stream = 0, side = "left")), 0))
    }

    @Test
    fun デュアルモノの1本は3つに平らに並ぶ() {
        val groups = listOf(audioTrack(0, null, "jpn"), audioTrack(1, null, "eng"))
        val choices = audioChoices(groups, dual)
        assertEquals(
            listOf(
                AudioChoice(0, AudioSide.Main, AudioTrack("主音声 (日本語)", false)),
                AudioChoice(0, AudioSide.Sub, AudioTrack("副音声 (英語)", false)),
                AudioChoice(0, AudioSide.Both, AudioTrack("主+副", false)),
                AudioChoice(1, null, groups[1]),
            ),
            choices,
        )
        // denpa が何も言わなければ、これまでどおり1本ずつ
        assertEquals(groups, audioChoices(groups, emptyList()).map { it.track })
    }

    @Test
    fun 選ばれているものは本と側で引く() {
        val choices = audioChoices(listOf(audioTrack(0, null, null), audioTrack(1, null, null)), dual)
        assertEquals(0, selectedChoice(choices, 0, AudioSide.Main))
        assertEquals(1, selectedChoice(choices, 0, AudioSide.Sub))
        assertEquals(2, selectedChoice(choices, 0, AudioSide.Both))
        // デュアルモノでない本は側を見ない
        assertEquals(3, selectedChoice(choices, 1, AudioSide.Sub))
        assertEquals(0, selectedChoice(emptyList(), 0, AudioSide.Main))
    }

    @Test
    fun 主は左を_副は右を両耳へ_主副はそのまま() {
        // 入力 × 出力の行優先 (L→L, L→R, R→L, R→R)
        assertArrayEquals(floatArrayOf(1f, 1f, 0f, 0f), dualMonoCoefficients(AudioSide.Main), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), dualMonoCoefficients(AudioSide.Sub), 0f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f), dualMonoCoefficients(AudioSide.Both), 0f)
    }

    @Test
    fun 側は_denpa_の書き方で読み書きする() {
        assertEquals(AudioSide.Sub, AudioSide.of("sub"))
        assertNull(AudioSide.of(null))
        assertNull(AudioSide.of("left"))
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun 局と録画の_audios_を読む_無ければ空() {
        val service = json.decodeFromString<Service>(
            """{"id":1,"type":"GR","name":"局","live":"api/services/1/live","now":{"title":"映画","startAt":0,"endAt":1,
               "audios":[{"id":"0:main","stream":0,"side":"main","label":"主音声 (日本語)","main":true}]}}""",
        )
        assertEquals("0:main", service.now?.audios?.single()?.id)
        val old = json.decodeFromString<Recording>("""{"id":1,"title":"録画","startAt":0}""")
        assertTrue(old.audios.isEmpty())
    }

    @Test
    fun 焼くときはこの画面で選んだもの_無ければ既定の主音声の覚えている側() {
        assertEquals("0:sub", bakedAudio(dual, "0:sub", AudioSide.Main)?.id)
        assertEquals("0:main", bakedAudio(dual, null, AudioSide.Main)?.id)
        assertEquals("0:sub", bakedAudio(dual, null, AudioSide.Sub)?.id)
        assertEquals("0:both", bakedAudio(dual, null, AudioSide.Both)?.id)
        // 番組が替わって無くなったものは忘れる
        assertEquals("0:main", bakedAudio(dual, "1:both", AudioSide.Main)?.id)
    }

    @Test
    fun 焼くときの既定は放送の言う主音声() {
        val two = listOf(
            DenpaAudio("0:both", 0, "both", "解説ステレオ (日本語)", false),
            DenpaAudio("1:both", 1, "both", "主音声ステレオ (日本語)", true),
        )
        assertEquals("1:both", bakedAudio(two, null, AudioSide.Sub)?.id)
        assertEquals("0:both", bakedAudio(two, "0:both", AudioSide.Main)?.id)
    }

    @Test
    fun 選べるものが1つ以下なら頼まない() {
        assertNull(bakedAudio(listOf(DenpaAudio("0:both", 0, "both", "ステレオ")), "0:both", AudioSide.Main))
        assertNull(bakedAudio(emptyList(), null, AudioSide.Main))
        assertEquals("", audioQuery(null))
    }

    @Test
    fun 頼む_URL_に音声を足す() {
        assertEquals("&audio=0%3Asub", audioQuery(dual[1]))
        assertEquals("api/recordings/9/chase?codec=h264&from=754&audio=0%3Amain", Chase.url("api/recordings/9/chase", "h264", 754_900, dual[0]))
        assertEquals("api/recordings/9/chase?codec=raw&from=754", Chase.url("api/recordings/9/chase", "raw", 754_900))
    }
}
