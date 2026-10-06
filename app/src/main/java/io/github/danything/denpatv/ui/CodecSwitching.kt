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
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.danything.denpatv.data.CodecSwitch
import io.github.danything.denpatv.data.LiveQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ライブ・追っかけの画質の切り替え (`CodecSwitch`)。選んだら「AV1 に切り替え中」、新しい流れの最初の絵で「AV1 にしました」、
 * 映せなければ元の画質に戻して理由を1行。**前の絵は残したまま** (回るものは `PlayerFrame` がいつもどおり 1.5 秒たってから)
 */
@Stable
class CodecSwitching internal constructor(
    private val state: MutableState<CodecSwitch>,
    private val note: MutableState<String?>,
    private val scope: CoroutineScope,
    private val repo: Repository,
    private val say: () -> (String) -> Unit,
) {
    /** 局の知らせに添える画質 (切り替え中ならそう言う) */
    val label: String get() = state.value.label

    /**
     * メニュー・帯の見出しに出す画質。済んだ・切り替えられなかったときは、その1行をしばらく — メニューを開いている間は
     * 下の1行の知らせが出ないので、見出しで知らせる
     */
    val heading: String get() = note.value ?: state.value.label

    /** 札で入っているもの。選んだものをすぐ入れる */
    val chosen: LiveQuality get() = state.value.pending ?: state.value.shown

    /** 札で選んだ。同じものなら何もしない。`before` は切り替えるときだけ (追っかけは居た場所を覚える) */
    fun choose(next: LiveQuality, before: () -> Unit = {}) {
        val switched = state.value.choose(next) ?: return
        before()
        state.value = switched
        note.value = null
        // 切り替え中に映っている画質を選び直したら、切り替えをやめるだけ (見出しは元の画質に戻る)
        if (switched.pending != null) say()("${next.label} に切り替え中")
        scope.launch { repo.app.settings.setLiveQuality(next) }
    }

    /** 流れを頼んだ (`setMediaItem` のたびに) */
    fun requested(quality: LiveQuality) {
        state.value = state.value.requested(quality)
    }
}

/**
 * `failure` は画面に出しているエラー (出ていなければ null)。**新しい画質の流れを頼んだあとにエラーが出たら、切り替えられなかったことにして
 * 元の画質に戻す。** 選んでから `SWITCH_GIVE_UP_MS` たっても映らないときも同じ (denpa が焼くのを断り続ける 503 などは、繋ぎ直しが
 * 諦めずに続くので、前の絵が暗くなって回り続けるだけになる)。繋ぎ直しの1回目 (空の返事・一瞬の切れ) では諦めない
 */
@Composable
fun rememberCodecSwitching(
    repo: Repository,
    player: ExoPlayer,
    quality: LiveQuality,
    failure: String?,
    flash: (String) -> Unit,
): CodecSwitching {
    val state = remember { mutableStateOf(CodecSwitch(quality)) }
    val note = remember { mutableStateOf<String?>(null) }
    val flashing by rememberUpdatedState(flash)
    /** 済んだ・切り替えられなかった: 下の1行と見出し (`heading`) の両方に */
    val tell = { text: String ->
        note.value = text
        flashing(text)
    }
    LaunchedEffect(note.value) {
        if (note.value == null) return@LaunchedEffect
        delay(NOTE_MS)
        note.value = null
    }
    // MPEG-2 との行き来ではプレーヤーごと作り直すので、プレーヤーごとに見る
    DisposableEffect(player) {
        fun pictured() {
            val (next, done) = state.value.pictured()
            state.value = next
            done?.let { tell("${it.label} にしました") }
        }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() = pictured()
            // 絵の無い局 (ラジオ) では最初の絵の知らせが来ないので、流れはじめたことでも済ませる (`Recovery.onPictured` と同じ)
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) pictured()
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
        // 元の画質で頼み直す (覚えている画質も戻す)
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
    return remember(repo) { CodecSwitching(state, note, scope, repo) { flashing } }
}

/** 済んだ・切り替えられなかったを見出しに出しておく間 (ミリ秒)。下の1行の知らせ (`rememberFlash`) と同じ */
private const val NOTE_MS = 4_000L

/** 切り替えを諦めるまで (ミリ秒)。denpa が焼きはじめるのを待つ分 (ふつうは数秒) より十分に長く */
private const val SWITCH_GIVE_UP_MS = 15_000L
