package io.github.danything.denpatv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.danything.denpatv.data.CodecSwitch
import io.github.danything.denpatv.data.LiveQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ライブ・追っかけの画質の切り替え (`CodecSwitch`)。選んだら「AV1 に切り替え中」、新しいストリームの最初のフレームで「AV1 にしました」、
 * 映せなければ元の画質に戻して理由を1行。**前の映像は残したまま** (回るものは `PlayerFrame` がいつもどおり 1.5 秒たってから)
 */
@Stable
class CodecSwitching internal constructor(
    private val state: MutableState<CodecSwitch>,
    private val scope: CoroutineScope,
    private val repo: Repository,
    private val say: () -> (String) -> Unit,
    private val switching: () -> (LiveQuality) -> Unit,
) {
    /**
     * 局の通知・メニューと操作バーのタイトルに添える画質 (切り替え中ならそう言う)。済んだ・切り替えられなかったは通知 (`flash`) で言う —
     * メニュー・操作バーを開いている間は、そのタイトルの2行目に出る (`LocalBarNotice`)
     */
    val label: String get() = state.value.label

    /** ボタンで選んでいるもの。選んだものをすぐ反映する */
    val chosen: LiveQuality get() = state.value.pending ?: state.value.shown

    /** ボタンで選んだ。同じものなら何もしない */
    fun choose(next: LiveQuality) {
        val switched = state.value.choose(next) ?: return
        state.value = switched
        switching()(next)
        if (switched.pending != null) {
            say()("${next.label} に切り替え中")
        } else {
            // 切り替え中に映っている画質を選び直した: 切り替えをやめる (「… に切り替え中」の1行を残さない)
            say()("${next.label} のまま")
        }
        scope.launch { repo.app.settings.setLiveQuality(next) }
    }

    /** ストリームをリクエストした (`load` のたびに) */
    fun requested(quality: LiveQuality) {
        state.value = state.value.requested(quality)
    }
}

/**
 * `failure` は画面に出しているエラー (出ていなければ null)。**新しい画質のストリームをリクエストしたあとにエラーが出たら、切り替えられなかったことにして
 * 元の画質に戻す。** 選んでから `SWITCH_GIVE_UP_MS` たっても映らないときも同じ (denpa の入れ替えが続く 503 などは、繋ぎ直しが
 * 諦めずに続くので、前の映像が暗くなって回り続けるだけになる)。繋ぎ直しの1回目 (空の返事・一瞬の切れ) では諦めない
 */
@Composable
fun rememberCodecSwitching(
    repo: Repository,
    player: ExoPlayer,
    quality: LiveQuality,
    failure: String?,
    flash: (String) -> Unit,
    /**
     * 覚えている画質を替える直前 (選んだ・切り替えをやめた・諦めて戻す)。引数は替える先。
     * 追っかけはここでいた位置を覚え、画質が替わってからそこからリクエストし直す
     */
    onSwitching: (LiveQuality) -> Unit = {},
): CodecSwitching {
    val switching by rememberUpdatedState(onSwitching)
    val state = remember { mutableStateOf(CodecSwitch(quality)) }
    val flashing by rememberUpdatedState(flash)
    /** 済んだ・切り替えられなかった */
    val tell = { text: String -> flashing(text) }
    // MPEG-2 との行き来ではプレーヤーごと作り直すので、プレーヤーごとに見る
    DisposableEffect(player) {
        fun pictured() {
            val (next, done) = state.value.pictured()
            state.value = next
            done?.let { tell("${it.label} にしました") }
        }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() = pictured()
            // 映像の無い局 (ラジオ) では最初のフレームの通知が来ないので、流れはじめたことで済ませる。映像のあるストリームでは、音だけでは済ませない
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && !player.currentTracks.containsType(C.TRACK_TYPE_VIDEO)) pictured()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    suspend fun giveUp(reason: String) {
        val (next, failed) = state.value.failed()
        failed ?: return
        state.value = next
        tell("${failed.label} に切り替えられません: $reason")
        switching(next.shown)
        // 元の画質でリクエストし直す (覚えている画質も戻す)
        repo.app.settings.setLiveQuality(next.shown)
    }
    LaunchedEffect(failure) {
        val reason = failure ?: return@LaunchedEffect
        if (state.value.awaiting) giveUp(reason.removePrefix(PLAYBACK_ERROR_PREFIX))
    }
    val pending = state.value.pending
    LaunchedEffect(pending) {
        pending ?: return@LaunchedEffect
        delay(SWITCH_GIVE_UP_MS)
        giveUp("映像が届きません")
    }
    val scope = rememberCoroutineScope()
    return remember(repo) { CodecSwitching(state, scope, repo, { flashing }, { switching }) }
}

/** 切り替えを諦めるまで (ミリ秒)。denpa がエンコードを始めるのを待つ分 (ふつうは数秒) より十分に長く */
private const val SWITCH_GIVE_UP_MS = 15_000L
