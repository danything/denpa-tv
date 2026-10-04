package io.github.danything.denpatv.data

/**
 * 決定キーの短押しと長押しを分ける。**Menu キーの無いリモコン (Google TV など) が多いので、
 * 再生の画面のメニューは決定の長押しで開く** (録画のカードの長押しと同じ)。
 *
 * - 押した (repeat 0) ときは何もしない。押し続けて repeat / 長押しの印が来たら1度だけ長押し
 * - 離したときに、長押しにならなかったら短押し。長押しのあとに離しても短押しにはしない
 * - 押したのを見ていない離し (前の画面で押した決定の離しなど) は捨てる
 */
class CenterPress {
    enum class Action { Short, Long }

    private var down = false
    private var fired = false

    fun down(repeatCount: Int, longPress: Boolean): Action? {
        if (repeatCount == 0 && !longPress) {
            down = true
            fired = false
            return null
        }
        if (!down || fired) return null
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
}
