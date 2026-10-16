package io.github.danything.denpatv

import io.github.danything.denpatv.data.BaseUrl
import io.github.danything.denpatv.data.ChapterMark
import io.github.danything.denpatv.data.Decoders
import io.github.danything.denpatv.data.DenpaApi
import io.github.danything.denpatv.data.DenpaAudio
import io.github.danything.denpatv.data.PlayAudio
import io.github.danything.denpatv.data.PlayChoice
import io.github.danything.denpatv.data.PlayOffer
import io.github.danything.denpatv.data.PlaybackInfo
import io.github.danything.denpatv.data.PlaybackLookup
import io.github.danything.denpatv.data.PlaybackPaths
import io.github.danything.denpatv.data.Pts
import io.github.danything.denpatv.data.StreamClock
import io.github.danything.denpatv.data.parsePlaybackChapters
import io.github.danything.denpatv.data.pickPlayback
import io.github.danything.denpatv.data.resumeSource
import io.github.danything.denpatv.data.streamAudios
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 録画の観方 (denpa の `playback`・`stream`・`chapters`)。docs/api.md の例の形式で */
class PlaybackTest {
    private val tv = Decoders(av1 = true, h264 = true, mpeg2 = true, av1Hardware = true, mpeg2Hardware = true)
    private val stick = Decoders(av1 = false, h264 = true, mpeg2 = true, av1Hardware = false, mpeg2Hardware = false)
    private val bare = Decoders(av1 = false, h264 = true, mpeg2 = false, av1Hardware = false, mpeg2Hardware = false)

    /** エンコード済み (AV1) と、詰め替え・変換・元の TS */
    private val encoded = PlaybackInfo(
        duration = 1790.5,
        axis = "encoded",
        offers = listOf(
            PlayOffer("av1", "file", "encoded"),
            PlayOffer("h264", "stream"),
            PlayOffer("av1", "stream"),
            PlayOffer("mpeg2", "raw"),
        ),
    )

    /** エンコード前 (元の TS だけ) */
    private val tsOnly = PlaybackInfo(
        duration = 1800.0,
        axis = "ts",
        offers = listOf(PlayOffer("h264", "stream"), PlayOffer("av1", "stream"), PlayOffer("mpeg2", "raw")),
    )

    @Test
    fun エンコード済みのファイルをデコードできればそのまま開く() {
        assertEquals(PlayChoice.File("av1", "encoded"), pickPlayback(encoded, tv))
        // AV1 と H.264 の2本あれば、軽い AV1 から
        val both = encoded.copy(offers = listOf(PlayOffer("h264", "file", "alt")) + encoded.offers)
        assertEquals(PlayChoice.File("av1", "encoded"), pickPlayback(both, tv))
        assertEquals(PlayChoice.File("h264", "alt"), pickPlayback(both, stick))
    }

    @Test
    fun ファイルをデコードできなければ_MPEG2_をハードでデコードできる端末は元の_TS_をそのまま() {
        val noAv1 = tv.copy(av1 = false, av1Hardware = false)
        assertEquals(PlayChoice.Stream("mpeg2"), pickPlayback(encoded, noAv1))
        assertEquals(PlayChoice.Stream("mpeg2"), pickPlayback(tsOnly, tv))
    }

    @Test
    fun それ以外は変換を_H264_から_元の_TS_はソフトのデコーダで最後に() {
        assertEquals(PlayChoice.Stream("h264"), pickPlayback(encoded, stick))
        assertEquals(PlayChoice.Stream("h264"), pickPlayback(tsOnly, stick))
        // H.264 の変換が無く (版のずれ)、AV1 もデコードできなければ元の TS を
        val rawOnly = tsOnly.copy(offers = listOf(PlayOffer("av1", "stream"), PlayOffer("mpeg2", "raw")))
        assertEquals(PlayChoice.Stream("mpeg2"), pickPlayback(rawOnly, stick))
        assertNull(pickPlayback(rawOnly, bare))
        assertNull(pickPlayback(PlaybackInfo(), tv))
        // 中身の形式は流し方で決まる
        assertEquals("video/mp2t", PlayChoice.Stream("mpeg2").mime)
        assertEquals("video/mp4", PlayChoice.Stream("h264").mime)
    }

    @Test
    fun 流すエンドポイントは録画の時刻を秒の小数で渡す() {
        assertEquals("api/recordings/12/stream?codec=h264&from=754.025", PlaybackPaths.stream(12, "h264", 754_025))
        assertEquals("api/recordings/12/stream?codec=mpeg2&from=0.000", PlaybackPaths.stream(12, "mpeg2", -5))
        assertEquals(
            "api/recordings/12/stream?codec=av1&from=3.500&audio=0%3Amain",
            PlaybackPaths.stream(12, "av1", 3_500, DenpaAudio(id = "0:main")),
        )
        assertEquals("api/recordings/12/file?source=alt", PlaybackPaths.file(12, "alt"))
    }

    @Test
    fun 変換でリクエストする音声は_元の_TS_からなら放送の音声_エンコード済みからならファイルの何本目か() {
        val broadcast = listOf(DenpaAudio("0:main", 0, "main", "主音声"), DenpaAudio("0:sub", 0, "sub", "副音声"))
        assertEquals(broadcast, streamAudios(tsOnly, broadcast))
        val withTracks = encoded.copy(audios = listOf(PlayAudio("0", "主音声"), PlayAudio("1", "副音声")))
        assertEquals(
            listOf(DenpaAudio("0", 0, label = "主音声", main = true), DenpaAudio("1", 1, label = "副音声", main = false)),
            streamAudios(withTracks, broadcast),
        )
    }

    @Test
    fun チャプターは秒をミリ秒にして_読めないものは捨てる() {
        val json = """{"chapters":[{"start":612.4,"end":702.4,"title":"CM"},{"start":0,"end":612.4,"title":"本編","extra":1},
            {"start":800,"end":800,"title":"空"}]}"""
        assertEquals(
            listOf(ChapterMark(0, 612_400, "本編"), ChapterMark(612_400, 702_400, "CM")),
            parsePlaybackChapters(json),
        )
        assertTrue(parsePlaybackChapters(json).last().isCm)
        assertEquals(emptyList<ChapterMark>(), parsePlaybackChapters("<html>"))
        assertEquals(emptyList<ChapterMark>(), parsePlaybackChapters("""{"chapters":[]}"""))
    }

    @Test
    fun 流れの時刻を録画の時刻に戻す() {
        // fMP4: 塊の時刻 (録画の時刻) 12.345 秒を 0 に寄せた (幅は −12.345 秒)
        assertEquals(12_345L, StreamClock.mp4BaseMs(-12_345_000))
        // 元の TS: 頭の PTS が 1000 秒、録画の 0 が 997 秒の PTS → プレーヤーの 0 は録画の 3 秒
        val pts = 1_000L * 90_000
        assertEquals(3_000L, StreamClock.tsBaseMs(-1_000_000_000, pts - 3 * 90_000))
        // 一周をまたぐ: 頭の PTS が一周の 1 秒あと、録画の 0 は一周の 1 秒前
        assertEquals(2_000L, StreamClock.tsBaseMs(-1_000_000, Pts.WRAP - 90_000))
        // 録画の 0 より手前から流れる (頭からリクエストすると 1 秒手前から): 負になる。一周の向きを取り違えない
        assertEquals(-1_000L, StreamClock.tsBaseMs(-1_000_000_000, pts + 90_000))
    }

    /**
     * 観た位置の `source` は観ているファイル。ファイルはそのファイル、流してもらうときは元にしたファイル (`axis`)。
     * エンコード済みが無い録画を変換・元の TS のまま流すなら `ts` (省くと denpa はエンコード済みの時刻とみなす)
     */
    @Test
    fun 観た位置のsourceは観ているファイル() {
        assertEquals("encoded", PlayChoice.File("av1", "encoded").resumeSource(encoded))
        assertEquals("alt", PlayChoice.File("h264", "alt").resumeSource(encoded))
        assertEquals("encoded", PlayChoice.Stream("h264").resumeSource(encoded))
        assertEquals("encoded", PlayChoice.Stream("mpeg2").resumeSource(encoded))
        assertEquals("ts", PlayChoice.Stream("h264").resumeSource(tsOnly))
        assertEquals("ts", PlayChoice.Stream("mpeg2").resumeSource(tsOnly))
    }
}

/** 観方まわりのエンドポイントを呼ぶ (docs/api.md の形式) */
class PlaybackApiTest {
    private val denpa = FakeDenpa()
    private val api = DenpaApi(warn = {})

    @After fun stop() = denpa.close()

    @Test
    fun 観方_チャプター_録画の状態を読む() = runTest {
        val base = BaseUrl.normalize(denpa.url())!!
        denpa.enqueue(
            """{ "duration": 1790.5, "axis": "encoded", "new": true,
                 "audios": [{ "id": "0", "label": "主音声" }],
                 "offers": [{ "codec": "av1", "way": "file", "source": "encoded", "codecs": "av01.0.08M.08,opus" },
                            { "codec": "mpeg2", "way": "raw" }] }""",
        )
        val found = api.playback(base, 12) as PlaybackLookup.Found
        assertEquals("encoded", found.info.axis)
        assertEquals(PlayOffer("mpeg2", "raw"), found.info.offers.last())
        assertEquals("/api/recordings/12/playback", denpa.requests.take().target)
        // 録画中・ファイルが無い・エンドポイントの無い古い denpa
        denpa.enqueue("", code = 404)
        assertEquals(PlaybackLookup.Missing, api.playback(base, 12))
        denpa.enqueue("<html>")
        assertTrue(api.playback(base, 12) is PlaybackLookup.Failed)

        denpa.enqueue("""{"chapters":[{"start":0,"end":10.5,"title":"本編"}]}""")
        assertEquals(listOf(ChapterMark(0, 10_500, "本編")), api.chapters(base, 12))
        assertEquals("/api/recordings/12/chapters", denpa.requests.last().target)
        denpa.enqueue("", code = 404)
        assertEquals(emptyList<ChapterMark>(), api.chapters(base, 12))

        denpa.enqueue("""{"id":12,"encoded":false,"state":"recording","durationMs":null}""")
        val recording = api.recordingState(base, 12)!!
        assertTrue(recording.recording)
        assertNull(recording.durationMs)
        denpa.enqueue("""{"id":12,"encoded":true,"state":"available","durationMs":1800000}""")
        assertEquals(1_800_000L, api.recordingState(base, 12)?.durationMs)
        // 消した録画
        denpa.enqueue("", code = 404)
        assertNull(api.recordingState(base, 12))
    }
}
