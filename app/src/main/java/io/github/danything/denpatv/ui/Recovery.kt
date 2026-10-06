package io.github.danything.denpatv.ui

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.danything.denpatv.data.Reconnect
import io.github.danything.denpatv.data.StallWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 画面ごとの繋ぎ直しの決まり (`rememberPlayer` に渡す)。
 *
 * - `label` … logcat に出す名前 (`live 局 <id>` など)
 * - `stream` … 流しっぱなしの1本 (ライブ・追っかけ)。回線の失敗は何度でも繋ぎ直し、止まったのも見張る。録画のファイルは `Reconnect.FEW` 回まで
 * - `endedIsLost` … 流れが終わったら切れたとみなす (ライブ。追っかけは録り終えたのかを画面が確かめてから `Recovery.retry` を呼ぶ)
 * - `reload` … 同じものを頼み直す (ライブは同じ局、追っかけはいまの位置から、録画は `prepare` し直す)
 */
class ReconnectPlan(
    val label: String,
    val stream: Boolean,
    val endedIsLost: Boolean = false,
    val reload: (ExoPlayer) -> Unit,
)

/**
 * **繋ぎ直しの最中の様子と、待っている目覚まし。** ブラウザの denpa の `reconnect` にあたる (data/Reconnect.kt)。
 *
 * - 見つけたら (エラー・流れが終わった・止まった) `Reconnect.delayMs` だけ待って `ReconnectPlan.reload`。待っている間も前の絵は残る
 *   (Media3 は面を消さない)。`active` の間は幕が「繋ぎ直しています」を出す (`LoadingVeil`。1.5 秒待ってから)
 * - 次の絵が出たら (`pictured`) 数え直す。局を替えた・画面が頼み直した (`requested`) ら、待っている繋ぎ直しは捨てる
 * - **裏に回っている間は繋ぎ直さない** (`started`。戻れば画面が頼み直す)。止めている (一時停止) 間は、動かしたときに繋ぎ直す
 * - 繋ぎ直すたびに、理由を logcat に1行 (`Log.i`、タグ `denpa`)。チューナーのドライバ・denpa・アプリのどこで切れたかを追うため
 */
@Stable
class Recovery(private val player: ExoPlayer, private val scope: CoroutineScope) {
    /** 繋ぎ直しの最中 (見つけてから次の絵が出るまで) */
    var active by mutableStateOf(false)
        private set

    /** `active` になったとき (uptime ミリ秒)。幕が回るものを出すまで数える */
    var since by mutableLongStateOf(0L)
        private set

    var plan: ReconnectPlan? = null

    /** アプリが前に出ているか (`Lifecycle.State.STARTED` 以上) */
    var started = true
        set(value) {
            field = value
            if (!value) cancel()
        }

    /** 続けて失敗した回数・そのうち直らないかもしれないもの (`Reconnect.Verdict.RetryFew`) の回数 */
    private var attempts = 0
    private var few = 0
    private var job: Job? = null
    /** 目覚ましが鳴って頼み直している (`requested` で、画面が自分で頼んだのと見分ける) */
    private var firing = false
    /** 止めている間に切れた。動かしたら繋ぎ直す */
    private var deferred: Pair<String, Reconnect.Verdict>? = null
    /** いまの頼みで絵を出したか・出したとき */
    var pictured = false
        private set
    private var picturedAt = 0L
    private val stall = StallWatch()

    /** 画面が頼み直した (局を替えた・位置を変えた・裏から戻った・目覚ましで頼み直した) */
    fun requested() {
        if (firing) {
            firing = false
        } else {
            cancel()
            attempts = 0
            few = 0
        }
        pictured = false
    }

    /** 次の絵が出た。数え直す */
    fun onPictured() {
        pictured = true
        picturedAt = SystemClock.uptimeMillis()
        attempts = 0
        few = 0
        active = false
    }

    /** 待っている繋ぎ直しを捨てる (裏に回った・画面が諦めた) */
    fun cancel() {
        job?.cancel()
        job = null
        deferred = null
        firing = false
        active = false
    }

    /** 映してからの長さ (秒)。logcat に出す */
    fun playedSeconds(): Long = if (pictured) (SystemClock.uptimeMillis() - picturedAt) / 1000 else 0

    /**
     * 繋ぎ直す (待ってから)。繋ぎ直すことにした・もう待っている・あとで繋ぎ直すなら true。
     * 諦めた (`verdict` が直らないもの・回数を使い切った・決まりが無い) なら false (画面は理由を出す)
     */
    fun retry(reason: String, verdict: Reconnect.Verdict = Reconnect.Verdict.Retry): Boolean {
        val plan = plan ?: return false
        if (job != null) return true
        if (!Reconnect.shouldRetry(verdict, plan.stream, attempts, few)) {
            Log.i(TAG, "繋ぎ直しません (${plan.label}): $reason。続けて $attempts 回失敗")
            cancel()
            return false
        }
        // 裏に回っている。戻れば画面が頼み直す
        if (!started) return true
        if (!player.playWhenReady) {
            deferred = reason to verdict
            return true
        }
        val wait = Reconnect.delayMs(attempts)
        attempts++
        if (verdict == Reconnect.Verdict.RetryFew) few++
        if (!active) {
            active = true
            since = SystemClock.uptimeMillis()
        }
        Log.i(TAG, "繋ぎ直します (${plan.label}): $reason。${attempts} 回目、${wait} ms 待つ")
        job = scope.launch {
            delay(wait)
            job = null
            if (!started) return@launch
            firing = true
            plan.reload(player)
        }
        return true
    }

    /** 止めていた間に切れていたら、動かしたときに繋ぎ直す */
    fun onPlayWhenReady(playWhenReady: Boolean) {
        if (!playWhenReady) return
        val (reason, verdict) = deferred ?: return
        deferred = null
        retry(reason, verdict)
    }

    /** 止まったかを見る (1 秒おき)。流しっぱなしのものを、映したあと・動かしている間・繋ぎ直しを待っていない間だけ */
    fun watch() {
        val watching = plan?.stream == true && pictured && job == null && player.playWhenReady &&
            (player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY)
        val stalled = stall.check(SystemClock.uptimeMillis(), watching, player.currentPosition, player.bufferedPosition) ?: return
        retry("stall ${stalled / 1000} 秒進まない (映して ${playedSeconds()} 秒)")
    }

    companion object {
        const val TAG = "denpa"
    }
}
