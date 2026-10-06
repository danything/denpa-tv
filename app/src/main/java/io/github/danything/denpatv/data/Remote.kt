package io.github.danything.denpatv.data

import android.view.KeyEvent

/**
 * 再生の画面で、何も開いていないときのリモコンのキーの割り当て。**十字キーと決定・戻るだけで全部に届く**
 * (Menu キーの無いリモコンが多い)。README の「操作」の表と同じ。
 *
 * ライブ: 上下で前・次の局 (チャンネル送りも同じ。Fire TV のリモコンにはチャンネル送りが無いので十字キーだけで替えられる)。
 * **ほかの十字キー (左右) と決定・Menu はどれもメニュー** (操作の列と局の列。YouTube のアプリと同じく、どのキーを押しても
 * 十字キーで辿れるメニューが出る。決定の長押しでも出す)。
 * 録画: 左右で 10 秒戻す・送る、決定で止める・動かす、下でシークバーと操作の列、上・決定の長押し・Menu で操作の列。
 * 決定の短押し・長押しは PlayerFrame が分ける (CenterPress)
 */
enum class LiveCommand { PreviousChannel, NextChannel, Menu, Info }

fun liveCommand(keyCode: Int): LiveCommand? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> LiveCommand.PreviousChannel
    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> LiveCommand.NextChannel
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU -> LiveCommand.Menu
    KeyEvent.KEYCODE_INFO -> LiveCommand.Info
    else -> null
}

enum class RecordingCommand { Back, Forward, SeekBar, Actions, NextChapter, PreviousChapter, PlayPause, NextSpeed }

fun recordingCommand(keyCode: Int): RecordingCommand? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> RecordingCommand.Back
    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> RecordingCommand.Forward
    KeyEvent.KEYCODE_DPAD_DOWN -> RecordingCommand.SeekBar
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU -> RecordingCommand.Actions
    KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> RecordingCommand.NextChapter
    KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> RecordingCommand.PreviousChapter
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> RecordingCommand.PlayPause
    KeyEvent.KEYCODE_PROG_GREEN -> RecordingCommand.NextSpeed
    else -> null
}

/**
 * 1押しで動かす量 (ミリ秒)。**戻しも送りも 10 秒** (ブラウザの denpa の、端を2回で 10 秒と同じ)。
 * 押し続けるとキーが繰り返し来るので、そのぶん速く動く
 */
const val SEEK_STEP_MS = 10_000L
