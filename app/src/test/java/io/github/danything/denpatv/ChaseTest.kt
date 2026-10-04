package io.github.danything.denpatv

import io.github.danything.denpatv.data.AudioTrack
import io.github.danything.denpatv.data.Chase
import io.github.danything.denpatv.data.Recording
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

    /** 録画中は追っかけ。録り終えて生TSだけ残っているものは、ふつうのファイルで観る */
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
        // 古い denpa は鍵が無い: 録画中ではなく、CM 飛ばしは入れてよい
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
}
