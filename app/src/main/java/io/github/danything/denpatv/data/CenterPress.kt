package io.github.danything.denpatv.data

/**
 * 決定キーの短押しと長押しを分ける。**Menu キーや情報キーの無いリモコン (Google TV など) が多いので、
 * 再生の画面の詳しくは決定の長押しで開く** (録画のカードの長押しと同じ)。
 *
 * - 押した (repeat 0) ときは何もしない。押し続けて repeat / 長押しの印が来たら1度だけ長押し
 * - **繰り返しが来なくても、押したまま `LONG_PRESS_MS` たてば長押し** (`held`。呼ぶ側が押してからの時間を測って呼ぶ)。
 *   リモコンや端末によっては、押し続けても繰り返しを送らず、押した・離したの2つだけが来る
 *   (キーの繰り返しを切ってある端末、離すまで何も送らない BT のリモコン)
 * - 離したときに、長押しにならなかったら短押し。長押しのあとに離しても短押しにはしない
 * - 押したのを見ていない離し (前の画面で押した決定の離しなど) は捨てる
 */
class CenterPress {
    enum class Action { Short, Long }

    private var down = false
    private var fired = false

    /** 何回目の押しか。`held` がどの押しの時間切れかを見分ける (離して押し直したあとに前の押しの時間切れが来ても効かせない) */
    var press = 0
        private set

    fun down(repeatCount: Int, longPress: Boolean): Action? {
        if (repeatCount == 0 && !longPress) {
            down = true
            fired = false
            press++
            return null
        }
        if (!down || fired) return null
        fired = true
        return Action.Long
    }

    /** `press` 回目の押しから `LONG_PRESS_MS` たった。まだ押したままなら長押し (繰り返しで長押しになっていれば何もしない) */
    fun held(press: Int): Action? {
        if (press != this.press || !down || fired) return null
        fired = true
        return Action.Long
    }

    fun up(): Action? {
        if (!down) return null
        down = false
        return if (fired) null else Action.Short
    }

    fun reset() {
        down = false
        fired = false
    }

    companion object {
        /**
         * 繰り返しが来ないときに長押しとみなすまで (ミリ秒)。Android の長押し (`ViewConfiguration.getLongPressTimeout`、
         * 0.4〜0.5 秒) と、最初の繰り返しまで (0.4〜0.5 秒) より少し長く — 繰り返しを送るリモコンでは、いつもどおり繰り返しで決まる。
         * ゆっくり押しただけの決定 (メニュー) を長押しと取り違えない長さ
         */
        const val LONG_PRESS_MS = 700L
    }
}
