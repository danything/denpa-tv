package io.github.danything.denpatv.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import io.github.danything.denpatv.R
import io.github.danything.denpatv.data.CenterPress
import io.github.danything.denpatv.data.ChapterMark
import io.github.danything.denpatv.data.recordingCommand
import io.github.danything.denpatv.data.SEEK_STEP_MS
import io.github.danything.denpatv.data.RecordingCommand
import io.github.danything.denpatv.data.Unauthorized
import io.github.danything.denpatv.data.nextSpeed
import io.github.danything.denpatv.data.resyncAfterSpeedChange
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
 * - 左右で 10 秒戻す・送る、決定で止める・動かす (キーの割り当ては data/Remote.kt と README の「操作」)
 * - **下でシークバー** (左右で 10 秒ずつ。CM は色を変えて出す)、下でその下の操作の列へ
 * - **上 (か決定の長押し・Menu) で操作の列**: 再生 / 一時停止、前・次のチャプター、**速さ** (押すたびに 1 / 1.25 / 1.5 / 2 倍)、
 *   **CM 飛ばし** (既定で入)、**削除** (2回押し)。ブラウザの denpa の再生と同じく、観ながら変えて端末ごとに覚える。
 *   動いている間は 5 秒触らなければ閉じる。戻るでも閉じる。緑のボタンは速さを1段送る
 * - リモコンの次へ・前へでチャプター送り
 * - CM 飛ばしが入っていれば CM に入ったら終わりまで飛ぶ。区切りは動画に入っているチャプター (`CM` / `本編`)
 *
 * 観た位置は denpa に預ける (15 秒ごとと、閉じるとき)。ブラウザで続きから観られる
 */
@OptIn(UnstableApi::class)
@Composable
fun RecordingPlayerScreen(repo: Repository, recordingId: Long, onLeave: () -> Unit, onUnauthorized: () -> Unit) {
    /** 一覧へ戻るところ (映像に合いを取り返させない。戻った先の一覧が開いた録画に合わせるので) */
    var leaving by remember { mutableStateOf(false) }
    val leave = { leaving = true; onLeave() }
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
    /** 開いている帯 (null なら何も出していない) */
    var bar by remember { mutableStateOf<Bar?>(null) }
    BackHandler(enabled = bar != null) { bar = null }
    BackHandler(enabled = bar == null) { leave() }
    /** 帯に出す位置と、止まっているか (帯を開いている間だけ取り直す) */
    var at by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    /** 帯で最後にキーを押したとき。動いている間は、5 秒触らなければ帯を閉じる */
    var touched by remember { mutableLongStateOf(0L) }
    // 帯を開いている間と止めている間 (止めると位置の帯を出す) は、位置を取り直す
    LaunchedEffect(bar, playing) {
        while (bar != null || !playing) {
            at = player.currentPosition
            playing = player.playWhenReady
            delay(500)
        }
    }
    LaunchedEffect(bar, touched, playing) {
        if (bar == null || !playing) return@LaunchedEffect
        delay(5_000)
        bar = null
    }
    /**
     * 一度でも流れ始めたか。速さを変えたときに飛び直すかの判定に使う (`resyncAfterSpeedChange`)。
     * 流れ始めたあとの読み込み中 (飛び直した直後の約1秒など) に続けて変えたときも飛び直す
     */
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(player) {
        while (player.playbackState != Player.STATE_READY) delay(250)
        started = true
    }
    /** 最後まで観た (終わりの帯を出す)。勝手に一覧へは戻らない */
    var ended by remember { mutableStateOf(false) }
    /** 消した (閉じるときに観た位置を預けない) */
    var deleted by remember { mutableStateOf(false) }
    val delete = rememberTwoPress()
    val speed by repo.app.settings.playbackSpeed.collectAsState(initial = 1f)
    val scope = rememberCoroutineScope()
    // 速さは録画だけ (ライブは追いつくための 1.05 倍を自分で回す)。CM 飛ばしと観た位置は再生位置で見るので速さに関わらない
    // 観ている途中で変えたら、今の位置に飛び直して音と映像を新しい速さで流し直す (`resyncAfterSpeedChange`)
    LaunchedEffect(player, speed) {
        val from = player.playbackParameters.speed
        player.setPlaybackSpeed(speed)
        val flowing = started && player.playbackState != Player.STATE_ENDED
        if (resyncAfterSpeedChange(from, speed, flowing)) player.seekTo(player.currentPosition)
    }
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
        flash((if (resume > 0) "${recording.title}\n続きから (${position(resume)})$pace" else "${recording.title}$pace") + "\n$RECORDING_HINT")
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
                bar = null
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
            leave()
        }
    }
    /** 止める・動かす。止めている間は位置の帯 (シークバーと同じ見た目、合わせない) を出したままにする */
    fun togglePause() {
        player.playWhenReady = !player.playWhenReady
        playing = player.playWhenReady
        at = player.currentPosition
        if (playing) flash("再生  速さ ${speedLabel(speed)}・CM 飛ばし ${if (skipCm) "入" else "切"}")
    }
    /** 10 秒ずつ戻す・送る (左右とシークバー)。戻して CM を観に行ったなら、そこは飛ばさない */
    fun step(direction: Int): Long {
        val end = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        val to = (player.currentPosition + direction * SEEK_STEP_MS).coerceIn(0, end)
        if (direction < 0) chapters.firstOrNull { it.isCm && to >= it.startMs && to < it.endMs }?.let { skipped += it.startMs }
        player.seekTo(to)
        at = to
        return to
    }
    // CM を飛ばしているなら、送り先も本編だけ (CM の頭に止まっても、すぐ飛ばされるだけ)
    fun nextChapterNow() {
        val next = nextChapter(if (skipCm) chapters.filterNot { it.isCm } else chapters, player.currentPosition)
        if (next == null) flash(if (chapters.isEmpty()) "チャプターがありません" else "最後のチャプターです")
        else { player.seekTo(next.startMs); at = next.startMs; flash("${next.title}  ${position(next.startMs)}") }
    }
    fun previousChapterNow() {
        val previous = previousChapter(if (skipCm) chapters.filterNot { it.isCm } else chapters, player.currentPosition)
        if (previous == null) flash("チャプターがありません")
        else {
            if (previous.isCm) skipped += previous.startMs
            player.seekTo(previous.startMs); at = previous.startMs; flash("${previous.title}  ${position(previous.startMs)}")
        }
    }
    fun open(which: Bar) {
        at = player.currentPosition
        playing = player.playWhenReady
        bar = which
    }
    val deleteControl = Control(deleteLabel(delete.armed), icon = R.drawable.ic_delete) { if (delete.press()) deleteNow() }
    val seekFocus = remember { FocusRequester() }

    PlayerFrame(player, overlay, error, active = bar == null && !ended && !leaving, above = {
        if (ended) {
            ControlBar(
                "最後まで観ました",
                // 観終えたものは消すことが多いので、最初は削除に合わせる (2回押しなので、1回では消えない)
                listOf("" to listOf(Control("一覧に戻る", icon = R.drawable.ic_back) { leave() }, deleteControl.copy(initial = true))),
            )
        } else if (bar == null && !playing) {
            // 止めている間の位置の帯。キーは映像が受けたまま (左右で 10 秒、決定で動かす、下でシークバー、上・長押しで操作の列)
            val total = player.duration.takeIf { it != C.TIME_UNSET }
            ControlBar(
                "一時停止  ${recording.title}\n${position(at)}${total?.let { " / ${position(it)}" } ?: ""}  ・決定で再生  $RECORDING_HINT",
                emptyList(),
                header = { ProgressLine(at, total ?: 0, chapters) },
                focusActions = false,
            )
        } else bar?.let { which ->
            val total = player.duration.takeIf { it != C.TIME_UNSET }
            LaunchedEffect(which) { if (which == Bar.SeekBar) runCatching { seekFocus.requestFocus() } }
            ControlBar(
                "${recording.title}\n${position(at)}${total?.let { " / ${position(it)}" } ?: ""}",
                listOf(
                    "" to listOf(
                        Control(if (playing) "一時停止" else "再生", on = true, icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play) { togglePause() },
                        Control("前のチャプター", icon = R.drawable.ic_previous) { previousChapterNow() },
                        Control("次のチャプター", icon = R.drawable.ic_next) { nextChapterNow() },
                    ),
                    // 速さは1つの札で送る (1 → 1.25 → 1.5 → 2 → 1)。列を短くして、押す回数も少なく
                    "" to listOf(
                        Control("速さ ${speedLabel(speed)}", on = speed != 1f, icon = R.drawable.ic_speed) {
                            scope.launch { repo.app.settings.setPlaybackSpeed(nextSpeed(speed)) }
                        },
                    ),
                    "" to listOf(
                        Control(if (skipCm) "CM 飛ばし 入" else "CM 飛ばし 切", on = skipCm, icon = R.drawable.ic_skip_cm) {
                            scope.launch { repo.app.settings.setSkipCm(!skipCm) }
                        },
                    ),
                    "" to listOf(deleteControl),
                ),
                header = { actions ->
                    ProgressLine(at, total ?: 0, chapters, seekFocus, down = actions) { direction -> step(direction) }
                },
                focusActions = which == Bar.Actions,
                onActivity = { touched = System.nanoTime() },
            )
        }
    }, onKey = { event ->
        when (recordingCommand(event.nativeKeyEvent.keyCode)) {
            // 止めている間は位置の帯に出るので、知らせは出さない
            RecordingCommand.Back -> { step(-1).let { if (playing) flash(position(it)) }; true }
            RecordingCommand.Forward -> { step(1).let { if (playing) flash(position(it)) }; true }
            RecordingCommand.SeekBar -> { open(Bar.SeekBar); true }
            RecordingCommand.Actions -> { open(Bar.Actions); true }
            RecordingCommand.NextChapter -> { nextChapterNow(); true }
            RecordingCommand.PreviousChapter -> { previousChapterNow(); true }
            RecordingCommand.PlayPause -> { togglePause(); true }
            // 速さを1段送る (1 → 1.25 → 1.5 → 2 → 1)
            RecordingCommand.NextSpeed -> {
                val next = nextSpeed(speed)
                scope.launch { repo.app.settings.setPlaybackSpeed(next) }
                flash("速さ ${speedLabel(next)}")
                true
            }
            null -> false
        }
    }, onCenter = { press ->
        when (press) {
            CenterPress.Action.Short -> togglePause()
            CenterPress.Action.Long -> open(Bar.Actions)
        }
    })
}

/** 録画の帯をどこに合わせて開いたか。下キーならシークバー、上キー・決定の長押し・Menu なら操作の列 */
private enum class Bar { SeekBar, Actions }

private const val RECORDING_HINT = "下でシークバー・上でメニュー"
