package io.github.danything.denpatv.data

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * **コメントを流す場所の割り当て** (ブラウザの denpa の `lanes.ts` と同じ決まり。流し方はニコニコと同じ。
 * テレビには枠の外の光が無いので、枠の外を流れるぶんは持たない)。
 *
 * - **決まった行は持たない。** 1件は字の高さぶん (`rows`。普通の大きさで 1) の縦の場所を取り、上から空いた所に積む。
 *   長さは行の高さを 1 とした単位で数える (枠の大きさが変わっても、位置はその割合のまま)
 *   - 行の高さは枠の高さの 1/14 (下限 14px)。上下に半行ずつ余白を残す。普通の大きさならおよそ 13 件が縦に並ぶ
 *   - 大きさの比もニコニコと同じ (`big` は 1.625 倍、`small` は 0.625 倍)。大きいものが多いと並ぶ数が減る
 * - **1件は、先頭が枠の右の端に着いてから末尾が左の端を出るまで、どれも `SPAN_MS` で渡る。** 長いものほど速く流れる
 *   (速さは `(枠の幅 + コメントの幅) / SPAN_MS`。`speedOf`)
 * - 縦に重なる前のものとは、前のものの末尾が枠の右の端を過ぎてから次のものの先頭がそこへ着き、
 *   しかも次のもののほうが速くても、前のものの末尾が左の端を出るまで追い付かないときだけ並べる
 * - **空いた所が無ければ、くじで選んだ高さに重ねて流す** (ニコニコの弾幕と同じ)。重ねたものは空きに数えない
 * - 上・下に止めるもの (`ue`・`shita`) は `HOLD_MS` 置く。流れるものとは別に、上 (下) の端から空いた所へ積み、
 *   空いた所が無ければくじで重ねる
 *
 * 時刻はどれも**放送時刻の ms** で数える (再生の速さに合わせて進み、止めれば止まる)
 *
 * @param height 枠の高さ (行の高さを 1 として)
 * @param random 重ねる高さを選ぶくじ (0 以上 1 未満。テストでは決まった値を渡す)
 */
class JikkyoLanes(private var height: Float = 1f, private val random: () -> Double = { Random.nextDouble() }) {
    /**
     * 縦の場所 (行の高さを 1 として)。流れているものは `entryAt`・`exitAt` で並べられるかを決める。
     * 止めているものは `exitAt` を消す時刻に使い、`entryAt` は使わない (置いた時刻を入れておく)
     */
    private class Placed(val top: Float, val bottom: Float, val entryAt: Long, val exitAt: Long)

    /** 流れているもの。`entryAt` は末尾 (と間) が枠の右の端を過ぎる時刻、`exitAt` は左の端を出る時刻 */
    private val moving = mutableListOf<Placed>()
    /** 止めているもの。`exitAt` は消す時刻。消えたものは次の `hold` で外す (同時に流すのは 60 件までなので溜まらない) */
    private val held = mutableListOf<Placed>()

    /** 1件の置き方。`top` は上の端 (行の高さを 1 として)、`duration` は流れ終わる (止めたものは消す) までの放送時刻の ms */
    data class Flow(val top: Float, val duration: Long)

    /** 枠の大きさが変わった (行の高さを 1 とした枠の高さ)。流れているものの場所はそのまま */
    fun resize(height: Float) {
        this.height = height
    }

    /** 飛んだ (シーク・局の切り替え)。どこも空ける */
    fun clear() {
        moving.clear()
        held.clear()
    }

    /**
     * 右から左へ流す場所を決める。空いた所が無ければ、くじで選んだ高さに重ねる。枠の幅が 0 なら null (流さない)。
     * @param now いまの放送時刻 (ms)
     * @param width コメントの幅 (px)
     * @param frameWidth 枠の幅 (px)
     * @param size 字の大きさ (px)
     * @param rows コメントの高さ (行の高さを 1 として)
     */
    fun flow(now: Long, width: Float, frameWidth: Float, size: Float, rows: Float = 1f): Flow? {
        if (!(frameWidth > 0)) return null
        val speed = speedOf(frameWidth, width)
        // 先頭が枠の左の端に着く時刻
        val reach = now + (frameWidth / speed).toLong()
        // 左の端を出たものは、あとのどれとも重ならない
        moving.removeAll { it.exitAt <= now }
        val busy = moving.filter { it.entryAt > now || it.exitAt > reach }
        val top = stack(busy, rows, fromBottom = false) ?: return Flow(draw(rows), SPAN_MS)
        val tail = ((width + size * GAP_EM) / speed).toLong()
        moving += Placed(top, top + rows, now + tail, reach + tail)
        return Flow(top, SPAN_MS)
    }

    /**
     * 上か下に止める (`ue`・`shita`。アニメーションを切っている端末では流れるものも上に)。`fromBottom` なら下の端から
     * 空いた所に `HOLD_MS` 置く。空いた所が無ければ、くじで選んだ高さに重ねる
     */
    fun hold(now: Long, fromBottom: Boolean, rows: Float = 1f): Flow {
        held.removeAll { it.exitAt <= now }
        val top = stack(held, rows, fromBottom) ?: return Flow(draw(rows), HOLD_MS)
        held += Placed(top, top + rows, now, now + HOLD_MS)
        return Flow(top, HOLD_MS)
    }

    /**
     * 端から、`busy` のどれとも縦に重ならない最初の所 (ニコニコと同じ。重なったものの向こうの端へずらして探し直す)。
     * 余白の内に収まらなければ null
     */
    private fun stack(busy: List<Placed>, rows: Float, fromBottom: Boolean): Float? {
        val lo = MARGIN
        val hi = height - MARGIN
        var y = if (fromBottom) hi - rows else lo
        while (true) {
            val at = y
            val hit = busy.firstOrNull { it.top < at + rows - EPSILON && at < it.bottom - EPSILON } ?: break
            y = if (fromBottom) hit.top - rows else hit.bottom
        }
        return if (y >= lo - EPSILON && y + rows <= hi + EPSILON) y else null
    }

    /** 重ねる高さ。余白の内でくじを引く (収まらないほど大きいものは上の余白から) */
    private fun draw(rows: Float): Float = MARGIN + maxOf(0f, height - 2 * MARGIN - rows) * random().toFloat()

    companion object {
        /** 先頭が枠の右の端に着いてから、末尾が左の端を出るまで (放送時刻の ms)。ニコニコと同じ 4 秒 */
        const val SPAN_MS = 4_000L
        /** 上・下に止めるもの (と、アニメーションを切っている端末の流れるもの) を置いておく長さ */
        const val HOLD_MS = 3_000L
        /** 行の高さの下限 (px) */
        private const val MIN_LINE = 14f
        /** 前のコメントとの間に空ける幅 (字の大きさに対して) */
        private const val GAP_EM = 1f
        /** 上下に残す余白 (行の高さを 1 として) */
        private const val MARGIN = 0.5f
        /** 端数の誤差 (行の高さを 1 として)。ちょうど収まるものを収まらないとしない */
        private const val EPSILON = 1e-4f

        /** 1件の速さ (px / 放送時刻の ms)。どれも枠を `SPAN_MS` で渡りきるので、長いものほど速い */
        fun speedOf(frameWidth: Float, width: Float): Float = (frameWidth + width) / SPAN_MS

        /** 行の高さ (px)。普通の大きさのコメント 1件が取る高さ */
        fun lineHeight(frameHeight: Float): Float = maxOf(MIN_LINE, frameHeight / 14)

        /** 字の大きさ (px)。行の高さに収まる大きさ */
        fun fontSize(frameHeight: Float): Float = (lineHeight(frameHeight) * 0.8f).roundToInt().toFloat()

        /** 字の大きさの倍率 (`big`・`small`)。ニコニコと同じ比 (普通 24・大 39・小 15)。取る縦の場所も同じ倍率で変わる */
        fun sizeScale(size: String?): Float = when (size) {
            "big" -> 39f / 24
            "small" -> 15f / 24
            else -> 1f
        }
    }
}
