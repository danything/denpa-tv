package io.github.danything.denpatv

import android.view.KeyEvent
import io.github.danything.denpatv.data.CenterPress
import io.github.danything.denpatv.data.LiveCommand
import io.github.danything.denpatv.data.liveCenter
import io.github.danything.denpatv.data.RecordingCommand
import io.github.danything.denpatv.data.UpKey
import io.github.danything.denpatv.data.UpToClose
import io.github.danything.denpatv.data.liveCommand
import io.github.danything.denpatv.data.RecordingCenter
import io.github.danything.denpatv.data.recordingCenter
import io.github.danything.denpatv.data.recordingCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteTest {
    /**
     * ライブ: 左右とチャンネル送りで前・次の局。下と Menu はメニュー (操作の列と局の列)、上は局の列に合わせて開くメニュー。
     * 決定は PlayerFrame が短押し・長押しに分け、短押しはメニュー、長押しは情報キーと同じ (いまの局と番組)
     */
    @Test
    fun ライブのキー() {
        assertEquals(LiveCommand.PreviousChannel, liveCommand(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(LiveCommand.PreviousChannel, liveCommand(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(LiveCommand.NextChannel, liveCommand(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(LiveCommand.NextChannel, liveCommand(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals(LiveCommand.Menu, liveCommand(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(LiveCommand.Menu, liveCommand(KeyEvent.KEYCODE_MENU))
        assertEquals(LiveCommand.Channels, liveCommand(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(LiveCommand.Info, liveCommand(KeyEvent.KEYCODE_INFO))
        assertNull(liveCommand(KeyEvent.KEYCODE_DPAD_CENTER))
        // 戻るは受けない (何も開いていなければメニューの画面へ戻る)
        assertNull(liveCommand(KeyEvent.KEYCODE_BACK))
        assertEquals(LiveCommand.Menu, liveCenter(CenterPress.Action.Short))
        assertEquals(LiveCommand.Info, liveCenter(CenterPress.Action.Long))
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

    /** 上キーのほかは受けない (左右・下・決定・戻るは札・帯がそのまま受ける) */
    @Test
    fun 上キーのほかは素通し() {
        val up = UpToClose()
        for (code in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK)) {
            assertEquals(UpKey.Pass, up.key(KeyEvent.ACTION_DOWN, code, 0, 100))
            assertEquals(UpKey.Pass, up.key(KeyEvent.ACTION_UP, code, 0, 100))
        }
    }
}
