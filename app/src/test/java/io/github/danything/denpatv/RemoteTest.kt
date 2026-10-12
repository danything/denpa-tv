package io.github.danything.denpatv

import android.view.KeyEvent
import io.github.danything.denpatv.data.CenterPress
import io.github.danything.denpatv.data.LiveCommand
import io.github.danything.denpatv.data.liveCenter
import io.github.danything.denpatv.data.RecordingCommand
import io.github.danything.denpatv.data.UpKey
import io.github.danything.denpatv.data.UpToClose
import io.github.danything.denpatv.data.NowProgram
import io.github.danything.denpatv.data.Service
import io.github.danything.denpatv.data.liveChannelKey
import io.github.danything.denpatv.data.liveCommand
import io.github.danything.denpatv.data.remoteKeyService
import io.github.danything.denpatv.data.RecordingCenter
import io.github.danything.denpatv.data.recordingCenter
import io.github.danything.denpatv.data.recordingCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteTest {
    /**
     * ライブ: 左右とチャンネル送りで前・次の局。下と Menu はメニュー (操作の列と局の列)、上は局の列に合わせて開くメニュー。
     * 決定は PlayerFrame が短押し・長押しに分け、短押しはメニュー、長押しは情報キーと同じ (番組の詳細)
     */
    @Test
    fun ライブのキー() {
        assertEquals(LiveCommand.PreviousChannel, liveCommand(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(LiveCommand.PreviousChannel, liveCommand(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals(LiveCommand.NextChannel, liveCommand(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(LiveCommand.NextChannel, liveCommand(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(LiveCommand.Menu, liveCommand(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(LiveCommand.Menu, liveCommand(KeyEvent.KEYCODE_MENU))
        assertEquals(LiveCommand.Channels, liveCommand(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(LiveCommand.Details, liveCommand(KeyEvent.KEYCODE_INFO))
        assertNull(liveCommand(KeyEvent.KEYCODE_DPAD_CENTER))
        // 戻るは受けない (何も開いていなければメニューの画面へ戻る)
        assertNull(liveCommand(KeyEvent.KEYCODE_BACK))
        assertEquals(LiveCommand.Menu, liveCenter(CenterPress.Action.Short))
        assertEquals(LiveCommand.Details, liveCenter(CenterPress.Action.Long))
    }

    /** ライブの数字キー: 押した番号の局 (リモコン番号)。0 は 10、11・12 は日本向けのリモコンのボタン。テンキーも同じ */
    @Test
    fun ライブの数字キー() {
        assertEquals(1, liveChannelKey(KeyEvent.KEYCODE_1))
        assertEquals(9, liveChannelKey(KeyEvent.KEYCODE_9))
        assertEquals(10, liveChannelKey(KeyEvent.KEYCODE_0))
        assertEquals(11, liveChannelKey(KeyEvent.KEYCODE_11))
        assertEquals(12, liveChannelKey(KeyEvent.KEYCODE_12))
        assertEquals(4, liveChannelKey(KeyEvent.KEYCODE_NUMPAD_4))
        assertEquals(10, liveChannelKey(KeyEvent.KEYCODE_NUMPAD_0))
        assertNull(liveChannelKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertNull(liveChannelKey(KeyEvent.KEYCODE_STAR))
        // 局の切り替え・メニューのキーとは重ならない
        (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9).forEach { assertNull(liveCommand(it)) }
    }

    /** 同じリモコン番号の局が何局かあれば、いまの種別 (地上波 / BS) を先に、次に放送している局 (サブチャンネルより本放送) */
    @Test
    fun 数字キーで選ぶ局() {
        fun service(id: Long, type: String, key: Int?, title: String = "番組") =
            Service(id = id, type = type, name = "局$id", remoteControlKey = key, live = "api/services/$id/live", now = NowProgram(title, 0, 1))
        val services = listOf(
            service(1, "GR", 1),
            // 同じものを流しているサブチャンネル (番組名が空)
            service(2, "GR", 1, title = ""),
            service(3, "GR", 4),
            service(101, "BS", 1),
            service(141, "BS", 4),
        )
        assertEquals(1L, remoteKeyService(services, 1, "GR")?.id)
        assertEquals(101L, remoteKeyService(services, 1, "BS")?.id)
        assertEquals(141L, remoteKeyService(services, 4, "BS")?.id)
        // いまの種別に無ければほかの種別の局
        assertEquals(3L, remoteKeyService(services.filter { it.type == "GR" }, 4, "BS")?.id)
        // サブチャンネルが前に並んでいても本放送
        assertEquals(1L, remoteKeyService(services.reversed(), 1, "GR")?.id)
        assertNull(remoteKeyService(services, 7, "GR"))
    }

    @Test
    fun 録画のキー() {
        assertEquals(RecordingCommand.Back, recordingCommand(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(RecordingCommand.Forward, recordingCommand(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(RecordingCommand.SeekBar, recordingCommand(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(RecordingCommand.Actions, recordingCommand(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(RecordingCommand.Actions, recordingCommand(KeyEvent.KEYCODE_MENU))
        assertEquals(RecordingCommand.NextChapter, recordingCommand(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(RecordingCommand.PlayPause, recordingCommand(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        // 情報キーは決定の長押しと同じ (番組の詳細)
        assertEquals(RecordingCommand.Details, recordingCommand(KeyEvent.KEYCODE_INFO))
        // 数字キーはライブだけ
        assertNull(recordingCommand(KeyEvent.KEYCODE_1))
        assertNull(recordingCommand(KeyEvent.KEYCODE_DPAD_CENTER))
        assertNull(recordingCommand(KeyEvent.KEYCODE_BACK))
        // 決定の短押しは止める・動かす、長押しは詳しいところ
        assertEquals(RecordingCenter.PlayPause, recordingCenter(CenterPress.Action.Short))
        assertEquals(RecordingCenter.Details, recordingCenter(CenterPress.Action.Long))
    }

    private fun UpToClose.down(at: Long, repeat: Int = 0) = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, repeat, at)
    private fun UpToClose.up(at: Long) = key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, at)

    /** いちばん上の段で押しはじめた上キーは、離したときに閉じる (押している間は受けるだけ) */
    @Test
    fun いちばん上の段で上を押すと閉じる() {
        val up = UpToClose()
        assertEquals(UpKey.Hold, up.down(100))
        assertEquals(UpKey.Close, up.up(100))
        // 押し続けてから離しても閉じる
        assertEquals(UpKey.Hold, up.down(200))
        assertEquals(UpKey.Hold, up.down(200, repeat = 1))
        assertEquals(UpKey.Hold, up.down(200, repeat = 2))
        assertEquals(UpKey.Close, up.up(200))
    }

    /**
     * 下の段から押し続けて上がってきた上キー (押しはじめの DOWN はここに来ず、繰り返しと離しだけが来る) では閉じない。
     * 次に押し直したら閉じる
     */
    @Test
    fun 下の段から押し続けて上がってきても閉じない() {
        val up = UpToClose()
        assertEquals(UpKey.Hold, up.down(100, repeat = 3))
        assertEquals(UpKey.Hold, up.down(100, repeat = 4))
        assertEquals(UpKey.Pass, up.up(100))
        // 1回押しで上がってきたとき (離しだけが来る) も閉じない
        assertEquals(UpKey.Pass, up.up(150))
        assertEquals(UpKey.Hold, up.down(200))
        assertEquals(UpKey.Close, up.up(200))
        // 閉じたあとに同じ押しの離しがもう一度来ても、二度は閉じない
        assertEquals(UpKey.Pass, up.up(200))
    }

    /** 押しはじめを見たあと離しが届かず、別の押しで上がってきた離しでは閉じない (押しを downTime で分ける) */
    @Test
    fun 届かなかった離しの続きでは閉じない() {
        val up = UpToClose()
        assertEquals(UpKey.Hold, up.down(100))
        assertEquals(UpKey.Pass, up.up(300))
    }

    /** 上キーのほかは受けない (左右・下・決定・戻るはボタン・操作バーがそのまま受ける) */
    @Test
    fun 上キーのほかは素通し() {
        val up = UpToClose()
        for (code in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK)) {
            assertEquals(UpKey.Pass, up.key(KeyEvent.ACTION_DOWN, code, 0, 100))
            assertEquals(UpKey.Pass, up.key(KeyEvent.ACTION_UP, code, 0, 100))
        }
    }
}
