package io.github.danything.denpatv.data

/**
 * 録画の速さ。denpa のブラウザの再生と同じ段 (`pacing.ts` の `SPEEDS`)。
 * 音の高さは変えない (Media3 の `setPlaybackSpeed` は pitch を 1 のまま速さだけ変える)
 */
val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f)

/** 次の速さ。いちばん速いの次は等速に戻る。段に無い値 (古い設定など) からは等速の次へ */
fun nextSpeed(current: Float): Float {
    val at = SPEEDS.indexOf(current).coerceAtLeast(0)
    return SPEEDS[(at + 1) % SPEEDS.size]
}

/**
 * 速さを変えたあと、今の位置に飛び直す (シークする) か。**流れている最中に変えたときだけ。**
 *
 * 流れている最中に `setPlaybackSpeed` だけを呼ぶと、Media3 は鳴らし途中の音 (AudioTrack に溜まった等速の音) を
 * 流し切ってから新しい速さに切り替え、映像はその音の時計に合わせて出す。実機のテレビではこの切り替えの途中で
 * 映像が止まったままになった (1 → 1.25 倍で止まり、開き直すと 1.25 倍でちゃんと動く)。開き直したときは
 * 始めから新しい速さなので切り替えが無い。飛び直すと音と映像の溜まりを一度捨てて、開き直したときと同じく
 * 新しい速さで始め直す。始まる前 (準備中) は速さを決めるだけで足りる
 */
fun resyncAfterSpeedChange(from: Float, to: Float, playing: Boolean): Boolean = playing && from != to

/** 覚えていた値を段にそろえる。段に無ければ等速 */
fun knownSpeed(saved: Float?): Float = saved?.takeIf { it in SPEEDS } ?: 1f

/** 画面に出す形 (1.25×) */
fun speedLabel(speed: Float): String =
    (if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()) + "×"
