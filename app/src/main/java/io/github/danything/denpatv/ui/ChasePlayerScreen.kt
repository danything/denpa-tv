package io.github.danything.denpatv.ui

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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import io.github.danything.denpatv.R
import io.github.danything.denpatv.data.CaptionPaths
import io.github.danything.denpatv.data.Chase
import io.github.danything.denpatv.data.ChaseEnd
import io.github.danything.denpatv.data.LiveQuality
import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.RecordingCommand
import io.github.danything.denpatv.data.SEEK_STEP_MS
import io.github.danything.denpatv.data.Unauthorized
import io.github.danything.denpatv.data.chaseEnd
import io.github.danything.denpatv.data.recordingCommand
import io.github.danything.denpatv.data.resyncAfterSpeedChange
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
 * - **切れたら (エラー・録り終える前に流れが閉じた・10 秒進まない) 居た場所から頼み直す** (`Recovery`。ライブと同じ決まり)
 * - H.264 / AV1 を denpa が焼けないと (ffmpeg が降りた・録画を読めない)、空の 200 が返る。映る前に終わった・空だったらそう出す
 *
 * キーは録画の再生と同じ (左右で 10 秒、決定で止める・動かす、下でシークバー、上・Menu で操作の列、決定の長押しで詳しく)
 */
@OptIn(UnstableApi::class)
@Composable
fun ChasePlayerScreen(repo: Repository, recording: Recording, onLeave: () -> Unit, onUnauthorized: () -> Unit) {
    val chase = recording.chase ?: return Centered("追っかけの口がありません")
    val quality = rememberLiveQuality(repo) ?: return
    val buffering = quality.buffering
    val clock = remember { TsClock() }
    fun recorded() = Chase.recordedMs(recording.startAt, System.currentTimeMillis())
    /** 頼んだ位置 (ミリ秒)。再生の位置はこれ + プレーヤーの位置 */
    var from by remember { mutableLongStateOf(Chase.clamp(recording.resumeMs ?: 0L, recorded())) }
    /** 同じ位置で頼み直すとき (止めていて繋がりが切れた・繋ぎ直す) に増やす */
    var attempt by remember { mutableIntStateOf(0) }
    /** 左右で動かしている途中の行き先 (まとめて頼む) */
    var pending by remember { mutableStateOf<Long?>(null) }
    /*
     * **切れた・止まったら、居た場所から頼み直す** (`Recovery`。ブラウザの追っかけと同じ)。流れが終わったときは、録り終えたのか
     * 切れたのかを denpa に聞いてから (下の `onPlaybackStateChanged`)
     */
    val (player, error, dualMono, recovery) = rememberPlayer(
        repo,
        buffering,
        onUnauthorized,
        clock,
        ReconnectPlan("chase 録画 ${recording.id}", stream = true) { p ->
            from = pending ?: (from + p.currentPosition)
            pending = null
            attempt++
        },
    )
    val (overlay, flash) = rememberFlash()
    val scope = rememberCoroutineScope()
    /** 焼くのを断られた。頼み直すまで、ふつうのエラーの代わりにそう出す */
    var refused by remember { mutableStateOf(false) }
    // 一覧の「録画中」は古くなる (録り終える・焼き上がる)。戻ったら読み直してもらう
    DisposableEffect(Unit) { onDispose { repo.recordingsStale = true } }

    fun position() = pending ?: (from + player.currentPosition)
    /**
     * 画質を替えるときの位置と行き先。**画質が替わってから**、ここから頼み直す — 押したときに `from` を替えると、新しい画質が届く前に
     * 古い画質で一度頼み直してしまう (denpa に焼き直しを2回させる)。MPEG-2 との行き来ではプレーヤーごと作り直すので、替える前に覚えておく
     */
    val switchedAt = remember { mutableStateOf<Pair<Long, LiveQuality>?>(null) }
    val codec = rememberCodecSwitching(repo, player, quality, if (refused) "denpa が焼くのを断りました" else error, flash) { target ->
        switchedAt.value = position() to target
    }
    /** 裏に回ったときの位置 (`OnBackground`)。戻ったらここから頼み直す。裏に回っていなければ null */
    var stoppedAt by remember { mutableStateOf<Long?>(null) }
    // 生の TS の字幕。映像を頼み直したら、字幕もいまの位置から頼み直す
    val captions = rememberRawCaptions(
        repo,
        player,
        clock,
        // 裏に回っている間は字幕の流れも閉じる
        path = CaptionPaths.recording(recording.id).takeIf { quality == LiveQuality.Raw && stoppedAt == null },
        generation = from to attempt,
        fromMs = { from + player.currentPosition },
        onUnauthorized = onUnauthorized,
    )
    // デュアルモノの主・副は、生の TS のときだけ配り直す (焼いた追っかけは denpa が選んだ1つだけを焼く。下の `baked`)
    val tracks = rememberTracks(repo, player, flash, captions, dualMono, denpaAudios = recording.audios.takeIf { quality == LiveQuality.Raw }.orEmpty())
    // 焼いた追っかけの音声は denpa に頼んで選ぶ (`?audio=<id>`)。替えたら、いまの位置から頼み直す
    val baked = rememberBakedAudio(repo, recording.audios.takeIf { quality != LiveQuality.Raw }.orEmpty(), key = recording.id) { label ->
        from = position()
        flash(label)
    }

    /** 詳しくから消した (閉じるときに観た位置を預けない。「続きを視聴」に戻さない) */
    var deleted by remember { mutableStateOf(false) }
    var at by remember { mutableLongStateOf(from) }
    var length by remember { mutableLongStateOf(recorded()) }
    var playing by remember { mutableStateOf(true) }
    val ui = rememberPlayerBar(onLeave, playing)
    var bar by ui::bar
    var details by ui::details
    var ended by remember { mutableStateOf(false) }
    /** 止めたとき。長く止めたら、動かすときに頼み直す (止めている間に繋がりが切れるので) */
    var pausedAt by remember { mutableLongStateOf(0L) }
    val speed by repo.app.settings.playbackSpeed.collectAsState(initial = 1f)
    /** 頼み直してから映りはじめたか (映る前に終わった・壊れたなら、denpa が焼くのを断った空の返事) */
    var started by remember { mutableStateOf(false) }
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

    LaunchedEffect(from, quality, attempt, baked.ready, baked.audio?.id, codec.chosen) {
        if (!baked.ready) return@LaunchedEffect
        // 選んだ画質が覚えている画質に届くまでは頼まない (ライブと同じ)
        if (codec.chosen != quality) return@LaunchedEffect
        // 画質を替えた頼みでだけ使う。替えるたびに覚え直し、使ったら消すので、あとの頼み直し (左右・繋ぎ直し) の位置は巻き戻さない
        switchedAt.value?.takeIf { it.second == quality }?.let { (at, _) ->
            switchedAt.value = null
            // 位置を替えると、この頼みをやり直す (そちらで頼む)
            if (at != from) {
                from = at
                return@LaunchedEffect
            }
        }
        val url = repo.url(Chase.url(chase, quality.codec, from, baked.audio)) ?: return@LaunchedEffect
        caughtUp = false
        started = false
        refused = false
        recovery.requested()
        player.setMediaItem(MediaItem.Builder().uri(url, quality.mime))
        player.prepare()
        player.playWhenReady = true
        codec.requested(quality)
    }
    LaunchedEffect(Unit) {
        val resume = recording.resumeMs ?: 0L
        flash(
            buildAnnotatedString {
                append("録画中  ")
                appendBroadcast(recording.title)
                append("\n" + (if (resume > 0) "続きから (${position(from)})  " else "") + SEEK_HINT)
            },
        )
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
            if (stopped) {
                repo.watchNext(recording, atMs, (scheduled * 1000).toLong(), finished)
                repo.positionSaved()
            }
        }
    }
    LaunchedEffect(player) {
        while (true) {
            delay(15_000)
            if (player.isPlaying) save(position())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // 裏に回ったまま閉じたときは、止めた位置 (止めたあとのプレーヤーの位置には頼らない)
            val at = stoppedAt ?: position()
            if (!ended && !deleted && at > 0) save(at, stopped = true)
        }
    }
    /*
     * **裏に回ったら止めて denpa から降り、観た位置を預ける** (ホーム・別のアプリ。焼いている追っかけなら denpa の焼く手も空く)。
     * 流しっぱなしの1本なので、戻ったら止めた位置から頼み直して続ける
     */
    OnBackground(
        onStop = {
            if (ended) return@OnBackground
            val at = position()
            pending = null
            stoppedAt = at
            if (at > 0) save(at, stopped = true)
            player.stop()
        },
        onStart = {
            val at = stoppedAt ?: return@OnBackground
            stoppedAt = null
            from = at
            attempt++
        },
    )
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) started = true
            }

            /*
             * 録り終えて最後まで読んだら、denpa が閉じる。**それ以外で閉じたのは切れた** (denpa の入れ替え・焼き直し) ので、
             * まだ録っているか・尻まで観たかを denpa に聞いて、切れたのなら居た場所から頼み直す (`chaseEnd`)
             */
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED || ended) return
                // 映る前に終わった: H.264 / AV1 を焼くのを断られた (空の 200)。録り終えたのではない
                if (!started && quality != LiveQuality.Raw) {
                    refused = true
                    return
                }
                val at = position()
                val pictured = recovery.pictured
                val played = recovery.playedSeconds()
                val asked = from to attempt
                scope.launch {
                    val (still, duration) = try {
                        val fresh = repo.api.recordings(repo.base).firstOrNull { it.id == recording.id }
                        (fresh?.recording ?: false) to fresh?.durationMs
                    } catch (_: Unauthorized) {
                        return@launch onUnauthorized()
                    } catch (_: Exception) {
                        // 聞けない: denpa が入れ替わっている最中
                        null to null
                    }
                    // 聞いている間に位置を変えた・閉じた
                    if (ended || asked != (from to attempt)) return@launch
                    val end = chaseEnd(still, at, duration, pictured)
                    if (end == ChaseEnd.Lost && recovery.retry("ended 流れが終わった (録画中=$still 位置 ${at / 1000} 秒 / ${duration?.div(1000)} 秒、映して $played 秒)")) {
                        return@launch
                    }
                    ended = true
                    bar = null
                    save(at, finished = true)
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                val code = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                when {
                    code == 404 -> flash("録り終えて焼き上がったようです。一覧に戻って開き直してください")
                    // 焼くのを断られた (空の返事)。繋ぎ直さずにそう出す
                    Chase.refused(started, baked = quality != LiveQuality.Raw, httpStatus = code, errorCode = e.errorCode) -> {
                        refused = true
                        recovery.cancel()
                    }
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
    /** 止める・動かす。止めたら操作の列を開いて「再生」に合わせ、動かしたら閉じて何も出さない (録画の再生と同じ) */
    fun togglePause() {
        if (player.playWhenReady) {
            player.playWhenReady = false
            pausedAt = System.nanoTime()
            at = position()
            length = recorded()
            bar = Bar.Actions
        } else {
            // 長く止めていたら繋がりが切れているかもしれないので、止めた位置から頼み直す
            if (System.nanoTime() - pausedAt > 10_000_000_000L) {
                from = position()
                attempt++
            }
            player.playWhenReady = true
            bar = null
        }
        playing = player.playWhenReady
    }
    fun open(which: Bar) {
        at = position()
        length = recorded()
        playing = player.playWhenReady
        bar = which
    }
    val seekFocus = remember { FocusRequester() }

    PlayerFrame(player, overlay, if (refused) REFUSED else error, active = bar == null && !details && !ended && !ui.leaving, captions = captions, recovery = recovery, above = {
        if (ended) {
            ControlBar(
                "最後まで観ました (録り終えました)",
                listOf("" to listOf(Control("一覧に戻る", on = true, icon = R.drawable.ic_back) { ui.leave() })),
            )
        } else bar?.let { which ->
            LaunchedEffect(which) { if (which == Bar.SeekBar) runCatching { seekFocus.requestFocus() } }
            ControlBar(
                buildAnnotatedString {
                    append("録画中  ")
                    appendBroadcast(recording.title)
                    append("\n${position(at)} / ${position(length)} (録れたところまで)  ${codec.heading}")
                },
                listOf(
                    "" to listOfNotNull(
                        playControl(playing) { togglePause() },
                        if (Chase.atEdge(at, length)) null else Control("最新", icon = R.drawable.ic_edge) { toEdge() },
                        speedControl(speed) { scope.stepSpeed(repo, speed) },
                    ),
                    "画質" to LiveQuality.available(repo.app.decoders).map { choice ->
                        // 選んだらすぐ入れて、居た場所から頼み直す (映るまでは前の絵のまま「… に切り替え中」)
                        Control(choice.label, on = choice == codec.chosen) {
                            codec.choose(choice)
                        }
                    },
                    "" to tracks.controls() + baked.controls(),
                ),
                header = { actions ->
                    // シークバーが帯のいちばん上の列。そこで上キーを押すと閉じて映像に戻る (録画と同じ)
                    ProgressLine(at, length, focus = seekFocus, down = actions, onUp = { bar = null }) { direction -> step(direction) }
                },
                focusActions = which == Bar.Actions,
                onActivity = { ui.touched = System.nanoTime() },
            )
        }
    }, onKey = { event ->
        when (recordingCommand(event.nativeKeyEvent.keyCode)) {
            RecordingCommand.Back -> { step(-1); true }
            RecordingCommand.Forward -> { step(1); true }
            RecordingCommand.SeekBar -> { open(Bar.SeekBar); true }
            RecordingCommand.Actions -> { open(Bar.Actions); true }
            RecordingCommand.PlayPause -> { togglePause(); true }
            RecordingCommand.NextSpeed -> { flash(scope.stepSpeed(repo, speed)); true }
            // 追っかけの流れにはチャプターが無い
            RecordingCommand.NextChapter, RecordingCommand.PreviousChapter -> { flash("チャプターがありません"); true }
            null -> false
        }
    }, onCenter = { press -> ui.center(press) { togglePause() } })

    /** 消して一覧へ戻る (録画の再生と同じ。録っている間は denpa が断る)。消したら観た位置は預けない */
    fun deleteNow() = scope.deleteFromPlayer(repo, recording.id, flash, onUnauthorized) { deleted = true; ui.leave() }

    if (details) PlayerDetailDialog(repo, recording, onDelete = { deleteNow() }, onClose = { details = false }, onUnauthorized = onUnauthorized)
}

private const val REFUSED = "denpa が映像を送らずに閉じました (焼けなかったかも)。少し待つか、画質を替えてください"
