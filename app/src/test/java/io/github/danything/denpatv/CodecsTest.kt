package io.github.danything.denpatv

import io.github.danything.denpatv.data.Decoders
import io.github.danything.denpatv.data.RecordingFile
import io.github.danything.denpatv.data.LiveQuality
import io.github.danything.denpatv.data.number
import io.github.danything.denpatv.data.pickFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecsTest {
    private val files = listOf(
        RecordingFile("encoded", "av1", "api/recordings/1/file?source=encoded"),
        RecordingFile("alt", "h264", "api/recordings/1/file?source=alt"),
        RecordingFile("ts", "mpeg2", "api/recordings/1/file?source=ts"),
    )

    @Test
    fun 解ける中で軽いものを選ぶ() {
        assertEquals("av1", pickFile(files, Decoders(av1 = true, h264 = true, mpeg2 = true))?.codec)
        assertEquals("h264", pickFile(files, Decoders(av1 = false, h264 = true, mpeg2 = true))?.codec)
        assertEquals("mpeg2", pickFile(files.drop(1).takeLast(1), Decoders(av1 = true, h264 = true, mpeg2 = true))?.codec)
    }

    @Test
    fun 解けるものが無ければ_null() {
        assertNull(pickFile(files.take(1), Decoders(av1 = false, h264 = true, mpeg2 = true)))
    }

    @Test
    fun ライブの既定は_MPEG2_をハードで解ければ低遅延_そうでなければ_H264() {
        val tv = Decoders(av1 = true, h264 = true, mpeg2 = true, av1Hardware = true, mpeg2Hardware = true)
        assertEquals(LiveQuality.Raw, LiveQuality.choose(null, tv))
        val stick = Decoders(av1 = true, h264 = true, mpeg2 = true, av1Hardware = false, mpeg2Hardware = false)
        assertEquals(LiveQuality.H264, LiveQuality.choose(null, stick))
        assertEquals(listOf(LiveQuality.H264), LiveQuality.available(stick))
        // 並びと名前はブラウザの画質のメニューと同じ
        assertEquals(listOf("H.264", "AV1", "MPEG-2"), LiveQuality.available(tv).map { it.label })
    }

    @Test
    fun 覚えた画質が選べなくなっていたら既定に戻す() {
        val tv = Decoders(av1 = true, h264 = true, mpeg2 = true, av1Hardware = true, mpeg2Hardware = true)
        assertEquals(LiveQuality.Av1, LiveQuality.choose("Av1", tv))
        val noAv1 = tv.copy(av1Hardware = false)
        assertEquals(LiveQuality.Raw, LiveQuality.choose("Av1", noAv1))
        assertEquals(LiveQuality.Raw, LiveQuality.choose("壊れた値", tv))
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
