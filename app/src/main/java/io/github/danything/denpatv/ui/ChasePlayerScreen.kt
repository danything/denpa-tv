package io.github.danything.denpatv.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import io.github.danything.denpatv.R
import io.github.danything.denpatv.data.CaptionPaths
import io.github.danything.denpatv.data.CenterPress
import io.github.danything.denpatv.data.Chase
import io.github.danything.denpatv.data.LiveQuality
import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.RecordingCommand
import io.github.danything.denpatv.data.SEEK_STEP_MS
import io.github.danything.denpatv.data.nextSpeed
import io.github.danything.denpatv.data.recordingCommand
import io.github.danything.denpatv.data.resyncAfterSpeedChange
import io.github.danything.denpatv.data.speedLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * **追っかけ再生** (録画中の録画)。denpa の `GET api/recordings/<id>/chase` で、伸びているファイルを流してもらう。
 * ブラウザの追っかけと同じく、続きの位置があればそこから (無ければ頭から)。
 *
 * - 焼き方はライブと同じ画質の設定 (MPEG-2 をハードで解ければ生の TS、そうでなければ H.264)。操作の列で変えられる
 * - 流しっぱなしの1本でシークできないので、**位置を変えるときは頼み直す** (`Chase`)。左右・シークバーで 10 秒ずつ、
 *   続けて押したぶんはまとめて頼む。シークバーの長さは録れた長さ (いま − 放送の始まり)
 * - 最新の近くまで来たら、ライブのようにそのまま観つづける (勝手に飛ばない)。速くして観ていたら等速に戻す。
 *   操作の列の「最新」で最新の少し手前へ
 * - 録り終えて最後まで来たら「最後まで観ました」。焼き上がった録画は、次に開くとふつうのファイルで観る
 * - H.264 / AV1 を焼くのを denpa が断ると (焼く数の上限など)、空の 200 が返る。映る前に終わったらそう出す
 *
 * キーは録画の再生と同じ (左右で 10 秒、決定で止める・動かす、下でシークバー、上・決定の長押しで操作の列)
 */
@OptIn(UnstableApi::class)
@Composable
fun ChasePlayerScreen(repo: Repository, recording: Recording, onLeave: () -> Unit, onUnauthorized: () -> Unit) {
    val chase = recording.chase ?: return Centered("追っかけの口がありません")
    val saved by repo.app.settings.liveQuality.collectAsState(initial = LOADING_QUALITY)
    if (saved == LOADING_QUALITY) return
    val quality = remember(saved) { LiveQuality.choose(saved, repo.app.decoders) }
    val buffering = if (quality == LiveQuality.Raw) Buffering.LowLatency else Buffering.Live
    val clock = remember { TsClock() }
    val (player, error) = rememberPlayer(repo, buffering, onUnauthorized, clock)
    val (overlay, flash) = rememberFlash()
    val scope = rememberCoroutineScope()
    // 一覧の「録画中」は古くなる (録り終える・焼き上がる)。戻ったら読み直してもらう
    DisposableEffect(Unit) { onDispose { repo.recordingsStale = true } }

    fun recorded() = Chase.recordedMs(recording.startAt, System.currentTimeMillis())
    /** 頼んだ位置 (ミリ秒)。再生の位置はこれ + プレーヤーの位置 */
    var from by remember { mutableLongStateOf(Chase.clamp(recording.resumeMs ?: 0L, recorded())) }
    /** 同じ位置で頼み直すとき (止めていて繋がりが切れた) に増やす */
    var attempt by remember { mutableIntStateOf(0) }
    /** 左右で動かしている途中の行き先 (まとめて頼む) */
    var pending by remember { mutableStateOf<Long?>(null) }
    fun position() = pending ?: (from + player.currentPosition)
    // 生の TS の字幕。映像を頼み直したら、字幕もいまの位置から頼み直す
    val captions = rememberRawCaptions(
        repo,
        player,
        clock,
        path = CaptionPaths.recording(recording.id).takeIf { quality == LiveQuality.Raw },
        generation = from to attempt,
        fromMs = { from + player.currentPosition },
        onUnauthorized = onUnauthorized,
    )
    val tracks = rememberTracks(repo, player, flash, captions)

    var bar by remember { mutableStateOf<ChaseBar?>(null) }
    BackHandler(enabled = bar != null) { bar = null }
    /** 一覧へ戻るところ (映像に合いを取り返させない。戻った先の一覧が開いた録画に合わせるので) */
    var leaving by remember { mutableStateOf(false) }
    val leave = { leaving = true; onLeave() }
    BackHandler(enabled = bar == null) { leave() }
    var at by remember { mutableLongStateOf(from) }
    var length by remember { mutableLongStateOf(recorded()) }
    var playing by remember { mutableStateOf(true) }
    var touched by remember { mutableLongStateOf(0L) }
    var ended by remember { mutableStateOf(false) }
    /** 止めたとき。長く止めたら、動かすときに頼み直す (止めている間に繋がりが切れるので) */
    var pausedAt by remember { mutableLongStateOf(0L) }
    val speed by repo.app.settings.playbackSpeed.collectAsState(initial = 1f)
    /** 頼み直してから映りはじめたか (映る前に終わった・壊れたなら、denpa が焼くのを断った空の返事) */
    var started by remember { mutableStateOf(false) }
    /** 焼くのを断られた。頼み直すまで、ふつうのエラーの代わりにそう出す */
    var refused by remember { mutableStateOf(false) }
    /** 最新に追いついて等速に戻したか (速さの設定はそのまま。頼み直したらまた速く) */
    var caughtUp by remember { mutableStateOf(false) }
    LaunchedEffect(player, speed, caughtUp) { player.setPlaybackSpeed(if (caughtUp) 1f else speed) }
    /**
     * 流れている最中に速さを変えたら、今の位置から頼み直す (録画の再生の `resyncAfterSpeedChange` と同じ手当て。
     * 流しっぱなしの1本はシークできないので、飛び直す代わりに頼み直す)。追いついて等速に戻すときはしない
     */
    var lastSpeed by remember { mutableFloatStateOf(speed) }
    LaunchedEffect(speed) {
        if (!caughtUp && resyncAfterSpeedChange(lastSpeed, speed, player.isPlaying)) {
            from = position()
            attempt++
        }
        lastSpeed = speed
    }

    LaunchedEffect(from, quality, attempt) {
        val url = repo.url(Chase.url(chase, quality.codec, from)) ?: return@LaunchedEffect
        caughtUp = false
        started = false
        refused = false
        player.setMediaItem(MediaItem.Builder().uri(url, quality.mime))
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(Unit) {
        val resume = recording.resumeMs ?: 0L
        flash("録画中  ${recording.title}\n" + (if (resume > 0) "続きから (${position(from)})  " else "") + CHASE_HINT)
    }
    // 位置と録れた長さを取り直す。最新に追いついたら等速に
    LaunchedEffect(player) {
        while (true) {
            at = position()
            length = recorded()
            playing = player.playWhenReady
            if (!caughtUp && speed != 1f && player.isPlaying && Chase.atEdge(at, length)) {
                caughtUp = true
                flash("最新に追いつきました (等速で観ます)")
            }
            delay(500)
        }
    }
    LaunchedEffect(bar, touched, playing) {
        if (bar == null || !playing) return@LaunchedEffect
        delay(5_000)
        bar = null
    }
    // 続けて押した左右は、止まってから1度だけ頼み直す
    LaunchedEffect(pending) {
        val target = pending ?: return@LaunchedEffect
        delay(700)
        from = target
        attempt++
        pending = null
    }

    /**
     * 観た位置を預ける。尺は予定の長さ (録画中は分からない。最新の近くで「観終えた」と消されないように)。
     * 閉じた・観終えたとき (`stopped`) は「続きを視聴」も直す
     */
    fun save(atMs: Long, finished: Boolean = false, stopped: Boolean = finished) {
        val seconds = atMs / 1000.0
        val scheduled = recording.endAt?.let { (it - recording.startAt) / 1000.0 } ?: 0.0
        repo.app.scope.launch {
            repo.api.saveResume(repo.base, recording.id, seconds, if (finished) seconds else scheduled)
            if (stopped) repo.watchNext(recording, atMs, (scheduled * 1000).toLong(), finished)
        }
    }
    LaunchedEffect(player) {
        while (true) {
            delay(15_000)
            if (player.isPlaying) save(position())
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (!ended && position() > 0) save(position(), stopped = true) }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) started = true
            }

            // 録り終えて最後まで読んだら、denpa が閉じる
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED || ended) return
                // 映る前に終わった: H.264 / AV1 を焼くのを断られた (空の 200)。録り終えたのではない
                if (!started && quality != LiveQuality.Raw) {
                    refused = true
                    return
                }
                ended = true
                bar = null
                save(position(), finished = true)
            }

            override fun onPlayerError(e: PlaybackException) {
                val code = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                when {
                    code == 404 -> flash("録り終えて焼き上がったようです。一覧に戻って開き直してください")
                    // 空の返事は、端末によっては形が分からないと言われる
                    !started && quality != LiveQuality.Raw && code == null -> refused = true
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    fun step(direction: Int) {
        val target = Chase.clamp(position() + direction * SEEK_STEP_MS, recorded())
        // もう最新 (か頭) で動けない。頼み直すと途切れるだけなので何もしない
        if (kotlin.math.abs(target - position()) < 1_000) return flash(if (direction > 0) "最新です" else "頭です")
        pending = target
        at = target
        flash("${position(target)} / ${position(recorded())}")
    }
    fun toEdge() {
        pending = null
        from = Chase.clamp(recorded(), recorded())
        attempt++
        flash("最新へ")
    }
    fun togglePause() {
        if (player.playWhenReady) {
            player.playWhenReady = false
            pausedAt = System.nanoTime()
            flash("一時停止  ${position(position())}\n$CHASE_HINT")
        } else {
            // 長く止めていたら繋がりが切れているかもしれないので、止めた位置から頼み直す
            if (System.nanoTime() - pausedAt > 10_000_000_000L) {
                from = position()
                attempt++
            }
            player.playWhenReady = true
            flash("再生")
        }
        playing = player.playWhenReady
    }
    fun open(which: ChaseBar) {
        at = position()
        length = recorded()
        playing = player.playWhenReady
        bar = which
    }
    val seekFocus = remember { FocusRequester() }

    PlayerFrame(player, overlay, if (refused) REFUSED else error, active = bar == null && !ended && !leaving, captions = captions, above = {
        if (ended) {
            ControlBar(
                "最後まで観ました (録り終えました)",
                listOf("" to listOf(Control("一覧に戻る", on = true, icon = R.drawable.ic_back) { leave() })),
            )
        } else bar?.let { which ->
            LaunchedEffect(which) { if (which == ChaseBar.SeekBar) runCatching { seekFocus.requestFocus() } }
            ControlBar(
                "録画中  ${recording.title}\n${position(at)} / ${position(length)} (録れたところまで)",
                listOf(
                    "" to listOfNotNull(
                        Control(if (playing) "一時停止" else "再生", on = true, icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play) { togglePause() },
                        if (Chase.atEdge(at, length)) null else Control("最新", icon = R.drawable.ic_edge) { toEdge() },
                        Control("速さ ${speedLabel(speed)}", on = speed != 1f, icon = R.drawable.ic_speed) {
                            scope.launch { repo.app.settings.setPlaybackSpeed(nextSpeed(speed)) }
                        },
                    ),
                    "画質 (コーデック)" to LiveQuality.available(repo.app.decoders).map { choice ->
                        Control(choice.label, on = choice == quality) {
                            if (choice == quality) return@Control
                            from = position()
                            scope.launch { repo.app.settings.setLiveQuality(choice) }
                        }
                    },
                    "" to tracks.controls(),
                ),
                header = { actions ->
                    ProgressLine(at, length, focus = seekFocus, down = actions) { direction -> step(direction) }
                },
                focusActions = which == ChaseBar.Actions,
                onActivity = { touched = System.nanoTime() },
            )
        }
    }, onKey = { event ->
        when (recordingCommand(event.nativeKeyEvent.keyCode)) {
            RecordingCommand.Back -> { step(-1); true }
            RecordingCommand.Forward -> { step(1); true }
            RecordingCommand.SeekBar -> { open(ChaseBar.SeekBar); true }
            RecordingCommand.Actions -> { open(ChaseBar.Actions); true }
            RecordingCommand.PlayPause -> { togglePause(); true }
            RecordingCommand.NextSpeed -> {
                val next = nextSpeed(speed)
                scope.launch { repo.app.settings.setPlaybackSpeed(next) }
                flash("速さ ${speedLabel(next)}")
                true
            }
            // 追っかけの流れにはチャプターが無い
            RecordingCommand.NextChapter, RecordingCommand.PreviousChapter -> { flash("チャプターがありません"); true }
            null -> false
        }
    }, onCenter = { press ->
        when (press) {
            CenterPress.Action.Short -> togglePause()
            CenterPress.Action.Long -> open(ChaseBar.Actions)
        }
    })
}

private enum class ChaseBar { SeekBar, Actions }

private const val REFUSED = "denpa が焼くのを断りました (混んでいるかも)。少し待つか、画質を替えてください"

private const val CHASE_HINT = "下でシークバー・上でメニュー"

private const val LOADING_QUALITY = "\u0000loading"
