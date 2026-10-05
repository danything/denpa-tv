package io.github.danything.denpatv.data

/**
 * 追っかけ再生 (録画中の録画)。denpa の `GET api/recordings/<id>/chase?codec=&from=<秒>` は伸びているファイルを
 * `from` から流しっぱなしにする1本で、シークできない。**シークは `from` を変えて頼み直す。**
 *
 * - 再生の位置 = 頼んだ `from` + プレーヤーの位置
 * - 録れた長さ = いま − 放送の始まり (denpa もファイルの大きさ ÷ 録れた秒数で位置を決めるので、ほぼ合う)
 * - 頼む位置は 0 から「録れた長さ − 余白」まで (denpa も録れた長さ − 5 秒で止める)。最新に飛んでも、
 *   少し手前から観る
 */
object Chase {
    /** 最新から手前に置く余白 (ミリ秒) */
    const val EDGE_MS = 10_000L

    fun recordedMs(startAt: Long, now: Long): Long = (now - startAt).coerceAtLeast(0)

    /** 頼む位置にそろえる (0 から 録れた長さ − 余白 まで) */
    fun clamp(targetMs: Long, recordedMs: Long): Long = targetMs.coerceIn(0, (recordedMs - EDGE_MS).coerceAtLeast(0))

    /** 最新の近く (ブラウザの追っかけの「最新」と同じく 20 秒以内) */
    fun atEdge(positionMs: Long, recordedMs: Long): Boolean = recordedMs - positionMs < 20_000

    /** `audio` は焼くときに頼む音声 (`bakedAudio`)。生では渡さない */
    fun url(chase: String, codec: String, fromMs: Long, audio: DenpaAudio? = null): String =
        "$chase?codec=$codec&from=${fromMs / 1000}${audioQuery(audio)}"
}

/**
 * 観はじめに CM 飛ばしを入れるか。**ブラウザの denpa (`skipCmAtStart`) と同じ:** 覚えている設定に従うが、
 * ロゴでの判定に失敗した録画 (`cmReliable` が false。無音だけで当てていて外れやすい) は切って始める。
 * 入れたければ操作の列で入れる
 */
fun skipCmAtStart(cmReliable: Boolean, remembered: Boolean): Boolean = cmReliable && remembered
