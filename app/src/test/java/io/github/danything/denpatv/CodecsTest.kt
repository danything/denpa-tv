package io.github.danything.denpatv

import io.github.danything.denpatv.data.AutoOrder
import io.github.danything.denpatv.data.Decoders
import io.github.danything.denpatv.data.LiveQuality
import io.github.danything.denpatv.data.LiveSelection
import io.github.danything.denpatv.data.PlaybackOptions
import io.github.danything.denpatv.data.number
import io.github.danything.denpatv.data.parsePlaybackOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecsTest {
    private val tv = Decoders(av1 = true, h264 = true, mpeg2 = true, av1Hardware = true, mpeg2Hardware = true)
    private val stick = Decoders(av1 = true, h264 = true, mpeg2 = true, av1Hardware = false, mpeg2Hardware = false)

    /** denpa の `autoOrder` と同じ並び (docs/stream.md の「どの形式で流すか」) */
    @Test
    fun 自動の順は_denpa_と同じ() {
        assertEquals(listOf("raw", "h264", "av1", "hevc"), AutoOrder.onTheFly(indoor = true))
        assertEquals(listOf("av1", "hevc", "h264"), AutoOrder.onTheFly(indoor = false))
        assertEquals(listOf("av1", "hevc", "h264"), AutoOrder.encoded)
    }

    /** denpa の `api/playback/options`。`indoor` が読めなければ null (呼ぶ側は控えない)。`hidden` に `h264` があれば H.264 を外す */
    @Test
    fun 観るときの決まりを読む() {
        assertEquals(PlaybackOptions(indoor = false, hideH264 = true), parsePlaybackOptions("""{"indoor":false,"hidden":["h264","hevc"],"hevc":true}"""))
        assertEquals(PlaybackOptions(indoor = true, hideH264 = false), parsePlaybackOptions("""{"indoor":true,"hidden":[]}"""))
        assertEquals(PlaybackOptions(indoor = true, hideH264 = false), parsePlaybackOptions("""{"indoor":true}"""))
        assertNull(parsePlaybackOptions("{}"))
        assertNull(parsePlaybackOptions("<html>"))
    }

    @Test
    fun 自動は家の中なら_MPEG2_から_外なら_AV1_から_この端末で流せるもの() {
        assertEquals(LiveQuality.Raw, LiveQuality.auto(tv, indoor = true))
        assertEquals(LiveQuality.Av1, LiveQuality.auto(tv, indoor = false))
        // MPEG-2 も AV1 もハードで無ければ、家の中でも外でも H.264
        assertEquals(LiveQuality.H264, LiveQuality.auto(stick, indoor = true))
        assertEquals(LiveQuality.H264, LiveQuality.auto(stick, indoor = false))
        // 外では MPEG-2 を選ばない (AV1 が無ければ H.264)
        assertEquals(LiveQuality.H264, LiveQuality.auto(tv.copy(av1Hardware = false), indoor = false))
        // 家の中で MPEG-2 が無ければ H.264 (AV1 より先)
        assertEquals(LiveQuality.H264, LiveQuality.auto(tv.copy(mpeg2Hardware = false), indoor = true))
    }

    /** ブラウザのコーデックのメニューと同じ並び (自動 → AV1 → H.265 → H.264 → MPEG-2) から、流せないものと外したものを除く */
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
        assertNull(LiveQuality.read("Av1", tv.copy(av1Hardware = false), hidden = false))
        assertNull(LiveQuality.read("壊れた値", tv, hidden = false))
        assertNull(LiveQuality.read(null, tv, hidden = false))
    }

    @Test
    fun 選んだものは家の中でも外でもそのまま_自動は場所で決まり_いまの画質を添える() {
        val outdoorAv1 = LiveSelection.of(LiveQuality.Raw, tv, indoor = false, hideH264 = false)
        assertEquals(LiveQuality.Raw, outdoorAv1.format)
        assertEquals(LiveSelection.AUTO, outdoorAv1.autoLabel)
        val auto = LiveSelection.of(null, tv, indoor = true, hideH264 = false)
        assertEquals(LiveQuality.Raw, auto.format)
        assertEquals("自動 (MPEG-2)", auto.autoLabel)
        // H.264 を外しても、AV1 を流せない端末の自動は H.264
        val hidden = LiveSelection.of(null, stick, indoor = false, hideH264 = true)
        assertEquals(LiveQuality.H264, hidden.format)
        assertEquals("自動 (H.264)", hidden.autoLabel)
        assertEquals(emptyList<LiveQuality>(), hidden.menu)
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
