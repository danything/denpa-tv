package io.github.danything.denpatv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import io.github.danything.denpatv.data.AudioTrack
import io.github.danything.denpatv.data.audioTrack
import io.github.danything.denpatv.data.rememberedAudio
import kotlinx.coroutines.launch

/**
 * 字幕と音声の切り替え。**ブラウザの denpa の再生の字幕・音声のボタンにあたる。** Media3 のトラックの選び方で切り替える。
 *
 * - 字幕: 入れ切り (端末ごとに覚える)。字幕のトラックがあるときだけ札を出す
 * - 音声: 押すたびに次の音声へ。2本以上あるときだけ札を出す。名前の付いた音声 (「解説ステレオ」など) は覚えて、
 *   次に同じ名前があればそれで始める。**1本の中の二か国語 (デュアルモノ) は分けられない** (左右に分かれて同時に鳴る)
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

@Composable
fun rememberTracks(repo: Repository, player: ExoPlayer, onChange: (String) -> Unit = {}): TrackControls {
    val subtitles by repo.app.settings.subtitles.collectAsState(initial = true)
    val remembered by repo.app.settings.audioLabel.collectAsState(initial = null)
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
    val audio = audioGroups.mapIndexed { index, group -> group.getTrackFormat(0).let { audioTrack(index, it.label, it.language) } }
    val selectedAudio = audioGroups.indexOfFirst { it.isSelected }.coerceAtLeast(0)
    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }

    fun selectAudio(index: Int) {
        val group = audioGroups.getOrNull(index) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    // 字幕の入れ切り。入れるときは、どれも選ばれていなければ最初の字幕を選ぶ (既定の印が無い字幕は選ばれないので)
    LaunchedEffect(subtitles, textGroups.size) {
        val builder = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitles)
        if (subtitles && textGroups.isNotEmpty() && textGroups.none { it.isSelected }) {
            builder.setOverrideForType(TrackSelectionOverride(textGroups.first().mediaTrackGroup, 0))
        }
        player.trackSelectionParameters = builder.build()
    }
    // 覚えている名前の音声があれば、それにする (並びが変わるたび。選んだものと同じなら何もしない)
    LaunchedEffect(audio, remembered) {
        val index = rememberedAudio(audio, remembered) ?: return@LaunchedEffect
        if (index != selectedAudio) selectAudio(index)
    }

    return TrackControls(
        subtitles = subtitles,
        hasText = textGroups.isNotEmpty(),
        audio = audio,
        selectedAudio = selectedAudio,
        toggleSubtitles = {
            scope.launch { repo.app.settings.setSubtitles(!subtitles) }
            onChange(if (subtitles) "字幕 切" else "字幕 入")
        },
        nextAudio = {
            val next = (selectedAudio + 1) % audio.size
            selectAudio(next)
            val track = audio[next]
            // 名前の付いたものだけ覚える。番号だけのものを選んだら忘れる (次に別の番組で副音声にならないように)
            scope.launch { repo.app.settings.setAudioLabel(track.label.takeIf { track.named }) }
            onChange("音声 ${track.label}")
        },
    )
}
