package io.github.danything.denpatv

import io.github.danything.denpatv.data.CaptionCue
import io.github.danything.denpatv.data.CaptionFeed
import io.github.danything.denpatv.data.CaptionFrame
import io.github.danything.denpatv.data.CaptionPaths
import io.github.danything.denpatv.data.CueTimeline
import io.github.danything.denpatv.data.Pts
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class CaptionsTest {
    /** denpa の `appFrame` と同じ形: [4:後ろの長さ][1:種別][8:時刻][中身] */
    private fun frame(out: DataOutputStream, kind: Int, pts: Long, payload: ByteArray) {
        out.writeInt(1 + 8 + payload.size)
        out.writeByte(kind)
        out.writeLong(pts)
        out.write(payload)
    }

    private fun picture(x: Int, y: Int, w: Int, h: Int, png: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeShort(x); writeShort(y); writeShort(w); writeShort(h); write(png)
        }
        return bytes.toByteArray()
    }

    private fun input(write: (DataOutputStream) -> Unit): DataInputStream {
        val bytes = ByteArrayOutputStream()
        write(DataOutputStream(bytes))
        return DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
    }

    @Test
    fun 字幕の絵と知らせを順に読み_閉じたら終わる() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val stream = input { out ->
            frame(out, 0x40, 0, """{"type":"captions","tracks":[{"index":0,"lang":"jpn","label":"字幕 (日本語)"}],"track":0}""".toByteArray())
            frame(out, 0x20, 0x1_2345_6789L, picture(0, 0, 1920, 1080, png))
            frame(out, 0x40, 0, """{"type":"ping"}""".toByteArray())
            // 知らない種別は読み捨てる
            frame(out, 0x30, 0, "{}".toByteArray())
        }
        val tracks = CaptionFeed.read(stream) as CaptionFrame.Tracks
        assertEquals(1, tracks.count)
        val cue = (CaptionFeed.read(stream) as CaptionFrame.Cue).cue
        assertEquals(0x1_2345_6789L, cue.pts)
        assertEquals(listOf(0, 0, 1920, 1080), listOf(cue.x, cue.y, cue.width, cue.height))
        assertArrayEquals(png, cue.png)
        assertSame(CaptionFrame.Other, CaptionFeed.read(stream))
        assertSame(CaptionFrame.Other, CaptionFeed.read(stream))
        assertNull(CaptionFeed.read(stream))
    }

    @Test
    fun 字幕を持たない放送は0本_形の違う知らせは読み捨てる() {
        val stream = input { out ->
            frame(out, 0x40, 0, """{"type":"captions","tracks":[],"track":0}""".toByteArray())
            frame(out, 0x40, 0, "not json".toByteArray())
        }
        assertEquals(0, (CaptionFeed.read(stream) as CaptionFrame.Tracks).count)
        assertSame(CaptionFrame.Other, CaptionFeed.read(stream))
    }

    @Test(expected = IOException::class)
    fun 途中で切れたら転ぶ() {
        val stream = input { out ->
            out.writeInt(1 + 8 + 100)
            out.writeByte(0x20)
            out.writeLong(0)
            out.write(ByteArray(10))
        }
        CaptionFeed.read(stream)
    }

    @Test(expected = IOException::class)
    fun 長さがおかしければ転ぶ() {
        CaptionFeed.read(input { it.writeInt(Int.MAX_VALUE) })
    }

    @Test
    fun 再生位置を放送のPTSに戻す() {
        // 最初の PTS が 10 秒 (900000) のとき、Media3 は寄せ幅 −10 秒で 0 に寄せる
        val offsetUs = -10_000_000L
        assertEquals(900_000L, Pts.broadcast(0, offsetUs))
        assertEquals(900_000L + 90 * 1_500, Pts.broadcast(1_500, offsetUs))
        // シークして 60 秒から始めたなら、寄せ幅はそのぶん足される (サンプルの時刻 = 放送の時刻 + 幅)
        assertEquals(900_000L, Pts.broadcast(60_000, 60_000_000L + offsetUs))
    }

    @Test
    fun 一周をまたいでも33ビットに畳む() {
        // 一周の 0.7 秒ほど手前 (95443 秒) から始めた。読み手は伸ばし続けるので、2 秒後は 2^33 を超えたところになる
        val offsetUs = -95_443_000_000L
        assertEquals(8_589_870_000L, Pts.broadcast(0, offsetUs))
        assertEquals(8_589_870_000L + 180_000 - Pts.WRAP, Pts.broadcast(2_000, offsetUs))
    }

    @Test
    fun 近いほうへ伸ばして比べる() {
        assertEquals(90_000L, Pts.delta(90_000, 0))
        assertEquals(-90_000L, Pts.delta(0, 90_000))
        // 一周をまたいだ: 小さい数のほうが後
        assertEquals(180_000L, Pts.delta(90_000, Pts.WRAP - 90_000))
        assertEquals(-180_000L, Pts.delta(Pts.WRAP - 90_000, 90_000))
    }

    private fun cue(pts: Long) = CaptionCue(pts, 0, 0, 1920, 1080, ByteArray(0))

    @Test
    fun 時計を過ぎた中で最後の1枚を出し_前のものは捨てる() {
        val timeline = CueTimeline()
        val a = cue(1_000)
        val b = cue(2_000)
        val c = cue(3_000)
        listOf(a, b, c).forEach(timeline::add)
        assertNull(timeline.at(500))
        assertSame(a, timeline.at(1_000))
        assertSame(b, timeline.at(2_500))
        assertEquals(2, timeline.size())
        assertSame(c, timeline.at(10_000))
        assertEquals(1, timeline.size())
    }

    @Test
    fun 一周をまたいだ字幕も順に出す() {
        val timeline = CueTimeline()
        val before = cue(Pts.WRAP - 9_000)
        val after = cue(9_000)
        timeline.add(before)
        timeline.add(after)
        assertSame(before, timeline.at(Pts.WRAP - 1))
        assertSame(after, timeline.at(9_000))
    }

    @Test
    fun 字幕の口の場所() {
        assertEquals("api/services/3227310008/captions", CaptionPaths.live("api/services/3227310008/live"))
        assertEquals("api/recordings/12/captions", CaptionPaths.recording(12))
        assertTrue(CaptionPaths.live("api/services/1/live?codec=raw").endsWith("/1/captions"))
    }
}
