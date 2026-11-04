package io.github.danything.denpatv

import io.github.danything.denpatv.data.Decoders
import io.github.danything.denpatv.data.LiveQuality
import io.github.danything.denpatv.data.LiveSelection
import io.github.danything.denpatv.data.Playable
import io.github.danything.denpatv.data.PlaybackOptions
import io.github.danything.denpatv.data.choiceLabel
import io.github.danything.denpatv.data.codecQuery
import io.github.danything.denpatv.data.labelThen
import io.github.danything.denpatv.data.number
import io.github.danything.denpatv.data.parsePlaybackOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecsTest {
    private val tv = Decoders(av1 = true, h264 = true, mpeg2 = true, av1Hardware = true, mpeg2Hardware = true, hevcHardware = true)
    private val stick = Decoders(av1 = true, h264 = true, mpeg2 = true, av1Hardware = false, mpeg2Hardware = false)

    /** denpa に伝える再生できる形式は、ハードでデコードできる MPEG-2・AV1・H.265 と、H.264 (並びは denpa が見ない) */
    @Test
    fun 再生できる形式はハードのものと_H264() {
        assertEquals("raw,av1,hevc,h264", Playable.of(tv).query)
        assertEquals("h264", Playable.of(stick).query)
        // H.264 を外す世帯の設定 (hidden) でも、自動には H.264 を伝える (外すのはメニューだけ)
        assertTrue(LiveQuality.H264 in Playable.of(stick).formats)
    }

    /** 端末の側で再生できなかったものは外す。外したものは、ソフトのものを足すときも足し直さない */
    @Test
    fun 再生できなかった形式を外す() {
        val playable = Playable.of(tv).without(LiveQuality.Raw)
        assertEquals("av1,hevc,h264", playable.query)
        assertFalse(playable.empty)
        assertTrue(Playable(listOf(LiveQuality.H264)).without(LiveQuality.H264).empty)
        // 録画で選べなかったら、ソフトでデコードできる AV1・MPEG-2 を足す
        assertEquals("h264,av1,raw", Playable.of(stick).withSoftware(stick).query)
        assertEquals("h264,av1", Playable.of(stick).without(LiveQuality.Raw).withSoftware(stick).query)
        assertEquals(Playable.of(tv), Playable.of(tv).withSoftware(tv))
    }

    /** 選んでいればそれ、自動なら denpa に選ばせる (`codec=auto&playable=`) */
    @Test
    fun リクエストする形式() {
        assertEquals("codec=raw", codecQuery(LiveQuality.Raw, Playable.of(tv)))
        assertEquals("codec=auto&playable=raw,av1,hevc,h264", codecQuery(null, Playable.of(tv)))
        assertEquals("codec=auto&playable=h264", codecQuery(null, Playable.of(stick)))
    }

    /** 応答ヘッダ `X-Denpa-Codec` の値。音声だけ・知らない値は null */
    @Test
    fun 応答ヘッダの形式を読む() {
        assertEquals(LiveQuality.Raw, LiveQuality.ofHeader("raw"))
        assertEquals(LiveQuality.Hevc, LiveQuality.ofHeader(" HEVC "))
        assertEquals(LiveQuality.Av1, LiveQuality.ofHeader("av1"))
        assertNull(LiveQuality.ofHeader("audio"))
        assertNull(LiveQuality.ofHeader("vp9"))
        assertNull(LiveQuality.ofHeader(null))
        // 録画の観方では、元の TS は mpeg2
        assertEquals(LiveQuality.Raw, LiveQuality.ofPlayback("mpeg2"))
        assertEquals(LiveQuality.H264, LiveQuality.ofPlayback("h264"))
    }

    /** denpa の `api/playback/options`。`hidden` が読めなければ null (呼ぶ側は控えない)。`hidden` に `h264` があれば H.264 を外す */
    @Test
    fun 観るときの決まりを読む() {
        assertEquals(PlaybackOptions(hideH264 = true), parsePlaybackOptions("""{"indoor":false,"hidden":["h264","hevc"],"hevc":true}"""))
        assertEquals(PlaybackOptions(hideH264 = false), parsePlaybackOptions("""{"indoor":true,"hidden":[]}"""))
        assertNull(parsePlaybackOptions("""{"indoor":true}"""))
        assertNull(parsePlaybackOptions("<html>"))
    }

    /** ブラウザのコーデックのメニューと同じ並び (自動 → AV1 → H.265 → H.264 → MPEG-2) から、選べないもの (H.265 はいつも) と外したものを除く */
    @Test
    fun メニューの並び() {
        assertEquals(listOf("AV1", "H.264", "MPEG-2"), LiveQuality.menu(tv, hidden = false).map { it.label })
        assertEquals(listOf("AV1", "MPEG-2"), LiveQuality.menu(tv, hidden = true).map { it.label })
        assertEquals(listOf("H.264"), LiveQuality.menu(stick, hidden = false).map { it.label })
        assertEquals(emptyList<LiveQuality>(), LiveQuality.menu(stick, hidden = true))
    }

    @Test
    fun 覚えた選択を読む_選べない値と外した_H264_は自動() {
        assertEquals(LiveQuality.Av1, LiveQuality.read("Av1", tv, hidden = false))
        assertEquals(LiveQuality.Raw, LiveQuality.read("Raw", tv, hidden = true))
        assertEquals(LiveQuality.H264, LiveQuality.read("H264", tv, hidden = false))
        assertNull(LiveQuality.read("H264", tv, hidden = true))
        assertNull(LiveQuality.read("Hevc", tv, hidden = false))
        assertNull(LiveQuality.read("Av1", tv.copy(av1Hardware = false), hidden = false))
        assertNull(LiveQuality.read("壊れた値", tv, hidden = false))
        assertNull(LiveQuality.read(null, tv, hidden = false))
    }

    /** 自動の名前には、応答ヘッダで受け取った形式を添える。選んだものはその名前だけ */
    @Test
    fun 自動の名前に流れている形式を添える() {
        val auto = LiveSelection.of(null, tv, hideH264 = false)
        assertEquals("自動 (MPEG-2)", auto.autoLabel(LiveQuality.Raw))
        assertEquals("自動 (H.265)", auto.autoLabel(LiveQuality.Hevc))
        assertEquals("自動", auto.autoLabel(null))
        assertEquals(LiveSelection.AUTO, LiveSelection.of(LiveQuality.Raw, tv, hideH264 = false).autoLabel(LiveQuality.Raw))
        assertEquals(emptyList<LiveQuality>(), LiveSelection.of(null, stick, hideH264 = true).menu)
        assertEquals("AV1", choiceLabel(LiveQuality.Av1, LiveQuality.H264))
        // 半角で終わる名前だけ、続く文との間に空白を入れる
        assertEquals("AV1 にしました", labelThen("AV1", "にしました"))
        assertEquals("自動にしました", labelThen("自動", "にしました"))
        assertEquals("自動 (MPEG-2) にしました", labelThen("自動 (MPEG-2)", "にしました"))
    }
}

class ChannelNumberTest {
    private fun service(type: String, id: Long, key: Int?) =
        io.github.danything.denpatv.data.Service(id = id, type = type, name = "", remoteControlKey = key, live = "")

    /** denpa の画面と同じ: リモコン番号、無ければ BS/CS はサービス ID (3桁)、地上波は無し */
    @Test
    fun テレビに出ている番号() {
        assertEquals(9, service("GR", 3227310008, 9).number)
        assertEquals(151, service("BS", 400151, null).number)
        assertNull(service("GR", 3227310008, null).number)
    }
}

class NeighborTest {
    private fun s(id: Long) = io.github.danything.denpatv.data.Service(id = id, type = "GR", name = "", live = "")
    private val list = listOf(s(1), s(2), s(3))

    @Test
    fun 隣の局は端で回り_消えていたら頭から_空なら無し() {
        assertEquals(2L, io.github.danything.denpatv.data.neighbor(list, 1, 1)?.id)
        assertEquals(3L, io.github.danything.denpatv.data.neighbor(list, 1, -1)?.id)
        assertEquals(1L, io.github.danything.denpatv.data.neighbor(list, 3, 1)?.id)
        assertEquals(1L, io.github.danything.denpatv.data.neighbor(list, 99, 1)?.id)
        assertNull(io.github.danything.denpatv.data.neighbor(emptyList(), 1, 1))
    }

    private fun on(id: Long, title: String?) = s(id).copy(now = title?.let { io.github.danything.denpatv.data.NowProgram(it, 0, 1) })

    /** 総合1 / 総合2 (同じものを流している: 番組名が空) / Eテレ1 / Eテレ2 (番組表なし) / tvk */
    private val nhk = listOf(on(1, "ニュース"), on(2, ""), on(3, "アニメ"), on(4, null), on(5, "野球"))

    @Test
    fun 放送していない局は飛ばす() {
        assertEquals(listOf(1L, 3L, 5L), io.github.danything.denpatv.data.airing(nhk).map { it.id })
        assertEquals(3L, io.github.danything.denpatv.data.neighbor(nhk, 1, 1)?.id)
        assertEquals(5L, io.github.danything.denpatv.data.neighbor(nhk, 3, 1)?.id)
        assertEquals(1L, io.github.danything.denpatv.data.neighbor(nhk, 5, 1)?.id)
        assertEquals(5L, io.github.danything.denpatv.data.neighbor(nhk, 1, -1)?.id)
        // いまの局が放送していなくても、その位置から隣の放送している局へ
        assertEquals(3L, io.github.danything.denpatv.data.neighbor(nhk, 2, 1)?.id)
        assertEquals(1L, io.github.danything.denpatv.data.neighbor(nhk, 2, -1)?.id)
        assertEquals(5L, io.github.danything.denpatv.data.neighbor(nhk, 4, 1)?.id)
        // 消えていたら、放送している局の頭から
        assertEquals(3L, io.github.danything.denpatv.data.neighbor(nhk.drop(1), 99, 1)?.id)
    }

    @Test
    fun 番組表が空なら全部から選ぶ() {
        val empty = listOf(on(1, null), on(2, ""), on(3, null))
        assertEquals(3, io.github.danything.denpatv.data.airing(empty).size)
        assertEquals(2L, io.github.danything.denpatv.data.neighbor(empty, 1, 1)?.id)
        // 放送しているのがいまの局だけなら、そのまま
        assertEquals(1L, io.github.danything.denpatv.data.neighbor(listOf(on(1, "a"), on(2, "")), 1, 1)?.id)
    }
}
