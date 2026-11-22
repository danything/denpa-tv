package io.github.danything.denpatv

import android.view.KeyEvent
import io.github.danything.denpatv.data.AppCookie
import io.github.danything.denpatv.data.AribKey
import io.github.danything.denpatv.data.CaptionFeed
import io.github.danything.denpatv.data.CaptionFrame
import io.github.danything.denpatv.data.DataFeed
import io.github.danything.denpatv.data.DataFrame
import io.github.danything.denpatv.data.DataKey
import io.github.danything.denpatv.data.DataKeyAction
import io.github.danything.denpatv.data.DataKeys
import io.github.danything.denpatv.data.DataPage
import io.github.danything.denpatv.data.DataPaths
import io.github.danything.denpatv.data.PageMessage
import io.github.danything.denpatv.data.PlaybackInfo
import io.github.danything.denpatv.data.VideoPlace
import io.github.danything.denpatv.data.WebOrigin
import io.github.danything.denpatv.data.dataKey
import io.github.danything.denpatv.data.lenientJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URI

class DataBroadcastTest {
    private fun input(vararg frames: Pair<Int, String>): DataInputStream {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        for ((kind, payload) in frames) {
            val body = payload.toByteArray()
            out.writeInt(1 + 8 + body.size)
            out.writeByte(kind)
            out.writeLong(0)
            out.write(body)
        }
        return DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
    }

    /** 開いている間にページへ渡すキーは AribKeyCode に直す。CH±・Menu・番組表・情報・再生のキーと決定・戻るは渡さない (決定と戻るは別の受け口から) */
    @Test
    fun キーをAribKeyCodeに直す() {
        assertEquals(DataKey.D, dataKey(KeyEvent.KEYCODE_TV_DATA_SERVICE))
        assertEquals(DataKey.Key(AribKey.UP), dataKey(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(DataKey.Key(AribKey.DOWN), dataKey(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(DataKey.Key(AribKey.LEFT), dataKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(DataKey.Key(AribKey.RIGHT), dataKey(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(DataKey.Key(21), dataKey(KeyEvent.KEYCODE_PROG_BLUE))
        assertEquals(DataKey.Key(22), dataKey(KeyEvent.KEYCODE_PROG_RED))
        assertEquals(DataKey.Key(23), dataKey(KeyEvent.KEYCODE_PROG_GREEN))
        assertEquals(DataKey.Key(24), dataKey(KeyEvent.KEYCODE_PROG_YELLOW))
        assertEquals(DataKey.Key(5), dataKey(KeyEvent.KEYCODE_0))
        assertEquals(DataKey.Key(14), dataKey(KeyEvent.KEYCODE_9))
        assertEquals(DataKey.Key(6), dataKey(KeyEvent.KEYCODE_NUMPAD_1))
        assertEquals(DataKey.Key(16), dataKey(KeyEvent.KEYCODE_11))
        assertEquals(DataKey.Key(17), dataKey(KeyEvent.KEYCODE_12))
        for (tv in listOf(
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK,
        )) {
            assertNull(dataKey(tv))
        }
        assertEquals(18, AribKey.ENTER)
        assertEquals(19, AribKey.BACK)
    }

    /** 押したのを渡したキーは離したのも渡す。繰り返しは渡さない。閉じている間は十字などを TV に残し、d は開けるときだけ受ける */
    @Test
    fun ページへ渡すキー() {
        val keys = DataKeys()
        val right = KeyEvent.KEYCODE_DPAD_RIGHT
        assertSame(DataKeyAction.Pass, keys.down(right, 0, open = false, canOpen = true))
        assertSame(DataKeyAction.Pass, keys.up(right))
        assertEquals(DataKeyAction.Send(AribKey.RIGHT, true), keys.down(right, 0, open = true, canOpen = true))
        assertSame(DataKeyAction.Hold, keys.down(right, 1, open = true, canOpen = true))
        // 閉じたあとに離しても、押したのを渡していれば離したのも渡す
        assertEquals(DataKeyAction.Send(AribKey.RIGHT, false), keys.up(right))
        assertSame(DataKeyAction.Pass, keys.up(right))
        assertSame(DataKeyAction.D, keys.down(KeyEvent.KEYCODE_TV_DATA_SERVICE, 0, open = false, canOpen = true))
        assertSame(DataKeyAction.Hold, keys.down(KeyEvent.KEYCODE_TV_DATA_SERVICE, 1, open = false, canOpen = true))
        assertSame(DataKeyAction.Pass, keys.down(KeyEvent.KEYCODE_TV_DATA_SERVICE, 0, open = false, canOpen = false))
        assertSame(DataKeyAction.Pass, keys.down(KeyEvent.KEYCODE_CHANNEL_UP, 0, open = true, canOpen = true))
        keys.down(KeyEvent.KEYCODE_PROG_RED, 0, open = true, canOpen = true)
        keys.clear()
        assertSame(DataKeyAction.Pass, keys.up(KeyEvent.KEYCODE_PROG_RED))
    }

    @Test
    fun ページへ送るもの() {
        val live = lenientJson.parseToJsonElement(DataPage.initLive(3239123608, open = true, receive = false, captions = true)) as JsonObject
        assertEquals("init", live["type"]!!.jsonPrimitive.content)
        assertEquals("live", live["mode"]!!.jsonPrimitive.content)
        assertEquals("3239123608", live["service"]!!.jsonPrimitive.content)
        assertEquals("true", live["open"]!!.jsonPrimitive.content)
        assertEquals("false", live["receive"]!!.jsonPrimitive.content)
        val recording = lenientJson.parseToJsonElement(DataPage.initRecording(12, open = false, receive = true, captions = false)) as JsonObject
        assertEquals("recording", recording["mode"]!!.jsonPrimitive.content)
        assertEquals("12", recording["recording"]!!.jsonPrimitive.content)
        // denpa から届いた JSON はそのまま埋める
        assertEquals("""{"type":"data","message":{"type":"pmt","x":1}}""", DataPage.data("""{"type":"pmt","x":1}"""))
        assertEquals("""{"type":"notice","message":{"type":"card","id":"1"}}""", DataPage.notice("""{"type":"card","id":"1"}"""))
        val time = lenientJson.parseToJsonElement(DataPage.time(null, 1500, 1.5f, paused = true)) as JsonObject
        assertEquals("null", time["air"].toString())
        assertEquals("1500", time["pos"]!!.jsonPrimitive.content)
        assertEquals("1.5", time["rate"]!!.jsonPrimitive.content)
        assertEquals("true", time["paused"]!!.jsonPrimitive.content)
        assertEquals("""{"type":"key","code":18,"down":true}""", DataPage.key(18, true))
        assertEquals("""{"type":"channel","service":7}""", DataPage.channel(7))
        assertEquals("""{"type":"receive","on":false}""", DataPage.receive(false))
        assertEquals("""{"type":"captions","on":true}""", DataPage.captions(true))
    }

    @Test
    fun ページから届いたものを読む() {
        assertSame(PageMessage.Ready, DataPage.parse("""{"type":"ready"}"""))
        assertEquals(PageMessage.Failed("engine"), DataPage.parse("""{"type":"failed","reason":"engine"}"""))
        assertEquals(PageMessage.Open(true), DataPage.parse("""{"type":"open","on":true}"""))
        assertEquals(PageMessage.Tune(3239123608), DataPage.parse("""{"type":"tune","service":3239123608,"onid":4,"tsid":null,"sid":1}"""))
        assertEquals(PageMessage.Audio(1), DataPage.parse("""{"type":"audio","index":1}"""))
        assertEquals(PageMessage.Audio(null), DataPage.parse("""{"type":"audio","index":null}"""))
        assertEquals(PageMessage.Captions(false), DataPage.parse("""{"type":"captions","on":false}"""))
        assertEquals(PageMessage.Note("データ取得中…"), DataPage.parse("""{"type":"note","text":"データ取得中…"}"""))
        assertEquals(PageMessage.Note(""), DataPage.parse("""{"type":"note","text":""}"""))
        assertEquals(PageMessage.Toast("録画の再生中は選局できません"), DataPage.parse("""{"type":"toast","text":"録画の再生中は選局できません"}"""))
        // 知らない type・形の合わないもの・JSON でないものは読み捨てる
        assertNull(DataPage.parse("""{"type":"later"}"""))
        assertNull(DataPage.parse("""{"type":"open"}"""))
        assertNull(DataPage.parse("""{"type":"audio","index":-1}"""))
        assertNull(DataPage.parse("not json"))
        assertNull(DataPage.parse(null))
    }

    @Test
    fun 映像の場所() {
        assertEquals(PageMessage.Video(VideoPlace.Full), DataPage.parse("""{"type":"video","rect":null}"""))
        assertEquals(PageMessage.Video(VideoPlace.Hidden), DataPage.parse("""{"type":"video","rect":"hidden"}"""))
        assertEquals(
            PageMessage.Video(VideoPlace.Rect(0.5f, 0.25f, 0.5f, 0.5f)),
            DataPage.parse("""{"type":"video","rect":{"left":0.5,"top":0.25,"width":0.5,"height":0.5}}"""),
        )
        // 枠からはみ出すものは枠に収める
        assertEquals(
            PageMessage.Video(VideoPlace.Rect(0.75f, 0f, 0.25f, 1f)),
            DataPage.parse("""{"type":"video","rect":{"left":0.75,"top":-0.1,"width":0.5,"height":1.2}}"""),
        )
        assertNull(DataPage.parse("""{"type":"video","rect":{"left":0,"top":0,"width":0,"height":1}}"""))
        assertNull(DataPage.parse("""{"type":"video","rect":"full"}"""))
    }

    /** 0x30 はそのまま、0x40 はページが読む card・hybridcast だけ。ping とほかの種別は読み捨てる */
    @Test
    fun ライブのデータのストリームを読む() {
        val stream = input(
            0x30 to """{"type":"moduleDownloaded","data":"AAAA"}""",
            0x40 to """{"type":"card","id":"0123456789012345"}""",
            0x40 to """{"type":"hybridcast","apps":[]}""",
            0x40 to """{"type":"ping"}""",
            0x30 to "not json",
            0x22 to "{}",
        )
        assertEquals("""{"type":"moduleDownloaded","data":"AAAA"}""", (DataFeed.read(stream) as DataFrame.Data).json)
        assertEquals("""{"type":"card","id":"0123456789012345"}""", (DataFeed.read(stream) as DataFrame.Notice).json)
        assertEquals("""{"type":"hybridcast","apps":[]}""", (DataFeed.read(stream) as DataFrame.Notice).json)
        assertSame(DataFrame.Other, DataFeed.read(stream))
        assertSame(DataFrame.Other, DataFeed.read(stream))
        assertSame(DataFrame.Other, DataFeed.read(stream))
        assertNull(DataFeed.read(stream))
    }

    /** 字幕のストリームの `autoStart` (データ放送の入口の自動起動の指定) */
    @Test
    fun 字幕のストリームのautoStart() {
        val stream = input(
            0x40 to """{"type":"autoStart","on":true}""",
            0x40 to """{"type":"autoStart","on":false}""",
            0x40 to """{"type":"autoStart"}""",
        )
        assertTrue((CaptionFeed.read(stream) as CaptionFrame.AutoStart).on)
        assertFalse((CaptionFeed.read(stream) as CaptionFrame.AutoStart).on)
        assertSame(CaptionFrame.Other, CaptionFeed.read(stream))
    }

    @Test
    fun WebViewのCookie() {
        val cookie = AppCookie.parse("""{"name":"denpa_app","value":"abc123","maxAge":2592000}""")!!
        assertEquals(
            "denpa_app=abc123; Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict",
            cookie.header(URI("http://192.168.1.10:3000/")),
        )
        // 前段の接頭辞の下なら接頭辞、https なら Secure
        assertEquals(
            "denpa_app=abc123; Max-Age=2592000; Path=/denpa/; HttpOnly; SameSite=Strict; Secure",
            cookie.header(URI("https://example.test/denpa/")),
        )
        assertNull(AppCookie.parse("""{"name":"denpa_app","value":"a; Path=/x","maxAge":1}"""))
        assertNull(AppCookie.parse("""{"name":"denpa_app","value":"","maxAge":1}"""))
        assertNull(AppCookie.parse("""{"name":"denpa_app","value":"a"}"""))
        assertNull(AppCookie.parse("nope"))
    }

    @Test
    fun オリジン() {
        assertEquals("http://192.168.1.10:3000", WebOrigin.of(URI("http://192.168.1.10:3000/")))
        // 既定のポートは書かない (ページの location.origin と同じにする)
        assertEquals("http://denpa.local", WebOrigin.of(URI("http://denpa.local:80/denpa/")))
        assertEquals("https://example.test", WebOrigin.of(URI("https://Example.test:443/")))
        val base = URI("http://192.168.1.10:3000/denpa/")
        assertTrue(WebOrigin.same(base, "http://192.168.1.10:3000/denpa/embed/data"))
        assertTrue(WebOrigin.same(URI("http://denpa.local/"), "http://denpa.local:80/x"))
        assertFalse(WebOrigin.same(base, "http://192.168.1.10:8080/denpa/embed/data"))
        assertFalse(WebOrigin.same(base, "https://192.168.1.10:3000/"))
        assertFalse(WebOrigin.same(base, "https://www.nhk.or.jp/"))
        assertFalse(WebOrigin.same(base, "about:blank"))
        assertFalse(WebOrigin.same(base, "::"))
    }

    @Test
    fun 場所() {
        assertEquals("api/services/3239123608/data", DataPaths.live("api/services/3239123608/live"))
        assertEquals("api/services/1/data", DataPaths.live("api/services/1/live?x=1"))
        assertNull(DataPaths.live("api/other"))
    }

    /** 録画の観方の `data`。取り出していなければ null */
    @Test
    fun 録画のデータ放送() {
        val with = lenientJson.decodeFromString(PlaybackInfo.serializer(), """{"duration":10,"data":{"autoStart":true}}""")
        assertEquals(true, with.data?.autoStart)
        val without = lenientJson.decodeFromString(PlaybackInfo.serializer(), """{"duration":10,"data":null}""")
        assertNull(without.data)
        assertNull(lenientJson.decodeFromString(PlaybackInfo.serializer(), """{"duration":10}""").data)
    }
}
