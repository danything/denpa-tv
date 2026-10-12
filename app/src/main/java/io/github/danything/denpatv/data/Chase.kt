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
    private const val EDGE_MS = 10_000L

    fun recordedMs(startAt: Long, now: Long): Long = (now - startAt).coerceAtLeast(0)

    /** 頼む位置にそろえる (0 から 録れた長さ − 余白 まで) */
    fun clamp(targetMs: Long, recordedMs: Long): Long = targetMs.coerceIn(0, (recordedMs - EDGE_MS).coerceAtLeast(0))

    /** 最新の近く (ブラウザの追っかけの「最新」と同じく 20 秒以内) */
    fun atEdge(positionMs: Long, recordedMs: Long): Boolean = recordedMs - positionMs < 20_000

    /**
     * 映る前のエラーが、エンコードを断られた (空の 200) ものか。空の返事は `EmptyStreamException` (`ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`。HTTP の番号が無い) になる。
     * 元の TS (`baked` でない) は断られない。**繋がらない・読めない (`Reconnect.Verdict.Retry`。denpa の入れ替えの最中) は
     * 断られたのではない** ので、繋ぎ直しに任せる
     */
    fun refused(started: Boolean, baked: Boolean, httpStatus: Int?, errorCode: Int): Boolean =
        !started && baked && httpStatus == null && Reconnect.verdict(errorCode, null) != Reconnect.Verdict.Retry

    /**
     * 追っかけの口が 404 を返したときに出す文。denpa の追っかけの 404 は**録画が無い (消された) か、まだ何も録れていない** (録りはじめた直後)。
     * エンコードが終わったのとは関係ない (終わればファイルの再生へ移る)
     */
    const val NOT_FOUND = "再生できません: 録画が無いか、まだ何も録れていません。少し待ってから開き直してください"

    /**
     * 録り終えた録画をファイルの再生へ渡すか (`fresh` は読み直した一覧の1件、`playback` はその録画の観方)。**エンコードが終わったときだけ** —
     * 追っかけでなく、録画の再生が選ぶもの (`pickPlayback`) がエンコード済みのファイルのとき。
     * 「エンコードしない」設定の録画 (元の TS だけ) や、エンコード済みのファイルをこの端末でデコードできない録画は渡さない (追っかけのまま最後まで観る)。
     * 一覧がまだ追いついていなければ、次の知らせを待つ
     */
    fun handOff(fresh: Recording?, playback: PlaybackInfo?, decoders: Decoders): Boolean {
        if (fresh == null || fresh.chasing || playback == null) return false
        return pickPlayback(playback, decoders) is PlayChoice.File
    }

    /** `audio` はエンコードするときに頼む音声 (`bakedAudio`)。元の TS では渡さない */
    fun url(chase: String, codec: String, fromMs: Long, audio: DenpaAudio? = null): String =
        "$chase?codec=$codec&from=${fromMs / 1000}${audioQuery(audio)}"
}

/**
 * 観はじめに CM 飛ばしをオンにするか。**ブラウザの denpa (`skipCmAtStart`) と同じ:** 覚えている設定に従うが、
 * ロゴでの判定に失敗した録画 (`cmReliable` が false。無音だけで当てていて外れやすい) は切って始める。
 * 入れたければ操作の列で入れる
 */
fun skipCmAtStart(cmReliable: Boolean, remembered: Boolean): Boolean = cmReliable && remembered
