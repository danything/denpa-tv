package io.github.danything.denpatv.data

import android.view.KeyEvent

/**
 * **長押しで開いたもの** (録画の詳しく・再生の画面の操作の帯) に付ける。開いてから、決定が一度離されて押し直される
 * (repeat 0 の DOWN が来る) までは、決定のキーを捨てる。
 *
 * リモコンは押し続けると ACTION_DOWN を繰り返し (2つ目に長押しの印)、離すと ACTION_UP を送る。長押しで開いたものに
 * 合いが移ると、そのあとの繰り返しと離しが**開いたものの押す部品に届いてしまう** — 詳しくの「再生」が押されて
 * 再生が始まる、帯の札 (画質) が押されて帯が閉じる。開いたものの側で、押し直しを見るまで受けない。
 *
 * 長押しを受けた側 (カード・映像) には何もしない。そちらは離しを受けて自分の長押しの印を戻すので、捨てると
 * 次の短押しが効かなくなる
 */
class LongPressGuard {
    /** まだ押し直しを見ていない (開いた直後) */
    private var armed = true

    /** このキーを届けるか */
    fun deliver(action: Int, keyCode: Int, repeatCount: Int): Boolean {
        if (keyCode !in CENTER_KEYS) return true
        if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) armed = false
        return !armed
    }

    companion object {
        /** 決定にあたるキー (リモコンによって ENTER を送るものもある) */
        val CENTER_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
    }
}
