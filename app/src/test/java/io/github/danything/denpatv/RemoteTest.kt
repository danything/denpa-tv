package io.github.danything.denpatv

import android.view.KeyEvent
import io.github.danything.denpatv.data.CenterPress
import io.github.danything.denpatv.data.LiveCommand
import io.github.danything.denpatv.data.liveCenter
import io.github.danything.denpatv.data.RecordingCommand
import io.github.danything.denpatv.data.liveCommand
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
    }
}
