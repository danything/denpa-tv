package io.github.danything.denpatv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import io.github.danything.denpatv.R
import io.github.danything.denpatv.data.AudioSide
import io.github.danything.denpatv.data.AudioTrack
import io.github.danything.denpatv.data.DenpaAudio
import io.github.danything.denpatv.data.audioChoices
import io.github.danything.denpatv.data.audioTrack
import io.github.danything.denpatv.data.bakedAudio
import io.github.danything.denpatv.data.dualMonoLabels
import io.github.danything.denpatv.data.rememberedAudio
import io.github.danything.denpatv.data.selectedChoice
import kotlinx.coroutines.launch

/**
 * 字幕と音声の切り替え。**ブラウザの denpa の再生の字幕・音声のボタンにあたる。**
 *
 * - 字幕: 入れ切り (端末ごとに覚える)。denpa が選べる字幕があると言ったときだけ札を出す
 * - 音声: Media3 のトラックの選び方で、押すたびに次の音声へ。選べるものが2つ以上あるときだけ札を出す。名前の付いた音声 (「解説ステレオ」など) は覚えて、
 *   次に同じ名前があればそれで始める
 * - **1本の中の二か国語 (デュアルモノ)** は、denpa がそう言っていれば (`DenpaAudio`) ブラウザと同じく「主音声」「副音声」「主+副」の
 *   3つに分けて並べ、選んだ側を両耳へ配り直す (`DualMonoProcessor`)。どちら側かは端末ごとに覚える (既定は主音声)
 */
class TrackControls(
    val subtitles: Boolean,
    val hasText: Boolean,
    val audio: List<AudioTrack>,
    val selectedAudio: Int,
    val toggleSubtitles: () -> Unit,
    val nextAudio: () -> Unit,
) {
    /** 操作の列に足す札 (出すものが無ければ空) */
    fun controls(): List<Control> = buildList {
        if (hasText) add(Control(if (subtitles) "字幕 入" else "字幕 切", on = subtitles, icon = R.drawable.ic_subtitles) { toggleSubtitles() })
        if (audio.size >= 2) {
            add(Control(audio.getOrNull(selectedAudio)?.label ?: "音声", icon = R.drawable.ic_audio) { nextAudio() })
        }
    }
}

/**
 * @param captions denpa から受け取る字幕 (`rememberRawCaptions`・`rememberCaptionPages`)。選べる字幕があると言われたら札を出す
 * @param dualMono デュアルモノの配り直し (`rememberPlayer` の `dualMono`)
 * @param denpaAudios denpa が言う選べる音声 (`DenpaAudio`)。**生の TS のときだけ渡す** — 焼いたものは denpa が先に分けている
 *   (録画は主・副の2本に割って名前を付ける。ライブ・追っかけは選んだ1つだけを焼く。`rememberBakedAudio`) ので、配り直すものが無い
 */
@Composable
fun rememberTracks(
    repo: Repository,
    player: ExoPlayer,
    onChange: (String) -> Unit,
    captions: CaptionState,
    dualMono: DualMonoProcessor,
    denpaAudios: List<DenpaAudio>,
): TrackControls {
    val subtitles by repo.app.settings.subtitles.collectAsState(initial = true)
    val remembered by repo.app.settings.audioLabel.collectAsState(initial = null)
    val savedSide by repo.app.settings.dualMonoSide.collectAsState(initial = AudioSide.Main)
    /** この画面で選んだ側。覚えたものが届くのを待たずにすぐ効かせる */
    var pickedSide by remember { mutableStateOf<AudioSide?>(null) }
    val side = pickedSide ?: savedSide
    var tracks by remember { mutableStateOf(player.currentTracks) }
    val scope = rememberCoroutineScope()
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(changed: Tracks) {
                tracks = changed
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
    val groupTracks = audioGroups.mapIndexed { index, group -> group.getTrackFormat(0).let { audioTrack(index, it.label, it.language) } }
    val selectedGroup = audioGroups.indexOfFirst { it.isSelected }.coerceAtLeast(0)
    val choices = audioChoices(groupTracks, denpaAudios)
    val selectedAudio = selectedChoice(choices, selectedGroup, side)
    // 選んでいる音声がデュアルモノなら覚えている側を両耳へ、そうでなければそのまま
    val mix = choices.getOrNull(selectedAudio)?.side ?: AudioSide.Both
    SideEffect { dualMono.side = mix }

    fun selectAudio(index: Int) {
        val group = audioGroups.getOrNull(index) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    // 覚えている名前の音声があれば、それにする (並びが変わるたび。選んだものと同じなら何もしない)
    LaunchedEffect(groupTracks, remembered) {
        val index = rememberedAudio(groupTracks, remembered) ?: return@LaunchedEffect
        if (index != selectedGroup) selectAudio(index)
    }

    return TrackControls(
        subtitles = subtitles,
        hasText = captions.available,
        audio = choices.map { it.track },
        selectedAudio = selectedAudio,
        toggleSubtitles = {
            scope.launch { repo.app.settings.setSubtitles(!subtitles) }
            onChange(if (subtitles) "字幕 切" else "字幕 入")
        },
        nextAudio = {
            val next = choices[(selectedAudio + 1) % choices.size]
            if (next.group != selectedGroup) selectAudio(next.group)
            val track = next.track
            if (next.side != null) pickedSide = next.side
            scope.launch {
                // デュアルモノはどちら側かを覚える (名前は番組で変わるので覚えない)
                if (next.side != null) repo.app.settings.setDualMonoSide(next.side)
                // 名前の付いたものだけ覚える。番号だけのもの・デュアルモノの本へ移ったら忘れる (次に別の番組で副音声にならないように)。
                // 同じデュアルモノの本の中で側を替えただけなら、覚えている名前はそのまま
                if (next.group != selectedGroup || next.side == null) repo.app.settings.setAudioLabel(track.label.takeIf { track.named })
            }
            onChange("音声 ${track.label}")
        },
    )
}

/**
 * 焼いて流すライブ・追っかけの音声の切り替え。`audio` を denpa に頼む URL に足し (`audioQuery`)、変わったら頼み直す。
 * `ready` になるまで (覚えている側を読み終えるまで) は頼まない — 読む前に頼むと、すぐ頼み直しになる
 */
class BakedAudio(val ready: Boolean, val audio: DenpaAudio?, private val choices: List<DenpaAudio>, private val next: () -> Unit) {
    /** 操作の列に足す札 (選べるものが2つ以上あるときだけ) */
    fun controls(): List<Control> =
        if (choices.size < 2) emptyList() else listOf(Control(audio?.label?.takeIf { it.isNotBlank() } ?: "音声", icon = R.drawable.ic_audio) { next() })
}

/**
 * **焼いたライブ・追っかけで音声を選ぶ** (denpa の `?audio=<id>`)。並びは denpa の `audios` そのまま (ブラウザと同じく
 * デュアルモノは主・副・主+副の3つ)。押すたびに次へ。デュアルモノのどちら側かは生の TS と同じ設定に覚える。
 *
 * @param audios denpa が言う選べる音声。**焼くときだけ渡す** (生の TS はアプリが選ぶ。`rememberTracks`)
 * @param key 観ているもの (局・録画)。替わったら、この画面で選んだものを忘れる
 */
@Composable
fun rememberBakedAudio(repo: Repository, audios: List<DenpaAudio>, key: Any?, onChange: (String) -> Unit): BakedAudio {
    val savedSide by repo.app.settings.dualMonoSide.collectAsState(initial = null)
    var picked by remember(key) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val side = savedSide ?: AudioSide.Main
    val current = bakedAudio(audios, picked, side)
    return BakedAudio(savedSide != null, current, audios) {
        val at = audios.indexOfFirst { it.id == current?.id }
        val next = audios[(at + 1) % audios.size]
        picked = next.id
        val nextSide = AudioSide.of(next.side)
        if (nextSide != null && dualMonoLabels(audios, next.stream) != null) scope.launch { repo.app.settings.setDualMonoSide(nextSide) }
        onChange("音声 ${next.label}")
    }
}
