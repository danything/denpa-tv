package io.github.danything.denpatv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.github.danything.denpatv.data.JikkyoRanking
import io.github.danything.denpatv.data.ListHold
import io.github.danything.denpatv.data.Unauthorized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * ライブのメニューの「実況」の列の勢いと並び (`rememberJikkyoRanking`)。部品は `ChannelRows`、決まりは `data/JikkyoRanking.kt`
 */
@Stable
class JikkyoRankingState internal constructor(scope: CoroutineScope) {
    /** 局ごとの勢い。読めていなければ null (数字を出さない) */
    var forces by mutableStateOf<Map<Long, Int>?>(null)
        private set

    /** 最後に読めなかったか (列の名前の横に「いまは勢いを読めません」) */
    var failed by mutableStateOf(false)
        private set

    /** 見せている並び (局の ID)。最初の返事までは null (番号順) */
    var order by mutableStateOf<List<Long>?>(null)
        private set

    /** 列を使っている間に届いた並び。離れたら `order` に移す */
    private var next: List<Long>? = null
    private val holder = ListHold(scope) { apply() }

    /** 実況の列にフォーカスが入った・出た */
    fun hold(on: Boolean) = holder.set(on)

    /** メニューを閉じた。待っている並びはすぐ入れる (次に開いたときは新しい並び) */
    fun release() {
        holder.clear()
        apply()
    }

    /** 実況をオフにした。勢いも並びも空に戻す (オンに戻したときに前の数字・並びを出さない。最初の返事までは番号順) */
    fun reset() {
        holder.clear()
        next = null
        forces = null
        failed = false
        order = null
    }

    /** 返事が届いた (`forces` が null なら読めなかった)。`ids` は実況のある局を番号順に */
    internal fun received(ids: List<Long>, forces: Map<Long, Int>?) {
        this.forces = forces
        failed = forces == null
        // 数字はすぐ替え、並びは列を使っていなければすぐ、使っていれば離れてから
        next = JikkyoRanking.order(ids, forces)
        if (!holder.held) apply()
    }

    private fun apply() {
        val pending = next ?: return
        next = null
        // 同じ並びなら書き換えない (カードを動かす合図にしない)
        if (pending != order) order = pending
    }
}

/**
 * **局の勢いを取る** (ブラウザの denpa のライブの「実況」タブと同じ決まり)。
 *
 * - `enabled` (実況のコメントを流せる: 世帯の設定と端末の設定の両方がオン) の間だけ、`JikkyoRanking.EVERY_MS` ごとに
 *   `GET api/jikkyo/ranking`。アプリが裏に回ったら止め、戻ったらすぐ1回取る (`OnBackground`)
 * - ブラウザはタブを開いている間だけ取るが、テレビはライブの画面にいる間ずっと取る。メニューを開いた瞬間に並び替えて、
 *   フォーカスしたカードが動かないように (denpa は 30 秒持つので、NX-Jikkyo を読むのは 30 秒に1回より増えない)
 * - 読めないときは数字を消して番号順に戻し、`failed` (通知は出さない)
 *
 * @param ids 実況のある局を番号順に (`JikkyoRanking.candidates`)
 */
@Composable
fun rememberJikkyoRanking(repo: Repository, enabled: Boolean, ids: List<Long>, onUnauthorized: () -> Unit): JikkyoRankingState {
    val scope = rememberCoroutineScope()
    val state = remember { JikkyoRankingState(scope) }
    val current by rememberUpdatedState(ids)
    val unauthorized by rememberUpdatedState(onUnauthorized)
    var foreground by remember { mutableStateOf(true) }
    OnBackground(onStop = { foreground = false }, onStart = { foreground = true })
    LaunchedEffect(enabled, foreground) {
        if (!enabled) return@LaunchedEffect state.reset()
        if (!foreground) return@LaunchedEffect
        while (true) {
            val forces = try {
                repo.api.jikkyoRanking(repo.base)
            } catch (_: Unauthorized) {
                return@LaunchedEffect unauthorized()
            }
            state.received(current, forces)
            delay(JikkyoRanking.EVERY_MS)
        }
    }
    return state
}
