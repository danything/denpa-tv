package io.github.danything.denpatv.data

/**
 * **2回押しで実行する** (消すなど、戻せない操作)。ブラウザの denpa と同じく、1回目で「もう一度押すと…」に
 * 変わり、`windowMs` 以内にもう1度押すと実行する。間が空いたら元に戻る (押し間違いをそのまま通さない)
 */
class TwoPress(private val windowMs: Long = 4_000) {
    private var armedAt: Long? = null

    /** 押した。実行するなら true */
    fun press(now: Long): Boolean {
        val at = armedAt
        return if (at != null && now - at <= windowMs) {
            armedAt = null
            true
        } else {
            armedAt = now
            false
        }
    }

    /** いま「もう一度押すと…」の状態か */
    fun armed(now: Long): Boolean = armedAt?.let { now - it <= windowMs } ?: false

    fun reset() {
        armedAt = null
    }
}
