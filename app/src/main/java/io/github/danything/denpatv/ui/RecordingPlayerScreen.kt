package io.github.danything.denpatv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import io.github.danything.denpatv.data.pickFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 録画を観る。解ける中でいちばん軽いファイルを開き (AV1 → H.264 → 生の TS)、
 * 左右で 10 秒戻す / 30 秒送る、決定で止める・動かす。
 * **観た位置は denpa に預ける** (15 秒ごとと、閉じるとき)。ブラウザで続きから観られる
 */
@OptIn(UnstableApi::class)
@Composable
fun RecordingPlayerScreen(repo: Repository, recordingId: Long) {
    val recording = remember { repo.recordings.firstOrNull { it.id == recordingId } }
    if (recording == null) {
        Centered("録画が見つかりません")
        return
    }
    val file = remember { pickFile(recording.files, repo.app.decoders) }
    if (file == null) {
        Centered("この端末で再生できる形のファイルがありません")
        return
    }
    val (player, error) = rememberPlayer(repo)
    val (overlay, flash) = rememberFlash()

    LaunchedEffect(file) {
        val url = repo.url(file.url) ?: return@LaunchedEffect
        val mime = if (file.source == "ts") MimeTypes.VIDEO_MP2T else MimeTypes.VIDEO_MATROSKA
        player.setMediaItem(MediaItem.Builder().uri(url, mime))
        player.prepare()
        player.playWhenReady = true
        flash("${recording.title}  ${file.codec.uppercase()}")
    }

    fun save() {
        val at = player.currentPosition / 1000.0
        val length = player.duration.takeIf { it != C.TIME_UNSET }?.div(1000.0) ?: 0.0
        // 画面はもう閉じるので、アプリの寿命で送る (画面の scope だと送る前に取り消される)
        if (at > 0) repo.app.scope.launch { repo.app.api.saveResume(repo.base, recording.id, at, length) }
    }
    LaunchedEffect(player) {
        while (true) {
            delay(15_000)
            if (player.isPlaying) repo.app.api.saveResume(
                repo.base,
                recording.id,
                player.currentPosition / 1000.0,
                player.duration.takeIf { it != C.TIME_UNSET }?.div(1000.0) ?: 0.0,
            )
        }
    }
    // 閉じるときに1回。player を畳むのは rememberPlayer の後始末で、こちらが先に走る
    DisposableEffect(Unit) { onDispose { save() } }

    PlayerFrame(player, overlay, error) { event ->
        when {
            event.key in listOf(Key.DirectionLeft, Key.MediaRewind) -> {
                player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); flash(position(player.currentPosition)); true
            }
            event.key in listOf(Key.DirectionRight, Key.MediaFastForward) -> {
                player.seekTo(player.currentPosition + 30_000); flash(position(player.currentPosition)); true
            }
            event.key in listOf(Key.DirectionCenter, Key.Enter, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause) -> {
                player.playWhenReady = !player.playWhenReady
                flash(if (player.playWhenReady) "再生" else "一時停止  ${position(player.currentPosition)}")
                true
            }
            else -> false
        }
    }
}

private fun position(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}
