package io.github.danything.denpatv.data

import android.view.KeyEvent

/**
 * 再生の画面で、何も開いていないときのリモコンのキーの割り当て。**十字キーと決定・戻るだけで全部に届く**
 * (Menu キーの無いリモコンが多い)。README の「操作」の表と同じ。
 *
 * ライブ: **左右で前・次の局** (チャンネル送りも同じ。Fire TV のリモコンにはチャンネル送りが無いので十字キーだけで替えられる)。
 * **下・決定・Menu でメニュー** (操作の列と局の列。YouTube・Prime Video・ABEMA などのテレビのアプリと同じく、下でメニューが出る)。
 * **上は同じメニューを、局の列のいま映している局に合わせて開く** (局を一覧から選ぶ近道)。
 * **決定の長押しは情報キーと同じく、いまの局と番組を出す** (`liveCenter`)。
 * 録画: 左右で 10 秒戻す・送る、決定で止める・動かす、下でシークバーと操作の列、上・決定の長押し・Menu で操作の列。
 * 開いたメニュー・帯は、いちばん上の段 (ライブは操作の列、録画はシークバー) で上を押すと閉じる (`UpToClose`)。
 * 決定の短押し・長押しは PlayerFrame が分ける (CenterPress)
 */
enum class LiveCommand { PreviousChannel, NextChannel, Menu, Channels, Info }

fun liveCommand(keyCode: Int): LiveCommand? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_CHANNEL_UP -> LiveCommand.PreviousChannel
    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_CHANNEL_DOWN -> LiveCommand.NextChannel
    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> LiveCommand.Menu
    KeyEvent.KEYCODE_DPAD_UP -> LiveCommand.Channels
    KeyEvent.KEYCODE_INFO -> LiveCommand.Info
    else -> null
}

/** ライブの決定。短押しはメニュー、長押しは情報キーと同じ (いまの局と番組)。長押しのあとの離しは CenterPress が捨てる */
fun liveCenter(press: CenterPress.Action): LiveCommand = when (press) {
    CenterPress.Action.Short -> LiveCommand.Menu
    CenterPress.Action.Long -> LiveCommand.Info
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

/**
 * 開いているもの (ライブのメニュー・録画と追っかけの帯) の**いちばん上の列で上キーを押したら閉じて映像に戻る** (戻ると同じ)。
 * いちばん上の列 (ライブは操作の列、録画・追っかけはシークバー) に付ける。
 *
 * **閉じるのは、その列に合っている間に押しはじめた上キーを離したとき** (DOWN の repeat 0 を見た押しの UP。押しの区別は downTime)。
 * 下の列 (局の列・操作の列) から上キーを押し続けて上がってきたときは、繰り返し (repeat 1〜) と離しがこの列に届くが、
 * 押しはじめを見ていないので閉じない。DOWN で閉じないのは、押し続けた繰り返しが閉じたあとの映像に届いて、
 * メニュー・帯を開き直してしまうため (映像の上キーはメニュー・帯を開く)
 */
class UpToClose {
    /** この列に合っている間に押しはじめた上キーの downTime (無ければ null) */
    private var pressedAt: Long? = null

    fun key(action: Int, keyCode: Int, repeatCount: Int, downTime: Long): UpKey {
        if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return UpKey.Pass
        return when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (repeatCount == 0) pressedAt = downTime
                // いちばん上の列なので、上へ動かす先は無い (受けて、合いを動かさない)
                UpKey.Hold
            }
            KeyEvent.ACTION_UP -> if (pressedAt == downTime) {
                pressedAt = null
                UpKey.Close
            } else {
                UpKey.Pass
            }
            else -> UpKey.Pass
        }
    }
}

/** `UpToClose` の答え。Pass は受けない、Hold は受けて何もしない、Close は受けて閉じる */
enum class UpKey { Pass, Hold, Close }
