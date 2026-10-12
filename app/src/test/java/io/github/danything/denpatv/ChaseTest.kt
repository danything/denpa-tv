package io.github.danything.denpatv

import io.github.danything.denpatv.data.AudioTrack
import io.github.danything.denpatv.data.Chase
import io.github.danything.denpatv.data.Decoders
import io.github.danything.denpatv.data.PlayOffer
import io.github.danything.denpatv.data.PlaybackInfo
import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.RecordingFile
import io.github.danything.denpatv.data.audioTrack
import io.github.danything.denpatv.data.chasing
import io.github.danything.denpatv.data.rememberedAudio
import io.github.danything.denpatv.data.skipCmAtStart
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaseTest {
    @Test
    fun 頼む位置は録れた長さの手前まで() {
        val recorded = Chase.recordedMs(startAt = 1_000_000, now = 1_000_000 + 600_000)
        assertEquals(600_000L, recorded)
        assertEquals(590_000L, Chase.clamp(700_000, recorded))
        assertEquals(0L, Chase.clamp(-5_000, recorded))
        assertEquals(300_000L, Chase.clamp(300_000, recorded))
        // 録りはじめ (余白より短い) は頭から
        assertEquals(0L, Chase.clamp(3_000, 4_000))
        assertTrue(Chase.atEdge(585_000, recorded))
        assertFalse(Chase.atEdge(500_000, recorded))
        assertEquals("api/recordings/9/chase?codec=raw&from=754", Chase.url("api/recordings/9/chase", "raw", 754_900))
    }

    /** 録画中は追っかけ。録り終えて元のTSだけ残っているものは、ふつうのファイルで観る */
    @Test
    fun 録画中だけ追っかけで観る() {
        val json = Json { ignoreUnknownKeys = true }
        val live = json.decodeFromString<Recording>(
            """{"id":9,"title":"t","startAt":1,"durationMs":null,"recording":true,"chase":"api/recordings/9/chase","cmReliable":false,"files":[{"source":"ts","codec":"mpeg2","url":"x"}]}""",
        )
        assertTrue(live.chasing)
        assertFalse(live.cmReliable)
        val done = live.copy(recording = false)
        assertFalse(done.chasing)
        // 鍵が無ければ: 録画中ではなく、CM 飛ばしは入れてよい
        val old = json.decodeFromString<Recording>("""{"id":1,"title":"t","startAt":1}""")
        assertFalse(old.chasing)
        assertTrue(old.cmReliable)
    }

    @Test
    fun CM飛ばしはロゴで判定できた録画だけ観はじめに入れる() {
        assertTrue(skipCmAtStart(cmReliable = true, remembered = true))
        assertFalse(skipCmAtStart(cmReliable = false, remembered = true))
        assertFalse(skipCmAtStart(cmReliable = true, remembered = false))
    }

    @Test
    fun 音声の名前() {
        assertEquals(AudioTrack("解説ステレオ", true), audioTrack(1, "解説ステレオ", "jpn"))
        assertEquals(AudioTrack("音声 2 (日本語)", false), audioTrack(1, null, "jpn"))
        assertEquals(AudioTrack("音声 1", false), audioTrack(0, "", "und"))
    }

    /** 名前の付いた音声だけ覚えた名前に合わせる (番号だけのものは局ごとに意味が違う) */
    @Test
    fun 覚えた音声に合わせる() {
        val named = listOf(AudioTrack("主音声ステレオ", true), AudioTrack("解説ステレオ", true))
        assertEquals(1, rememberedAudio(named, "解説ステレオ"))
        assertNull(rememberedAudio(named, "英語"))
        assertNull(rememberedAudio(named, null))
        val bare = listOf(audioTrack(0, null, "jpn"), audioTrack(1, null, "jpn"))
        assertNull(rememberedAudio(bare, "音声 2 (日本語)"))
    }

    /**
     * エンコードが終わった録画をファイルの再生へ渡すのは、録り終えていて、録画の再生 (`pickPlayback`) がエンコード済みのファイルを開くときだけ
     * (一覧が追いついていなければ待つ)
     */
    @Test
    fun エンコードが終わったらファイルの再生へ渡す() {
        val decoders = Decoders(av1 = false, h264 = true, mpeg2 = false)
        val finished = Recording(
            id = 12, title = "番組", startAt = 0,
            files = listOf(RecordingFile("encoded", "h264", "api/recordings/12/file?source=encoded")),
        )
        val encoded = PlaybackInfo(
            duration = 1800.0,
            axis = "encoded",
            offers = listOf(PlayOffer("h264", "file", "encoded"), PlayOffer("av1", "stream"), PlayOffer("mpeg2", "raw")),
        )
        assertTrue(Chase.handOff(finished, encoded, decoders))
        // 一覧がまだ録画中のまま・一覧から消えた・観方が読めない
        assertFalse(Chase.handOff(finished.copy(recording = true, chase = "api/recordings/12/chase"), encoded, decoders))
        assertFalse(Chase.handOff(null, encoded, decoders))
        assertFalse(Chase.handOff(finished, null, decoders))
        // エンコード済みのファイルをこの端末でデコードできない (AV1 だけ。録画の再生は変換して流してもらう)
        val av1Only = encoded.copy(offers = listOf(PlayOffer("av1", "file", "encoded"), PlayOffer("h264", "stream")))
        assertFalse(Chase.handOff(finished, av1Only, decoders))
        // 元の TS だけ (「エンコードしない」設定・エンコード待ち) は渡さない。MPEG-2 をデコードできても
        val ts = PlaybackInfo(duration = 1800.0, axis = "ts", offers = listOf(PlayOffer("h264", "stream"), PlayOffer("mpeg2", "raw")))
        assertFalse(Chase.handOff(finished, ts, Decoders(av1 = false, h264 = true, mpeg2 = true, mpeg2Hardware = true)))
    }
}
