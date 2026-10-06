package io.github.danything.denpatv.data

import androidx.media3.common.PlaybackException

/**
 * **映像が切れた・止まったときの繋ぎ直し** (ui/Recovery.kt)。決まりはブラウザの denpa のライブ (`live-player.svelte.ts` の
 * `reconnect`) と同じ: 待ち時間は `RETRY_FIRST_MS` から倍々に伸ばして `RETRY_MOST_MS` で頭打ち、**回数の上限は置かない**
 * (点けっぱなしで戻ってきたときに映っていてほしい)。次の絵が出たら数え直す。
 *
 * 切れる理由でいちばん多いのは denpa の側の入れ替え (番組の境目での焼き直し・GPU からソフトウェアへ降りた・denpa の再起動) と、
 * チューナーのドライバが黙ること。どれも待てば帰ってくるので、こちらから諦める理由が無い。
 * ただし**待っても直らないもの** (局が無い 404 など) は繋ぎ直さずに理由を出す (`verdict`)
 */
object Reconnect {
    /** 1回目に待つ時間 (ミリ秒)。ブラウザの `RETRY_FIRST` と同じ */
    const val RETRY_FIRST_MS = 1_000L

    /** 待つ時間の上限 (ミリ秒)。ブラウザの `RETRY_MOST` と同じ */
    const val RETRY_MOST_MS = 10_000L

    /**
     * 直らないかもしれない失敗 (`Verdict.RetryFew`。中身が読めない・解けない、映る前に流れが終わった) を続けて繋ぎ直す回数。
     * 録画のファイルは、回線の失敗もこの回数まで (Media3 が中で読み直したうえでの失敗なので)
     */
    const val FEW = 3

    /**
     * 映していて (絵を1枚でも出したあと)、これだけ何も進まなければ止まったとみなして繋ぎ直す (ミリ秒)。
     * 位置も溜まりも伸びないこと。**`RETRY_MOST_MS` と同じ 10 秒**: denpa が流れの中で焼き手を温め直す間 (`WARM_WAIT` 8 秒) より長く、
     * 読みの待ち (`Http.READ_TIMEOUT_MS` 30 秒) より十分短い。前の絵を残すのは 6 秒 (`HOLD_MOST`) までなので、
     * 暗くなってから少しで繋ぎ直しに入る
     */
    const val STALL_MS = 10_000L

    /** `attempts` 回失敗したあと、次に繋ぎ直すまで待つ時間。0 回目は `RETRY_FIRST_MS` */
    fun delayMs(attempts: Int): Long {
        val shift = attempts.coerceIn(0, 30)
        return (RETRY_FIRST_MS shl shift).coerceAtMost(RETRY_MOST_MS)
    }

    /** 失敗をどう扱うか */
    enum class Verdict {
        /** トークンが外された・期限切れ (401)。繋ぐ画面へ */
        Unauthorized,

        /** 待っても直らない (404 などの 4xx・ファイルが無い・権限が無い)。理由を出して止める */
        GiveUp,

        /** 回線・denpa の入れ替え。流しっぱなしのもの (ライブ・追っかけ) は何度でも */
        Retry,

        /** 直らないかもしれない (中身が読めない・解けない)。`FEW` 回まで */
        RetryFew,
    }

    /**
     * Media3 のエラー (`PlaybackException.errorCode`) と、HTTP の応答の番号 (あれば) から決める。
     * 番号は Media3 の `PlaybackException.ERROR_CODE_*` (1xxx その他・2xxx 読み・3xxx 中身・4xxx 解く・5xxx 音・6xxx DRM)
     */
    fun verdict(errorCode: Int, httpStatus: Int?): Verdict = when {
        httpStatus == 401 -> Verdict.Unauthorized
        // 408 (待ちきれない) と 429 (混んでいる) は待てば通る
        httpStatus == 408 || httpStatus == 429 -> Verdict.Retry
        httpStatus != null && httpStatus in 400..499 -> Verdict.GiveUp
        // ファイルが無い・権限が無い・平文が許されていない (どれも待っても同じ)
        errorCode in HOPELESS -> Verdict.GiveUp
        // 型が違う (denpa ではないものが答えた) と読む位置が外れたは、何度か
        errorCode in DOUBTFUL -> Verdict.RetryFew
        // 読み (2xxx。5xx の応答もここ)・待ちきれない・遅れすぎ
        errorCode in 2000..2999 || errorCode == PlaybackException.ERROR_CODE_TIMEOUT || errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> Verdict.Retry
        // DRM は denpa には無い
        errorCode in 6000..6999 -> Verdict.GiveUp
        else -> Verdict.RetryFew
    }

    /** 待っても同じもの */
    private val HOPELESS = setOf(
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
    )
    /** 何度か試すもの */
    private val DOUBTFUL = setOf(
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    )

    /**
     * 繋ぎ直すか。`stream` はライブ・追っかけ (流しっぱなしの1本)、そうでなければ録画のファイル。
     * `attempts` は続けて失敗した回数、`few` はそのうち `RetryFew` だった回数 (どちらも次の絵が出たら 0 に戻す)
     */
    fun shouldRetry(verdict: Verdict, stream: Boolean, attempts: Int, few: Int): Boolean = when (verdict) {
        Verdict.Unauthorized, Verdict.GiveUp -> false
        Verdict.Retry -> stream || attempts < FEW
        Verdict.RetryFew -> stream && few < FEW
    }
}

/**
 * **止まったかの見張り。** 映していて (`watching`) 位置も溜まりも `limitMs` 動かなければ、止まっていた長さを返す
 * (返したら数え直す)。動いていれば、見張っていなければ (止めている・まだ映していない・繋ぎ直しの最中) 数え直す。
 * 時刻は呼ぶ側が渡す (`SystemClock.uptimeMillis`。テストでは好きに進める)
 */
class StallWatch(private val limitMs: Long = Reconnect.STALL_MS) {
    private var since = 0L
    private var position = Long.MIN_VALUE
    private var buffered = Long.MIN_VALUE

    fun check(now: Long, watching: Boolean, positionMs: Long, bufferedMs: Long): Long? {
        if (!watching || positionMs != position || bufferedMs != buffered) {
            since = now
            position = positionMs
            buffered = bufferedMs
            return null
        }
        val stalled = now - since
        if (stalled < limitMs) return null
        since = now
        return stalled
    }
}

/** 追っかけの流れが終わったとき、録り終えて最後まで観たのか、途中で切れたのか */
enum class ChaseEnd { Finished, Lost }

/**
 * 追っかけの流れが終わった (`STATE_ENDED`)。denpa は**録り終えて尻まで読んだときだけ**閉じるので、それ以外は切れたとみなして
 * いまの位置から繋ぎ直す (denpa の入れ替え・焼き直し)。
 *
 * - `stillRecording` … 終わったあとに denpa に聞いた、まだ録っているか。聞けなければ null (denpa が入れ替わっている最中なので、切れた)
 * - `durationMs` … 録り終えた長さ。分からなければ最後まで観たことにする (前と同じ)
 * - `pictured` … この頼みで絵を出したか。録り終えた録画を頼み直して何も映らずに終わったなら、もう続きは無い (繰り返さない)
 */
fun chaseEnd(stillRecording: Boolean?, positionMs: Long, durationMs: Long?, pictured: Boolean): ChaseEnd = when {
    stillRecording == null || stillRecording -> ChaseEnd.Lost
    durationMs == null || !pictured -> ChaseEnd.Finished
    positionMs >= durationMs - CHASE_END_MARGIN_MS -> ChaseEnd.Finished
    else -> ChaseEnd.Lost
}

/** 録り終えた録画で、尻からこれより手前で終わったら切れたとみなす (ミリ秒)。頼む位置は秒に丸めるので、そのずれより十分大きく */
const val CHASE_END_MARGIN_MS = 30_000L
