package io.github.danything.denpatv.ui

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import io.github.danything.denpatv.data.ChapterMark
import io.github.danything.denpatv.data.nextSpeed
import io.github.danything.denpatv.data.speedLabel
import io.github.danything.denpatv.data.cmSkipTarget
import io.github.danything.denpatv.data.nextChapter
import io.github.danything.denpatv.data.pickFile
import io.github.danything.denpatv.data.previousChapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 録画を観る。解ける中でいちばん軽いファイルを開き (AV1 → H.264 → 生の TS)、
 * **続きの位置があればそこから** (denpa の `resumeMs`)。
 *
 * - 左右で 10 秒戻す / 30 秒送る、決定で止める・動かす
 * - 上下 (リモコンの次へ・前へ) でチャプター送り
 * - メニュー (緑のボタン) で速さを変える (1 → 1.25 → 1.5 → 2 倍。端末ごとに覚える)
 * - **CM は飛ばす** (設定で切れる)。区切りは動画に入っているチャプター (`CM` / `本編`)
 *
 * 観た位置は denpa に預ける (15 秒ごとと、閉じるとき)。ブラウザで続きから観られる
 */
@OptIn(UnstableApi::class)
@Composable
fun RecordingPlayerScreen(repo: Repository, recordingId: Long, onUnauthorized: () -> Unit) {
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
    val (player, error) = rememberPlayer(repo, Buffering.Recording, onUnauthorized)
    val (overlay, flash) = rememberFlash()
    val skipCm by repo.app.settings.skipCm.collectAsState(initial = true)
    val speed by repo.app.settings.playbackSpeed.collectAsState(initial = 1f)
    val scope = rememberCoroutineScope()
    // 速さは録画だけ (ライブは追いつくための 1.05 倍を自分で回す)。CM 飛ばしと観た位置は再生位置で見るので速さに関わらない
    LaunchedEffect(player, speed) { player.setPlaybackSpeed(speed) }
    var chapters by remember { mutableStateOf(emptyList<ChapterMark>()) }
    val skipped = remember { mutableSetOf<Long>() }

    LaunchedEffect(file) {
        val url = repo.url(file.url) ?: return@LaunchedEffect
        val mime = if (file.source == "ts") MimeTypes.VIDEO_MP2T else MimeTypes.VIDEO_MATROSKA
        val resume = recording.resumeMs ?: 0L
        player.setMediaItem(MediaItem.Builder().uri(url, mime), resume)
        player.prepare()
        player.playWhenReady = true
        val pace = repo.app.settings.playbackSpeed.first().takeIf { it != 1f }?.let { "  速さ ${speedLabel(it)}" } ?: ""
        flash(if (resume > 0) "${recording.title}\n続きから (${position(resume)})$pace" else "${recording.title}$pace")
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                chaptersOf(tracks).takeIf { it.isNotEmpty() }?.let { chapters = it }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // CM に入ったら終わりまで飛ぶ。続きから始めたときに CM の中だった場合も飛ぶ
    LaunchedEffect(player, chapters, skipCm) {
        if (!skipCm || chapters.none { it.isCm }) return@LaunchedEffect
        while (true) {
            delay(250)
            if (!player.isPlaying) continue
            val cm = cmSkipTarget(chapters, player.currentPosition, skipped) ?: continue
            skipped += cm.startMs
            player.seekTo(cm.endMs)
            flash("CM を飛ばしました")
        }
    }

    fun length() = player.duration.takeIf { it != C.TIME_UNSET }?.div(1000.0) ?: 0.0
    LaunchedEffect(player) {
        while (true) {
            delay(15_000)
            if (player.isPlaying) repo.api.saveResume(repo.base, recording.id, player.currentPosition / 1000.0, length())
        }
    }
    // 閉じるときに1回。画面はもう閉じるので、アプリの寿命で送る (画面の scope だと送る前に取り消される)。
    // player を畳むのは rememberPlayer の後始末で、こちらが先に走る
    DisposableEffect(Unit) {
        onDispose {
            val at = player.currentPosition / 1000.0
            val length = length()
            if (at > 0) repo.app.scope.launch { repo.api.saveResume(repo.base, recording.id, at, length) }
        }
    }

    PlayerFrame(player, overlay, error, onKey = { event ->
        when (event.key) {
            Key.DirectionLeft, Key.MediaRewind -> {
                val to = (player.currentPosition - 10_000).coerceAtLeast(0)
                // 戻して CM を観に行ったなら、そこは飛ばさない
                chapters.firstOrNull { it.isCm && to >= it.startMs && to < it.endMs }?.let { skipped += it.startMs }
                player.seekTo(to); flash(position(to)); true
            }
            Key.DirectionRight, Key.MediaFastForward -> {
                player.seekTo(player.currentPosition + 30_000); flash(position(player.currentPosition)); true
            }
            Key.DirectionUp, Key.MediaNext, Key.MediaSkipForward -> {
                // CM を飛ばしているなら、送り先も本編だけ (CM の頭に止まっても、すぐ飛ばされるだけ)
                val next = nextChapter(if (skipCm) chapters.filterNot { it.isCm } else chapters, player.currentPosition)
                if (next == null) flash(if (chapters.isEmpty()) "チャプターがありません" else "最後のチャプターです")
                else { player.seekTo(next.startMs); flash("${next.title}  ${position(next.startMs)}") }
                true
            }
            Key.DirectionDown, Key.MediaPrevious, Key.MediaSkipBackward -> {
                val previous = previousChapter(if (skipCm) chapters.filterNot { it.isCm } else chapters, player.currentPosition)
                if (previous == null) flash("チャプターがありません")
                else {
                    if (previous.isCm) skipped += previous.startMs
                    player.seekTo(previous.startMs); flash("${previous.title}  ${position(previous.startMs)}")
                }
                true
            }
            // 速さを変える (1 → 1.25 → 1.5 → 2 → 1)。メニューか緑のボタン
            Key.Menu, Key.ProgramGreen -> {
                val next = nextSpeed(speed)
                scope.launch { repo.app.settings.setPlaybackSpeed(next) }
                flash("速さ ${speedLabel(next)}")
                true
            }
            Key.DirectionCenter, Key.Enter, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                player.playWhenReady = !player.playWhenReady
                flash(
                    (if (player.playWhenReady) "再生" else "一時停止  ${position(player.currentPosition)}") +
                        "\n速さ ${speedLabel(speed)} (メニューで変える)",
                )
                true
            }
            else -> false
        }
    })
}

private fun position(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}
