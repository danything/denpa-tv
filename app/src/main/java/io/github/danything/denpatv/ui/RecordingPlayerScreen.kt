package io.github.danything.denpatv.ui

import androidx.activity.compose.BackHandler
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
import io.github.danything.denpatv.data.SPEEDS
import io.github.danything.denpatv.data.Unauthorized
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
 * - メニューで操作の帯を開く: **速さ** (1 / 1.25 / 1.5 / 2 倍) と **CM 飛ばし** (既定で入)、**削除** (2回押し)。
 *   ブラウザの denpa の再生と同じく、観ながら変えて端末ごとに覚える。緑のボタンは速さを1段送る
 * - CM 飛ばしが入っていれば CM に入ったら終わりまで飛ぶ。区切りは動画に入っているチャプター (`CM` / `本編`)
 *
 * 観た位置は denpa に預ける (15 秒ごとと、閉じるとき)。ブラウザで続きから観られる
 */
@OptIn(UnstableApi::class)
@Composable
fun RecordingPlayerScreen(repo: Repository, recordingId: Long, onLeave: () -> Unit, onUnauthorized: () -> Unit) {
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
    var controls by remember { mutableStateOf(false) }
    BackHandler(enabled = controls) { controls = false }
    /** 最後まで観た (終わりの帯を出す)。勝手に一覧へは戻らない */
    var ended by remember { mutableStateOf(false) }
    /** 消した (閉じるときに観た位置を預けない) */
    var deleted by remember { mutableStateOf(false) }
    val delete = rememberTwoPress()
    val speed by repo.app.settings.playbackSpeed.collectAsState(initial = 1f)
    val scope = rememberCoroutineScope()
    // 速さは録画だけ (ライブは追いつくための 1.05 倍を自分で回す)。CM 飛ばしと観た位置は再生位置で見るので速さに関わらない
    LaunchedEffect(player, speed) { player.setPlaybackSpeed(speed) }
    fun length() = player.duration.takeIf { it != C.TIME_UNSET }?.div(1000.0) ?: 0.0
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

            /*
             * **最後まで来たら、すぐ終わりの位置を預ける。** 終わりの CM を飛ばして終わると、閉じるまで預けないうちは
             * denpa が観終えたと分からない (ブラウザの denpa の `finished()` と同じ)。末尾の位置を渡せば denpa が続きを消す
             */
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED || ended) return
                ended = true
                controls = false
                val length = length()
                repo.app.scope.launch { repo.api.saveResume(repo.base, recording.id, length, length) }
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
            if (at > 0 && !deleted) repo.app.scope.launch { repo.api.saveResume(repo.base, recording.id, at, length) }
        }
    }

    /** 消して一覧へ戻る。一覧からも抜き、隣に合わせる */
    fun deleteNow() {
        scope.launch {
            val done = try {
                repo.api.deleteRecording(repo.base, recording.id)
            } catch (_: Unauthorized) {
                return@launch onUnauthorized()
            }
            if (!done) return@launch flash("消せませんでした (録画中は消せません)")
            deleted = true
            repo.focusOnReturn = repo.forgetRecording(recording.id)
            onLeave()
        }
    }
    val deleteControl = Control(deleteLabel(delete.armed)) { if (delete.press()) deleteNow() }

    PlayerFrame(player, overlay, error, active = !controls && !ended, above = {
        if (ended) {
            ControlBar(
                "最後まで観ました",
                listOf("" to listOf(Control("一覧に戻る", on = true) { onLeave() }, deleteControl)),
            )
        } else if (controls) {
            ControlBar(
                recording.title,
                listOf(
                    "速さ" to SPEEDS.map { pace ->
                        Control(speedLabel(pace), on = pace == speed) {
                            scope.launch { repo.app.settings.setPlaybackSpeed(pace) }
                        }
                    },
                    "CM" to listOf(
                        Control(if (skipCm) "CMを自動で飛ばす: 入" else "CMを自動で飛ばす: 切", on = skipCm) {
                            scope.launch { repo.app.settings.setSkipCm(!skipCm) }
                        },
                    ),
                    "" to listOf(deleteControl),
                ),
            )
        }
    }, onKey = { event ->
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
            Key.Menu -> { controls = true; true }
            // 速さを1段送る (1 → 1.25 → 1.5 → 2 → 1)
            Key.ProgramGreen -> {
                val next = nextSpeed(speed)
                scope.launch { repo.app.settings.setPlaybackSpeed(next) }
                flash("速さ ${speedLabel(next)}")
                true
            }
            Key.DirectionCenter, Key.Enter, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                player.playWhenReady = !player.playWhenReady
                flash(
                    (if (player.playWhenReady) "再生" else "一時停止  ${position(player.currentPosition)}") +
                        "\n速さ ${speedLabel(speed)}・CM 飛ばし ${if (skipCm) "入" else "切"} (メニューで変える)",
                )
                true
            }
            else -> false
        }
    })
}
