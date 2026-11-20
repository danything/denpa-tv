package io.github.danything.denpatv

import io.github.danything.denpatv.data.AirTime
import io.github.danything.denpatv.data.CaptionFeed
import io.github.danything.denpatv.data.CaptionFrame
import io.github.danything.denpatv.data.ClockNotice
import io.github.danything.denpatv.data.PlaybackInfo
import io.github.danything.denpatv.data.Pts
import io.github.danything.denpatv.data.RecordingJikkyo
import io.github.danything.denpatv.data.Ruler
import io.github.danything.denpatv.data.lenientJson
import io.github.danything.denpatv.data.parseRecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** いま映しているコマの放送時刻 W (実況のコメントを合わせる) */
class AirTimeTest {
    private val t = 1_793_188_800_000L

    @Test
    fun 元の_TS_のライブは時計の通知と放送の_PTS_から() {
        // 放送の時計 1000 秒のところを、サーバが t に受け取った
        val clock = ClockNotice(at = 1000.0, unixMs = t)
        assertEquals(1000L * 90_000, clock.atPts)
        assertEquals(t, AirTime.live(clock, 1000L * 90_000))
        // 映しているコマがそれより 2.5 秒後 / 1 秒前
        assertEquals(t + 2_500, AirTime.live(clock, 1000L * 90_000 + 225_000))
        assertEquals(t - 1_000, AirTime.live(clock, 1000L * 90_000 - 90_000))
    }

    @Test
    fun 放送の_PTS_が_33_ビットで一周しても続けて数える() {
        // 一周の 1 秒手前で受け取った時計と、一周して 2 秒たったコマ
        val clock = ClockNotice(at = (Pts.WRAP - 90_000) / 90_000.0, unixMs = t)
        assertEquals(t + 3_000, AirTime.live(clock, 2L * 90_000))
        // 逆向き (時計が一周したあとに、一周の手前のコマ)
        val after = ClockNotice(at = 1.0, unixMs = t)
        assertEquals(t - 2_000, AirTime.live(after, Pts.WRAP - 90_000))
        // Media3 は一周をまたいでも位置を伸ばし続ける。放送の PTS に戻すときに畳むので、W は続いたまま
        // (一周の 1 秒ほど手前から流しはじめた。マイクロ秒に割り切れるよう 9 の倍数にしてある)
        val start = Pts.WRAP - 90_008
        val near = ClockNotice(at = start / 90_000.0, unixMs = t)
        val offsetUs = -start / 9 * 100
        assertEquals(t, AirTime.live(near, Pts.broadcast(0, offsetUs)))
        assertEquals(t + 3_000, AirTime.live(near, Pts.broadcast(3_000, offsetUs)))
    }

    @Test
    fun 変換したライブは_fMP4_の時刻で同じように数える() {
        // 塊の時刻 (tfdt) は 100 秒から始まり、Extractor はそれを 0 に寄せた (寄せ幅 −100 秒)。時計は fMP4 の 101.5 秒を t に受け取った
        val clock = ClockNotice(at = 101.5, unixMs = t)
        val offsetUs = -100_000_000L
        assertEquals(t - 1_500, AirTime.live(clock, Pts.broadcast(0, offsetUs)))
        assertEquals(t + 500, AirTime.live(clock, Pts.broadcast(2_000, offsetUs)))
    }

    @Test
    fun 追っかけは頭の放送時刻と位置から() {
        assertEquals(t + 61_000, AirTime.chase(t, 61_000))
    }

    @Test
    fun 録画は物差しの位置を元の_TS_に直して頭の放送時刻を足す() {
        // 物差しが元の TS
        assertEquals(t + 10_000, AirTime.recording(t, 10_000, null))
        // エンコードで頭を 3 秒捨てた
        assertEquals(t + 13_000, AirTime.recording(t, 10_000, Ruler(skip = 3.0)))
        // CM を切った: 残したのは 5〜65 秒と 125〜185 秒。物差しの 70 秒は 2 つ目の区間の 10 秒目
        val cut = Ruler(listOf(Ruler.KeptRange(5.0, 65.0), Ruler.KeptRange(125.0, 185.0)), skip = 5.0)
        assertEquals(t + 135_000, AirTime.recording(t, 70_000, cut))
        assertEquals(t + 5_000, AirTime.recording(t, 0, cut))
        // 残した区間の後ろは、最後の区間の終わり
        assertEquals(t + 185_000, AirTime.recording(t, 500_000, cut))
    }

    @Test
    fun 観方の_ruler_を読む_古い_denpa_には無い() {
        val info = lenientJson.decodeFromString(
            PlaybackInfo.serializer(),
            """{"duration":10,"axis":"encoded","offers":[],"ruler":{"kept":[{"start":5,"end":65}],"skip":5}}""",
        )
        assertEquals(Ruler(listOf(Ruler.KeptRange(5.0, 65.0)), 5.0), info.ruler)
        assertEquals(Ruler(null, 2.5), lenientJson.decodeFromString(PlaybackInfo.serializer(), """{"ruler":{"kept":null,"skip":2.5}}""").ruler)
        assertNull(lenientJson.decodeFromString(PlaybackInfo.serializer(), """{"duration":10,"axis":"encoded","offers":[]}""").ruler)
    }

    @Test
    fun 録画の状態から実況のチャンネルと頭の放送時刻を読む() {
        assertEquals(
            RecordingJikkyo("jk9", 1_790_000_000_000, 1_790_001_815_000),
            parseRecordingState("""{"id":12,"state":"recording","jikkyo":{"channel":"jk9","head":1790000000000,"end":1790001815000}}""")?.jikkyo,
        )
        assertNull(parseRecordingState("""{"id":12,"state":"recorded","jikkyo":null}""")?.jikkyo)
        assertNull(parseRecordingState("""{"id":12,"state":"recorded"}""")?.jikkyo)
        // 形がずれていたら言って、読めた状態はそのまま
        val warned = mutableListOf<String>()
        val state = parseRecordingState("""{"id":12,"state":"recorded","jikkyo":{"channel":9}}""") { warned += it }
        assertEquals("recorded", state?.state)
        assertNull(state?.jikkyo)
        assertEquals(1, warned.size)
    }

    @Test
    fun 字幕のストリームの時計の通知を読む() {
        val frame = CaptionFeed.read(stream(control("""{"type":"clock","at":72575.147089,"unixMs":1793188800000,"now":1793188800100}""")))
        val notice = (frame as CaptionFrame.Clock).notice
        assertEquals(72575.147089, notice.at, 1e-9)
        assertEquals(t, notice.unixMs)
        // 読めないものは読み捨てる
        assertTrue(CaptionFeed.read(stream(control("""{"type":"clock","at":"x"}"""))) is CaptionFrame.Other)
    }

    private fun control(json: String): ByteArray {
        val payload = json.toByteArray()
        return ByteArrayOutputStream().also { out ->
            DataOutputStream(out).run {
                writeInt(1 + 8 + payload.size)
                writeByte(CaptionFeed.CONTROL)
                writeLong(0)
                write(payload)
            }
        }.toByteArray()
    }

    private fun stream(bytes: ByteArray) = DataInputStream(bytes.inputStream())
}
