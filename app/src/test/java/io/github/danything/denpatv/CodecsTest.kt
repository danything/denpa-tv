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

    private val shown = PlaybackOptions(hideH264 = false)
    private val hidden = PlaybackOptions(hideH264 = true)
    private fun live(vararg codecs: LiveQuality) = PlaybackOptions(hideH264 = true, liveCodecs = codecs.toList())

    /** denpa の `api/playback/options`。`hidden` も `liveCodecs` も読めなければ null (呼ぶ側は控えない)。`hidden` に `h264` があれば H.264 を外す */
    @Test
    fun 観るときの決まりを読む() {
        assertEquals(PlaybackOptions(hideH264 = true), parsePlaybackOptions("""{"indoor":false,"hidden":["h264","hevc"],"hevc":true}"""))
        assertEquals(PlaybackOptions(hideH264 = false), parsePlaybackOptions("""{"indoor":true,"hidden":[]}"""))
        assertNull(parsePlaybackOptions("""{"indoor":true}"""))
        assertNull(parsePlaybackOptions("<html>"))
    }

    /** v2.45.0 からの denpa は `liveCodecs` (その場でエンコードできる並び) も返す。知らない値は読み飛ばし、放送のまま (`raw`) は入れない */
    @Test
    fun 観るときの決まりの_liveCodecs_を読む() {
        assertEquals(
            PlaybackOptions(hideH264 = true, liveCodecs = listOf(LiveQuality.Hevc, LiveQuality.H264)),
            parsePlaybackOptions("""{"indoor":true,"liveCodecs":["hevc","h264"],"hidden":["av1","h264"],"hevc":true}"""),
        )
        assertEquals(
            PlaybackOptions(hideH264 = false, liveCodecs = listOf(LiveQuality.H264)),
            parsePlaybackOptions("""{"indoor":true,"liveCodecs":["vvc","raw","h264"],"hidden":["av1","hevc"],"hevc":false}"""),
        )
        // hidden が無くても liveCodecs が読めれば決まりとして使う
        assertEquals(PlaybackOptions(hideH264 = false, liveCodecs = listOf(LiveQuality.Av1)), parsePlaybackOptions("""{"liveCodecs":["av1"]}"""))
    }

    /** `liveCodecs` の形式が違えば (denpa のバージョンのずれ) `warn` に書き、止めずに `hidden` で決める */
    @Test
    fun liveCodecs_の形式が違えば_hidden_で決める() {
        val warnings = mutableListOf<String>()
        assertEquals(
            PlaybackOptions(hideH264 = true),
            parsePlaybackOptions("""{"liveCodecs":"hevc","hidden":["h264","hevc"]}""") { warnings += it },
        )
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("liveCodecs"))
        assertEquals(PlaybackOptions(hideH264 = false), parsePlaybackOptions("""{"liveCodecs":[{"id":"h264"}],"hidden":[]}""") { warnings += it })
        assertEquals(2, warnings.size)
        // どちらも読めなければ null
        assertNull(parsePlaybackOptions("""{"liveCodecs":null}""") { warnings += it })
    }

    /** `liveCodecs` が無い古い denpa: ブラウザのコーデックのメニューと同じ並び (自動 → AV1 → H.265 → H.264 → MPEG-2) から、選べないもの (H.265 はいつも) と外したものを除く */
    @Test
    fun メニューの並び() {
        assertEquals(listOf("AV1", "H.264", "MPEG-2"), LiveQuality.menu(tv, shown).map { it.label })
        assertEquals(listOf("AV1", "MPEG-2"), LiveQuality.menu(tv, hidden).map { it.label })
        assertEquals(listOf("H.264"), LiveQuality.menu(stick, shown).map { it.label })
        assertEquals(emptyList<LiveQuality>(), LiveQuality.menu(stick, hidden))
    }

    /**
     * `liveCodecs` を返す denpa: エンコードするものは、その先頭から端末で再生できるものを 1 つだけ並べる (ブラウザの `shownCodec` と同じ)。
     * `hidden` (先頭以外を外す) は端末で再生できるかを見ないので、端末が先頭を再生できなくても次のものを並べる
     */
    @Test
    fun メニューは_liveCodecs_から再生できる最初のもの() {
        // Intel Pentium Gold 8505 (VA-API で H.264・H.265) の denpa
        val vaapi = live(LiveQuality.Hevc, LiveQuality.H264)
        assertEquals(listOf("H.265", "MPEG-2"), LiveQuality.menu(tv, vaapi).map { it.label })
        assertEquals(listOf("H.264"), LiveQuality.menu(stick, vaapi).map { it.label })
        // 追っかけは H.265 を出さないので、次のもの
        assertEquals(listOf("H.264", "MPEG-2"), LiveQuality.menu(tv, vaapi, chase = true).map { it.label })
        // GPU で AV1 も出せる denpa でも、AV1 をハードでデコードできない端末は次の H.264
        val av1 = live(LiveQuality.Av1, LiveQuality.Hevc, LiveQuality.H264)
        assertEquals(listOf("AV1", "MPEG-2"), LiveQuality.menu(tv, av1).map { it.label })
        assertEquals(listOf("H.264"), LiveQuality.menu(stick, av1).map { it.label })
        // GPU が無い denpa は H.264 だけ。AV1 をデコードできる端末でも AV1 は並べない (その場でエンコードできない)
        assertEquals(listOf("H.264", "MPEG-2"), LiveQuality.menu(tv, live(LiveQuality.H264)).map { it.label })
        // 再生できるものが無ければ、エンコードするものは並べない
        assertEquals(listOf("MPEG-2"), LiveQuality.menu(tv.copy(av1Hardware = false), live(LiveQuality.Av1)).map { it.label })
    }

    @Test
    fun 覚えた選択を読む_選べない値と外した_H264_は自動() {
        assertEquals(LiveQuality.Av1, LiveQuality.read("Av1", tv, shown))
        assertEquals(LiveQuality.Raw, LiveQuality.read("Raw", tv, hidden))
        assertEquals(LiveQuality.H264, LiveQuality.read("H264", tv, shown))
        assertNull(LiveQuality.read("H264", tv, hidden))
        assertNull(LiveQuality.read("Hevc", tv, shown))
        assertNull(LiveQuality.read("Av1", tv.copy(av1Hardware = false), shown))
        assertNull(LiveQuality.read("壊れた値", tv, shown))
        assertNull(LiveQuality.read(null, tv, shown))
    }

    /** 覚えた選択がいまメニューに並んでいなければ (denpa の GPU が変わった・ソフトウェアに降りている) 自動。並んでいれば選んだもの */
    @Test
    fun 覚えた選択を読む_liveCodecs_に無いものは自動() {
        val vaapi = live(LiveQuality.Hevc, LiveQuality.H264)
        assertEquals(LiveQuality.Hevc, LiveQuality.read("Hevc", tv, vaapi))
        assertNull(LiveQuality.read("Av1", tv, vaapi))
        assertNull(LiveQuality.read("H264", tv, vaapi))
        assertEquals(LiveQuality.H264, LiveQuality.read("H264", tv, live(LiveQuality.H264)))
        assertNull(LiveQuality.read("Hevc", tv, live(LiveQuality.H264)))
        assertEquals(LiveQuality.Raw, LiveQuality.read("Raw", tv, vaapi))
    }

    /** 録画に譲ったライブの追っかけは H.265 を出さないので、ライブで H.265 を選んでいれば自動でリクエストする */
    @Test
    fun 譲った追っかけでは_H265_を自動にする() {
        assertNull(LiveQuality.forChase(LiveQuality.Hevc))
        assertEquals(LiveQuality.H264, LiveQuality.forChase(LiveQuality.H264))
        assertEquals(LiveQuality.Raw, LiveQuality.forChase(LiveQuality.Raw))
        assertNull(LiveQuality.forChase(null))
    }

    /** 自動の名前には、応答ヘッダで受け取った形式を添える。選んだものはその名前だけ */
    @Test
    fun 自動の名前に流れている形式を添える() {
        val auto = LiveSelection.of(null, tv, shown)
        assertEquals("自動 (MPEG-2)", auto.autoLabel(LiveQuality.Raw))
        assertEquals("自動 (H.265)", auto.autoLabel(LiveQuality.Hevc))
        assertEquals("自動", auto.autoLabel(null))
        assertEquals(LiveSelection.AUTO, LiveSelection.of(LiveQuality.Raw, tv, shown).autoLabel(LiveQuality.Raw))
        assertEquals(emptyList<LiveQuality>(), LiveSelection.of(null, stick, hidden).menu)
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
    fun denpa_が返す_airing_を番組名より先に見る() {
        // 総合2 が総合1 と同じ名前の番組を流している (denpa-tv#131)。denpa は false を返す
        val same = listOf(
            on(1, "大相撲").copy(listed = true),
            on(2, "大相撲").copy(listed = false),
            on(3, "アニメ").copy(listed = true),
        )
        assertEquals(listOf(1L, 3L), io.github.danything.denpatv.data.airing(same).map { it.id })
        assertEquals(3L, io.github.danything.denpatv.data.neighbor(same, 1, 1)?.id)
        // 名前が空でも、denpa が true なら並べる
        val nameless = listOf(on(4, "").copy(listed = true), on(5, "野球").copy(listed = false))
        assertEquals(listOf(4L), io.github.danything.denpatv.data.airing(nameless).map { it.id })
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
