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

/** 覚えていた値を段にそろえる。段に無ければ等速 */
fun knownSpeed(saved: Float?): Float = saved?.takeIf { it in SPEEDS } ?: 1f

/** 画面に出す形 (1.25×) */
fun speedLabel(speed: Float): String =
    (if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()) + "×"
