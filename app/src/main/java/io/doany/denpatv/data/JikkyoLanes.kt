package io.doany.denpatv.data

import kotlin.math.roundToInt

/**
 * **コメントを流す行の割り当て** (ブラウザの denpa の `lanes.ts` と同じ決まり。テレビには枠の外の光が無いので、枠の外を流れるぶんは持たない)。
 *
 * - 行の高さは枠の高さの 1/14 (下限 14px)。上下に余白を残して、およそ 13 行
 * - 1件は枠の右の端から左の端まで `SPAN_MS` で流れる。長いものも同じ速さで、そのぶん長く流れる
 *   (速さが揃っているので、後から入ったものが前のものに追いつくことはない)
 * - 行が空くのは、前のコメントの尻尾が枠の右の端を過ぎてから、次のものの頭がそこへ着くとき。**空いた行が無ければ捨てる** (重ねて描かない)
 * - 上・下に止めるもの (`ue`・`shita`) は `HOLD_MS` 置く。流れるものとは別に、上 (下) の行から空いたところへ積む
 *
 * 時刻はどれも**放送時刻の ms** で数える (再生の速さに合わせて進み、止めれば止まる)
 */
class JikkyoLanes(count: Int) {
    /** 行ごとの、前のコメントの尻尾 (と間) が枠の右の端を過ぎる時刻 */
    private var freeAt = LongArray(count) { Long.MIN_VALUE }
    /** 行ごとの、次の止めるものを置いてよい時刻 */
    private var heldAt = LongArray(count) { Long.MIN_VALUE }

    val count: Int get() = freeAt.size

    /** 1件の流し方。`duration` は流れ終わる (止めたものは消す) までの放送時刻の ms */
    data class Flow(val lane: Int, val duration: Long)

    /** 枠の大きさが変わった。行の数を合わせる (流れているものの印はそのまま) */
    fun resize(count: Int) {
        if (count == freeAt.size) return
        freeAt = LongArray(count) { freeAt.getOrElse(it) { Long.MIN_VALUE } }
        heldAt = LongArray(count) { heldAt.getOrElse(it) { Long.MIN_VALUE } }
    }

    /** 飛んだ (シーク・局の切り替え)。どの行も空ける */
    fun clear() {
        freeAt.fill(Long.MIN_VALUE)
        heldAt.fill(Long.MIN_VALUE)
    }

    /**
     * 右から左へ流す行を決める。空いた行が無ければ null (捨てる)。
     * @param now いまの放送時刻 (ms)
     * @param width コメントの幅 (px)
     * @param frameWidth 枠の幅 (px)
     * @param size 字の大きさ (px)
     */
    fun flow(now: Long, width: Float, frameWidth: Float, size: Float): Flow? {
        val speed = frameWidth / SPAN_MS
        if (!(speed > 0)) return null
        val lane = freeAt.indexOfFirst { it <= now }
        if (lane < 0) return null
        freeAt[lane] = now + ((width + size * GAP_EM) / speed).toLong()
        return Flow(lane, ((frameWidth + width) / speed).toLong())
    }

    /** 上か下に止める (`ue`・`shita`。アニメーションを切っている端末では流れるものも上に)。返す行はいつも上から数えたもの */
    fun hold(now: Long, fromBottom: Boolean): Flow? {
        val count = heldAt.size
        for (i in 0 until count) {
            val lane = if (fromBottom) count - 1 - i else i
            if (heldAt[lane] > now) continue
            heldAt[lane] = now + HOLD_MS
            return Flow(lane, HOLD_MS)
        }
        return null
    }

    companion object {
        /** 右の端から左の端まで (放送時刻の ms) */
        const val SPAN_MS = 4_000L
        /** 上・下に止めるもの (と、アニメーションを切っている端末の流れるもの) を置いておく長さ */
        const val HOLD_MS = 3_000L
        /** 行の高さの下限 (px) */
        private const val MIN_LINE = 14f
        /** 前のコメントとの間に空ける幅 (字の大きさに対して) */
        private const val GAP_EM = 1f

        /** 行の高さ (px) */
        fun lineHeight(frameHeight: Float): Float = maxOf(MIN_LINE, frameHeight / 14)

        /** 行の数。上下に半行ずつ余白を残す */
        fun laneCount(frameHeight: Float): Int {
            // 行の高さが 1/14 なら 14 行ぶんちょうど (割り算の端数で 13.999… にしない)
            val lines = if (frameHeight / 14 >= MIN_LINE) 14 else (frameHeight / MIN_LINE).toInt()
            return maxOf(1, lines - 1)
        }

        /** 字の大きさ (px)。行の高さに収まる大きさ */
        fun fontSize(frameHeight: Float): Float = (lineHeight(frameHeight) * 0.8f).roundToInt().toFloat()

        /** 字の大きさの倍率 (`big`・`small`)。大きいものも1行に収まる所まで (字は行の高さの 0.8 なので 1.25 倍で行いっぱい) */
        fun sizeScale(size: String?): Float = when (size) {
            "big" -> 1.25f
            "small" -> 0.75f
            else -> 1f
        }
    }
}
