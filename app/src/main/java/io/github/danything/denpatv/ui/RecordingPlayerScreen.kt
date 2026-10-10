package io.github.danything.denpatv.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.danything.denpatv.data.CaptionPaths
import io.github.danything.denpatv.data.ChapterMark
import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.RecordingCenter
import io.github.danything.denpatv.data.recordingCenter
import io.github.danything.denpatv.data.recordingCommand
import io.github.danything.denpatv.data.SEEK_STEP_MS
import io.github.danything.denpatv.data.skipCmAtStart
import io.github.danything.denpatv.data.RecordingCommand
import io.github.danything.denpatv.data.resyncAfterSpeedChange
import io.github.danything.denpatv.data.nextSpeed
import io.github.danything.denpatv.data.speedLabel
import io.github.danything.denpatv.data.CM_LEAD_MS
import io.github.danything.denpatv.data.cmHopPoints
import io.github.danything.denpatv.data.cmRunAt
import io.github.danything.denpatv.data.cmSkipTarget
import io.github.danything.denpatv.data.nextChapter
import io.github.danything.denpatv.data.pickFile
import io.github.danything.denpatv.data.previousChapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 録画を観る。解ける中でいちばん軽いファイルを開き (AV1 → H.264 → 生の TS)、
 * **続きの位置があればそこから** (denpa の `resumeMs`)。
 *
 * - 左右で 10 秒戻す・送る、決定で止める・動かす (キーの割り当ては data/Remote.kt と README の「操作」)
 * - **下でシークバー** (左右で 10 秒ずつ。CM は色を変えて出す)、下でその下の操作の列へ
 * - **上 (か Menu) で操作の列**。**決定の長押しで番組の詳しいところ** (一覧のカードの長押しと同じ。削除もそこから)。帯の中は上下で操作の列とシークバーを行き来し、**シークバー (いちばん上) から上で閉じる**。操作の列: 再生 / 一時停止、前へ・次へ (チャプター)、**速さ** (押すたびに 1 / 1.25 / 1.5 / 2 倍)、
 *   **CM 飛ばし** (既定で入。ロゴで CM を判定できなかった録画は切で始まる)、**字幕**・**音声** (あれば)、**削除** (2回押し)。ブラウザの denpa の再生と同じく、観ながら変えて端末ごとに覚える。
 *   5 秒触らなければ閉じる (止めている間も)。戻るでも閉じる。緑のボタンは速さを1段送る
 * - リモコンの次へ・前へでチャプター送り
 * - CM 飛ばしが入っていれば CM の頭の少し手前で終わりまで飛ぶ (CM のコマは映さない)。区切りは動画に入っているチャプター (`CM` / `本編`)
 *
 * 観た位置は denpa に預ける (15 秒ごとと、閉じるとき)。ブラウザで続きから観られる
 */
@OptIn(UnstableApi::class)
@Composable
fun RecordingPlayerScreen(repo: Repository, recording: Recording, onLeave: () -> Unit, onUnauthorized: () -> Unit) {
    /** 一覧へ戻るところ (映像に合いを取り返させない。戻った先の一覧が開いた録画に合わせるので) */
    var leaving by remember { mutableStateOf(false) }
    // 戻るを続けて押しても、1つだけ戻る (2回目は受けない)
    val leave = { if (!leaving) { leaving = true; onLeave() } }
    val file = remember { pickFile(recording.files, repo.app.decoders) }
    if (file == null) {
        Centered("この端末で再生できる形のファイルがありません")
        return
    }
    val clock = remember { TsClock() }
    /*
     * 録画のファイルは、回線が切れたときだけいまの位置から読み直す (`prepare` し直すと、Media3 は止まった位置から続ける)。
     * Media3 が中で読み直したうえでの失敗なので、何度か (`Reconnect.FEW`) まで。中身が読めない・解けないのは繋ぎ直しても同じなので出して止める
     */
    val (player, error, dualMono, recovery) = rememberPlayer(
        repo,
        Buffering.Recording,
        onUnauthorized,
        clock,
        ReconnectPlan("recording 録画 ${recording.id}", stream = false) { it.prepare() },
    )
    val (overlay, flash) = rememberFlash()
    val scope = rememberCoroutineScope()
    /** 飛んだ回数。生の TS の字幕を、飛んだ先から頼み直す */
    var seeks by remember { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) seeks++
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    /*
     * 字幕は denpa から別の口で受け取る。生の TS は字幕の口、焼いた録画は文字の配置まるごと (`captions.json`)。
     * 焼いた録画で 404 なら字幕が絵 (PGS) で動画に入っている前の録画で、そちらは Media3 が出す。ファイルは開いている間替わらない
     */
    val captions = if (file.source == "ts") {
        rememberRawCaptions(
            repo,
            player,
            clock,
            path = CaptionPaths.recording(recording.id),
            generation = seeks,
            fromMs = { player.currentPosition },
            onUnauthorized = onUnauthorized,
        )
    } else {
        rememberCaptionPages(repo, player, CaptionPaths.recordingText(recording.id), onUnauthorized)
    }
    // デュアルモノの主・副は、生の TS のときだけ配り直す (焼いた録画は denpa が主・副の2本に割ってある)
    val tracks = rememberTracks(repo, player, flash, captions, dualMono, denpaAudios = recording.audios.takeIf { file.source == "ts" }.orEmpty())
    /**
     * CM 飛ばし。観はじめはブラウザと同じく、覚えている設定に従うが、ロゴで CM を判定できなかった録画は切って始める
     * (`skipCmAtStart`)。切り替えは、判定できた録画なら覚え、できなかった録画ではこの録画だけ
     */
    var skipCm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { skipCm = skipCmAtStart(recording.cmReliable, repo.app.settings.skipCm.first()) }
    fun toggleCm() {
        skipCm = !skipCm
        if (recording.cmReliable) scope.launch { repo.app.settings.setSkipCm(skipCm) }
        flash(if (skipCm) "CM 飛ばし 入" else "CM 飛ばし 切")
    }
    /** 開いている帯 (null なら何も出していない) */
    var bar by remember { mutableStateOf<Bar?>(null) }
    /** 番組の詳しいところを開いているか (決定の長押し) */
    var details by remember { mutableStateOf(false) }
    // 戻るは1つで受ける (帯が開いていれば閉じ、無ければ一覧へ。ライブと同じ理由)
    BackHandler(enabled = !leaving) { if (bar != null) bar = null else leave() }
    /** 帯に出す位置と、止まっているか (帯を開いている間だけ取り直す) */
    var at by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    /** 帯で最後にキーを押したとき。5 秒触らなければ帯を閉じる (止めている間も) */
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
        // 止めている間も同じ (止めて開いた帯も、触らなければ閉じて止めた位置の帯だけにする)
        if (bar == null) return@LaunchedEffect
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
        flash((if (resume > 0) "${recording.title}\n続きから (${position(resume)})$pace" else "${recording.title}$pace") + "\n$SEEK_HINT")
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                chaptersOf(tracks).takeIf { it.isNotEmpty() }?.let { chapters = it }
            }

            /*
             * **最後まで来たら、すぐ終わりの位置を預ける。** 終わりの CM を飛ばして終わると、閉じるまで預けないうちは
             * denpa が観終えたと分からない (ブラウザの denpa の `finished()` と同じ)。末尾の位置を渡せば denpa が続きを消し、観終えた印 (`watchedAt`) を付ける
             */
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED || ended) return
                ended = true
                bar = null
                val length = length()
                repo.app.scope.launch {
                    repo.api.saveResume(repo.base, recording.id, length, length)
                    repo.watchNext(recording, (length * 1000).toLong(), (length * 1000).toLong(), finished = true)
                    repo.positionSaved()
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    /*
     * **CM の頭の少し手前 (`CM_LEAD_MS`) で終わりまで飛ぶ。** 入ってから見つけて飛ぶと、見つけるまでの CM のコマが映る。
     * 飛ぶところは ExoPlayer に預ける (`PlayerMessage`。流している位置がそこに来たら、再生の糸で呼ばれる)。
     * 呼ばれたら主の糸の列の先頭に割り込んで飛ぶ (列の後ろに並ぶと、そのぶん遅れて CM のコマが出ることがある)。
     * 飛んでいる間は前の絵 (本編の末尾) が残る。速いほど先に来るので、手前の幅も速さに合わせる。
     * 飛んだ・戻した・送ったら (`seeks`) 預け直す。開いた・飛んだ先が CM の中なら、すぐ飛ぶ (映る前に。止めていれば動かしたら)
     */
    LaunchedEffect(player, chapters, skipCm, seeks, speed) {
        if (!skipCm || chapters.none { it.isCm }) return@LaunchedEffect
        val lead = (CM_LEAD_MS * speed).toLong()
        fun hop(cm: ChapterMark) {
            skipped += cm.startMs
            player.seekTo(cm.endMs)
            flash("CM を飛ばしました")
        }
        val main = Handler(Looper.getMainLooper())
        /** 預け直したあとに、前に預けたぶんが割り込んでこないように */
        var live = true
        val messages = cmHopPoints(chapters, player.currentPosition, skipped, lead).map { (at, cm) ->
            player.createMessage { _, _ -> main.postAtFrontOfQueue { if (live && cm.startMs !in skipped) hop(cm) } }
                .setPosition(at)
                .send()
        }
        try {
            while (true) {
                val cm = cmSkipTarget(chapters, player.currentPosition, skipped, lead) ?: break
                if (player.playWhenReady) {
                    hop(cm)
                    break
                }
                delay(100)
            }
            awaitCancellation()
        } finally {
            live = false
            messages.forEach { it.cancel() }
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(15_000)
            if (player.isPlaying) repo.api.saveResume(repo.base, recording.id, player.currentPosition / 1000.0, length())
        }
    }
    /**
     * 観た位置を預け、途中なら「続きを視聴」に出す (最後まで来ていれば消す)。画面はもう閉じる・裏に回るので、アプリの寿命で送る
     * (画面の scope だと送る前に取り消される)
     */
    fun saveNow() {
        val at = player.currentPosition / 1000.0
        val length = length()
        if (at > 0 && !deleted) repo.app.scope.launch {
            repo.api.saveResume(repo.base, recording.id, at, length)
            repo.watchNext(recording, (at * 1000).toLong(), (length * 1000).toLong(), finished = ended)
            repo.positionSaved()
        }
    }
    // 閉じるときに1回。player を畳むのは rememberPlayer の後始末で、こちらが先に走る
    DisposableEffect(Unit) { onDispose { saveNow() } }
    /*
     * **裏に回ったら止めて、観た位置を預ける** (ホーム・別のアプリ。流しっぱなしにすると裏で進んでしまう)。
     * 戻ったら、流していたなら続きから流す (止めていたなら止めたまま)
     */
    var resumeOnReturn by remember { mutableStateOf(false) }
    OnBackground(
        onStop = {
            resumeOnReturn = player.playWhenReady
            player.pause()
            playing = false
            saveNow()
        },
        onStart = {
            if (resumeOnReturn && !ended) {
                player.play()
                playing = true
            }
        },
    )

    /** 消して一覧へ戻る。一覧からも抜き、隣に合わせる */
    fun deleteNow() = scope.deleteFromPlayer(repo, recording.id, flash, onUnauthorized) { deleted = true; leave() }
    /**
     * 止める・動かす。**止めたら操作の列を開いて「再生」に合わせる** (決定でそのまま動かせる)。
     * 動かしたら帯を閉じて何も出さない (映像を観たいだけなので)。帯を閉じて止めたままなら位置の帯を出す
     */
    fun togglePause() {
        player.playWhenReady = !player.playWhenReady
        playing = player.playWhenReady
        at = player.currentPosition
        bar = if (playing) null else Bar.Actions
    }
    /** 10 秒ずつ戻す・送る (左右とシークバー)。戻して CM を観に行ったなら、そこは飛ばさない */
    fun step(direction: Int): Long {
        val end = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        val to = (player.currentPosition + direction * SEEK_STEP_MS).coerceIn(0, end)
        if (direction < 0) cmRunAt(chapters, to)?.let { skipped += it.startMs }
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
            if (previous.isCm) cmRunAt(chapters, previous.startMs)?.let { skipped += it.startMs }
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

    PlayerFrame(player, overlay, error, active = bar == null && !details && !ended && !leaving, captions = captions, recovery = recovery, above = {
        if (ended) {
            ControlBar(
                "最後まで観ました",
                // 観終えたものは消すことが多いので、最初は削除に合わせる (2回押しなので、1回では消えない)
                listOf("" to listOf(Control("一覧に戻る", icon = R.drawable.ic_back) { leave() }, deleteControl.copy(initial = true))),
            )
        } else if (bar == null && !playing) {
            // 止めている間の位置の帯。キーは映像が受けたまま (左右で 10 秒、決定で動かす、下でシークバー、上で操作の列、長押しで詳しく)
            val total = player.duration.takeIf { it != C.TIME_UNSET }
            ControlBar(
                "一時停止  ${recording.title}\n${position(at)}${total?.let { " / ${position(it)}" } ?: ""}  ・決定で再生  $SEEK_HINT",
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
                        playControl(playing) { togglePause() },
                        // 札は短く (リモコンの「前へ」「次へ」と同じ名前)。帯が1行に収まるように
                        Control("前へ", icon = R.drawable.ic_previous, description = "前のチャプター") { previousChapterNow() },
                        Control("次へ", icon = R.drawable.ic_next, description = "次のチャプター") { nextChapterNow() },
                    ),
                    "" to listOf(
                        speedControl(speed) { scope.stepSpeed(repo, speed) },
                    ),
                    "" to listOf(
                        Control(if (skipCm) "CM 飛ばし 入" else "CM 飛ばし 切", on = skipCm, icon = R.drawable.ic_skip_cm) {
                            toggleCm()
                        },
                    ),
                    "" to tracks.controls(),
                    "" to listOf(deleteControl),
                ),
                header = { actions ->
                    // シークバーが帯のいちばん上の列。そこで上キーを押すと閉じて映像に戻る (ライブのメニューの操作の列と同じ)
                    ProgressLine(at, total ?: 0, chapters, seekFocus, down = actions, onUp = { bar = null }) { direction -> step(direction) }
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
            RecordingCommand.NextSpeed -> { flash(scope.stepSpeed(repo, speed)); true }
            null -> false
        }
    }, onCenter = { press ->
        when (recordingCenter(press)) {
            RecordingCenter.PlayPause -> togglePause()
            RecordingCenter.Details -> details = true
        }
    })

    if (details) PlayerDetailDialog(repo, recording, onDelete = { deleteNow() }, onClose = { details = false }, onUnauthorized = onUnauthorized)
}

/** 録画・追っかけの帯をどこに合わせて開いたか。下キーならシークバー、上キー・Menu なら操作の列 */
internal enum class Bar { SeekBar, Actions }

/** 止める・動かすの札 (録画・追っかけ) */
internal fun playControl(playing: Boolean, onClick: () -> Unit) =
    Control(if (playing) "一時停止" else "再生", on = true, icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play, onClick = onClick)

/** 速さの札 (録画・追っかけ)。1つの札で送る (`stepSpeed`)。列を短くして、押す回数も少なく */
internal fun speedControl(speed: Float, onClick: () -> Unit) =
    Control("速さ ${speedLabel(speed)}", on = speed != 1f, icon = R.drawable.ic_speed, onClick = onClick)

/** 速さを1段送る (1 → 1.25 → 1.5 → 2 → 1。端末ごとに覚える)。知らせる文を返す */
internal fun CoroutineScope.stepSpeed(repo: Repository, speed: Float): String {
    val next = nextSpeed(speed)
    launch { repo.app.settings.setPlaybackSpeed(next) }
    return "速さ ${speedLabel(next)}"
}

/** 録画・追っかけのキーの手引き */
internal const val SEEK_HINT = "下でシークバー・上でメニュー"
