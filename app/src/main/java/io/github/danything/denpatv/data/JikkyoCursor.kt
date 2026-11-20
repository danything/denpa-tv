package io.github.danything.denpatv.data

/**
 * **控えから、いまの放送時刻 W までに書かれたものを順に出す** (ブラウザの denpa の `cursor.ts` と同じ決まり)。
 *
 * - **前の W から今の W までに書かれたもの**を出す。W が戻った・大きく飛んだ (シーク・局の切り替え) ときは、
 *   飛んだ先から出し直す (飛んだぶんをまとめて出さない)
 * - 届いたときに W を過ぎていても 10 秒以内なら次に出す (NX-Jikkyo が公式のコメントを取り込むぶん遅れて届く)。それより古いものは出さない
 * - `ahead` ms 先まで出せる (ブラウザの録画が枠の外から流し始めるぶん。テレビは枠の外が無いので 0 で呼ぶ)
 *
 * 鍵は呼ぶ側 (`JikkyoFeed`) が掛ける
 */
class JikkyoCursor {
    var store = CommentStore()
        private set
    /** 前の W */
    private var last: Long? = null
    /** ここまで出した (W に `ahead` を足したところの、いちばん先) */
    private var upTo = 0L
    private var late = mutableListOf<JikkyoComment>()
    private var trimmedAt = Long.MIN_VALUE / 2

    /** 1回の `take` の結果。`jumped` は飛んだ (流れているものを消して、行を空ける) */
    data class Take(val comments: List<JikkyoComment>, val jumped: Boolean)

    /** コメントを控えに足す。`span` はその区間を埋めたことにする */
    fun receive(comments: List<JikkyoComment>, span: Pair<Long, Long>? = null) {
        val added = store.add(comments, span)
        if (last == null) return
        // 届いたときにもう出したところを過ぎていたもの。10 秒以内なら次の `take` で出す
        for (comment in added) if (comment.at <= upTo && comment.at > upTo - LATE_MS) late += comment
    }

    /** いまの放送時刻 `w` (の `ahead` ms 先) までに書かれたもののうち、まだ出していないもの */
    fun take(w: Long, ahead: Long = 0): Take {
        val last = last
        if (last == null || jumped(last, w)) {
            this.last = w
            upTo = w
            late = mutableListOf()
            return Take(emptyList(), last != null)
        }
        // 少し戻った・進んでいない。遅れて届いたものだけ出し、W は進めない
        val lateOnes = late.filter { it.at > w - LATE_MS }
        late = mutableListOf()
        if (w > last) this.last = w
        val to = w + ahead.coerceAtLeast(0)
        if (to <= upTo) return Take(lateOnes, false)
        val comments = lateOnes + store.between(upTo, to)
        upTo = to
        if (Math.abs(w - trimmedAt) > TRIM_EVERY_MS) {
            trimmedAt = w
            store.trim(w)
        }
        return Take(comments, false)
    }

    /** 流していない間 (オフ) に進んだぶんは出さない。次の `take` で出し直す */
    fun pause() {
        last = null
        late = mutableListOf()
    }

    /** 局が替わった。控えも捨てる */
    fun reset() {
        store = CommentStore()
        pause()
    }

    companion object {
        /** 届いたのが遅れても出す幅 */
        private const val LATE_MS = 10_000L
        /** 控えを前後あわせて 2 時間に収め直す間隔 (放送時刻の ms) */
        private const val TRIM_EVERY_MS = 60_000L
    }
}

/** これより大きく W が進んだら、飛んだとみなす (再生の詰まりで1回に進む幅より十分大きく) */
private const val JUMP_MS = 10_000L

/** これより大きく W が戻ったら、飛んだとみなす。時計の合わせ直し (`clock` の通知) で W は少し戻ることがある */
private const val BACK_MS = 1_000L

/** 放送時刻が飛んだか (シーク・局の切り替え。流すほうと一覧で同じ) */
fun jumped(last: Long?, w: Long): Boolean = last != null && (w < last - BACK_MS || w - last > JUMP_MS)
