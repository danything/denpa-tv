package io.github.danything.denpatv.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.buildAnnotatedString
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.danything.denpatv.R
import io.github.danything.denpatv.data.CaptionPaths
import io.github.danything.denpatv.data.CenterPress
import io.github.danything.denpatv.data.RecordingCenter
import io.github.danything.denpatv.data.recordingCenter
import io.github.danything.denpatv.data.ChapterMark
import io.github.danything.denpatv.data.PlayChoice
import io.github.danything.denpatv.data.PlaybackInfo
import io.github.danything.denpatv.data.PlaybackLookup
import io.github.danything.denpatv.data.PlaybackPaths
import io.github.danything.denpatv.data.Reconnect
import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.StreamClock
import io.github.danything.denpatv.data.Unauthorized
import io.github.danything.denpatv.data.pickPlayback
import io.github.danything.denpatv.data.recordingCommand
import io.github.danything.denpatv.data.SEEK_STEP_MS
import io.github.danything.denpatv.data.skipCmAtStart
import io.github.danything.denpatv.data.streamAudios
import io.github.danything.denpatv.data.RecordingCommand
import io.github.danything.denpatv.data.resyncAfterSpeedChange
import io.github.danything.denpatv.data.nextSpeed
import io.github.danything.denpatv.data.speedDescription
import io.github.danything.denpatv.data.speedLabel
import io.github.danything.denpatv.data.speedNumber
import io.github.danything.denpatv.data.CM_LEAD_MS
import io.github.danything.denpatv.data.cmHopPoints
import io.github.danything.denpatv.data.cmRunAt
import io.github.danything.denpatv.data.cmSkipTarget
import io.github.danything.denpatv.data.nextChapter
import io.github.danything.denpatv.data.previousChapter
import io.github.danything.denpatv.data.resumedInCm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 録画を観る。何で観るかは denpa に聞いて (`GET api/recordings/<id>/playback`)、端末がデコードできるものから選ぶ (`pickPlayback`):
 * エンコード済みのファイルがデコードできればそのまま開き、無ければ denpa に流してもらう (`stream`。元の TS をそのまま、または変換)。
 * **続きの位置があればそこから** (denpa の `resumeMs`)。
 *
 * - 左右で 10 秒戻す・送る、決定で止める・動かす (キーの割り当ては data/Remote.kt と docs/controls.md)
 * - **下でシークバー** (左右で 10 秒ずつ。CM は色を変えて出す)、下でその下の操作の列へ
 * - **上 (か Menu) で操作の列**。**決定の長押し (と情報キー) で番組の詳しいところ** (一覧のカードの長押しと同じ。削除もそこから)。操作バーの中は上下で操作の列とシークバーを行き来し、**シークバー (いちばん上) から上で閉じる**。操作の列: 再生 / 一時停止、前へ・次へ (チャプター)、**速さ** (押すたびに 1 / 1.25 / 1.5 / 2 倍)、
 *   **CM 飛ばし** (既定でオン。ロゴで CM を判定できなかった録画はオフで始まる)、**字幕**・**音声** (あれば)、**削除** (2回押し)。ブラウザの denpa の再生と同じく、観ながら変えて端末ごとに覚える。
 *   5 秒触らなければ閉じる (止めている間も)。戻るでも閉じる。緑のボタンは速さを1段送る
 * - リモコンの次へ・前へでチャプター送り
 * - CM 飛ばしがオンなら CM の頭の少し手前で終わりまで飛ぶ (CM のコマは映さない)。区切りはチャプター (`CM` / `本編`)。ファイルは動画に入っているもの、
 *   denpa に流してもらうときは `api/recordings/<id>/chapters`
 *
 * 位置・尺・チャプター・続きの位置は、どれも録画の時刻 (`playback` の物差し) で数える (`RecordingTimeline`)。
 * 観た位置は denpa に預ける (15 秒ごとと、閉じるとき)。ブラウザで続きから観られる
 */
@Composable
fun RecordingPlayerScreen(repo: Repository, recording: Recording, onLeave: () -> Unit, onUnauthorized: () -> Unit) {
    var lookup by remember { mutableStateOf<PlaybackLookup?>(null) }
    LaunchedEffect(recording.id) {
        lookup = try {
            repo.api.playback(repo.base, recording.id)
        } catch (_: Unauthorized) {
            return@LaunchedEffect onUnauthorized()
        }
    }
    when (val found = lookup) {
        null -> Centered("読み込んでいます")
        PlaybackLookup.Missing -> Centered("この録画を開けません (ファイルが無いか、denpa が古いため)")
        is PlaybackLookup.Failed -> Centered("再生できません: ${found.message}")
        is PlaybackLookup.Found -> {
            val choice = remember(found.info) { pickPlayback(found.info, repo.app.decoders) }
            if (choice == null) Centered("この端末で再生できる形式がありません")
            else RecordingPlayback(repo, recording, found.info, choice, onLeave, onUnauthorized)
        }
    }
}

/**
 * **録画の位置を、録画の時刻 (`playback` の物差し) で数える。**
 *
 * - ファイル (`stream` が null) は、プレーヤーがシークする。位置もプレーヤーのまま
 * - denpa に流してもらう (`stream`) ときは流しっぱなしの1本なので、**位置を変えるたびに `from` を変えてリクエストし直す** (`request`)。
 *   ストリームはリクエストした位置ちょうどからは始まらない (変換は 1 秒手前から) ので、位置は Extractor が頭を 0 に寄せた幅から録画の時刻に戻す
 *   (`settle`・`StreamClock`)。戻せるまではリクエストした位置から数える。左右を続けて押したぶんは、まとめてリクエストする (`pending`)
 */
@Stable
internal class RecordingTimeline(
    private val clock: TsClock,
    val stream: PlayChoice.Stream?,
    startMs: Long,
    private val lengthMs: Long,
) {
    /** 位置を読むプレーヤー (繋ぎ直しの決まりがこの `request` を使うので、プレーヤーより先に作って後から渡す) */
    lateinit var player: ExoPlayer

    /** リクエストした位置 (ストリーム) */
    var from by mutableLongStateOf(clamp(startMs))
        private set

    /** リクエストした回数 (ストリーム)。増えたらリクエストし直す */
    var requests by mutableIntStateOf(0)
        private set

    /** プレーヤーの位置 0 が、録画の何ミリ秒か。ファイルは 0、ストリームは読みはじめるまで null */
    var baseMs by mutableStateOf<Long?>(if (stream == null) 0L else null)
        private set

    /** 左右で動かしている途中の行き先 (ストリーム。止まってからまとめてリクエストする) */
    var pending by mutableStateOf<Long?>(null)

    /** いまの位置 (録画の時刻) */
    fun position(): Long = pending ?: if (stream == null) player.currentPosition else baseMs?.let { it + player.currentPosition } ?: from

    /** 尺 (ミリ秒)。ファイルはプレーヤーが読めばそれ、ストリームは `playback` の尺 */
    fun durationMs(): Long =
        if (stream == null) player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: lengthMs else lengthMs

    /** `at` へ動く。ファイルはシーク、ストリームはリクエストし直す */
    fun seekTo(at: Long) {
        pending = null
        if (stream == null) player.seekTo(at) else request(at)
    }

    /** ストリームを `at` からリクエストし直す (位置を変えた・音声を替えた・繋ぎ直す・裏から戻った) */
    fun request(at: Long) {
        pending = null
        from = clamp(at)
        baseMs = null
        // 新しいストリームを読みはじめるまで、前のストリームの幅で数えない
        clock.reset()
        requests++
    }

    /** ストリームを読みはじめていれば、プレーヤーの 0 が録画の何ミリ秒かを決める。決まれば true */
    fun settle(): Boolean {
        if (stream == null || baseMs != null) return true
        val offset = clock.offsetUs() ?: return false
        baseMs = if (stream.codec == "mpeg2") clock.origin?.let { StreamClock.tsBaseMs(offset, it) } ?: from else StreamClock.mp4BaseMs(offset)
        return true
    }

    /**
     * ストリームで、`at` が尻 (もう流すものが無いところ) か。そこをリクエストしても denpa は何も流せないので、リクエストせずに最後まで観たことにする
     * (終わりの CM を飛ぶ・尻まで送る)。尺が分からなければ false
     */
    fun reachesEnd(at: Long): Boolean = stream != null && lengthMs > 0 && at >= lengthMs - END_TOLERANCE_MS

    /** リクエストする位置にそろえる (0 から尺まで。尺が分からなければ上は抑えない) */
    private fun clamp(at: Long): Long = if (lengthMs > 0) at.coerceIn(0, lengthMs) else at.coerceAtLeast(0)

    /** 録画の時刻 `at` が、いまのストリームのプレーヤーの位置でいつか (CM の頭で飛ぶ見張りを置くところ)。まだ決まっていなければ null */
    fun playerPosition(at: Long): Long? = baseMs?.let { at - it }
}

@OptIn(UnstableApi::class)
@Composable
private fun RecordingPlayback(
    repo: Repository,
    recording: Recording,
    info: PlaybackInfo,
    choice: PlayChoice,
    onLeave: () -> Unit,
    onUnauthorized: () -> Unit,
) {
    val stream = choice as? PlayChoice.Stream
    /** 元の TS を流してもらう (字幕は字幕のエンドポイントから、音声は全部入っているので端末で選ぶ) */
    val raw = stream?.codec == "mpeg2"
    /** denpa が変換した fMP4 (音声は denpa にリクエストして選ぶ) */
    val baked = stream != null && !raw
    val lengthMs = (info.duration * 1000).toLong()
    val clock = remember { TsClock(mp4 = stream != null) }
    val timeline = remember { RecordingTimeline(clock, stream, recording.resumeMs ?: 0L, lengthMs) }
    /*
     * ファイルは、回線が切れたときだけいまの位置から読み直す (`prepare` し直すと、Media3 は止まった位置から続ける)。
     * Media3 が中で読み直したうえでの失敗なので、何度か (`Reconnect.FEW`) まで。中身が読めない・デコードできないのは繋ぎ直しても同じなので出して止める。
     * 流してもらうときは追っかけと同じく、切れた・止まったらいた位置からリクエストし直す
     */
    val (player, error, dualMono, recovery) = rememberPlayer(
        repo,
        Buffering.Recording,
        onUnauthorized,
        clock,
        if (stream == null) {
            ReconnectPlan("recording 録画 ${recording.id}", stream = false) { it.prepare() }
        } else {
            ReconnectPlan("recording 録画 ${recording.id} (${stream.codec} を流す)", stream = true) {
                timeline.request(timeline.position())
            }
        },
    )
    timeline.player = player
    val flasher = rememberFlash()
    val (overlay, flash) = flasher
    val scope = rememberCoroutineScope()
    /** 飛んだ回数。元の TS の字幕を、飛んだ先からリクエストし直す。CM 飛ばしの見張りも置き直す */
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
    /** 動かした回数。ファイルはシークの回数、ストリームはリクエストし直した回数 (最初の1回は数えない) */
    val moves = if (stream == null) seeks else timeline.requests
    // 字幕は denpa から受け取る。元の TS は字幕のエンドポイント、そのほかは文字の配置まるごと (`captions.json`。録画の時刻で引く)
    val captions = if (raw) {
        rememberRawCaptions(
            repo,
            player,
            clock,
            path = CaptionPaths.recording(recording.id),
            generation = moves,
            fromMs = { timeline.position() },
            onUnauthorized = onUnauthorized,
        )
    } else {
        rememberCaptionPages(repo, player, CaptionPaths.recordingText(recording.id), onUnauthorized) { timeline.position() }
    }
    // デュアルモノの主・副は、元の TS のときだけ配り直す (エンコード済みの録画は denpa が主・副の2本に割ってある。変換は denpa が選んだ1つだけ)
    val tracks = rememberTracks(repo, player, flash, captions, dualMono, denpaAudios = recording.audios.takeIf { raw }.orEmpty())
    // 変換したストリームの音声は denpa にリクエストして選ぶ (`?audio=<id>`)。替えたら、いまの位置からリクエストし直す
    val bakedAudio = rememberBakedAudio(repo, if (baked) streamAudios(info, recording.audios) else emptyList(), key = recording.id) { label ->
        timeline.request(timeline.position())
        flash(label)
    }
    /**
     * CM 飛ばし。観はじめはブラウザと同じく、覚えている設定に従うが、ロゴで CM を判定できなかった録画は切って始める
     * (`skipCmAtStart`)。切り替えは、判定できた録画なら覚え、できなかった録画ではこの録画だけ
     */
    var skipCm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { skipCm = skipCmAtStart(recording.cmReliable, repo.app.settings.skipCm.first()) }
    fun toggleCm() {
        skipCm = !skipCm
        if (recording.cmReliable) scope.launch { repo.app.settings.setSkipCm(skipCm) }
        flash(if (skipCm) "CM 飛ばし オン" else "CM 飛ばし オフ")
    }
    /** 操作バーに出す位置と、止まっているか (操作バーを開いている間だけ取り直す) */
    var at by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    val ui = rememberPlayerBar(onLeave, playing)
    var bar by ui::bar
    var details by ui::details
    // 操作バーを開いている間と止めている間 (止めると位置のバーを出す) は、位置を取り直す
    LaunchedEffect(bar, playing) {
        while (bar != null || !playing) {
            at = timeline.position()
            playing = player.playWhenReady
            delay(500)
        }
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
    /** 最後まで観た (終わりの操作バーを出す)。勝手に一覧へは戻らない */
    var ended by remember { mutableStateOf(false) }
    /** 消した (閉じるときに観た位置を預けない) */
    var deleted by remember { mutableStateOf(false) }
    /** 止めたとき。流してもらっているときに長く止めたら、動かすときにリクエストし直す (止めている間に繋がりが切れるので) */
    var pausedAt by remember { mutableLongStateOf(0L) }
    val delete = rememberTwoPress()
    val speed by repo.app.settings.playbackSpeed.collectAsState(initial = 1f)
    // 速さは録画だけ (ライブは追いつくための 1.05 倍を自分で回す)。CM 飛ばしと観た位置は再生位置で見るので速さに関わらない
    // 観ている途中で変えたら、今の位置に飛び直して音と映像を新しい速さで流し直す (`resyncAfterSpeedChange`。ストリームはリクエストし直す)
    LaunchedEffect(player, speed) {
        val from = player.playbackParameters.speed
        player.setPlaybackSpeed(speed)
        val flowing = started && player.playbackState != Player.STATE_ENDED
        if (resyncAfterSpeedChange(from, speed, flowing)) timeline.seekTo(timeline.position())
    }
    fun length() = timeline.durationMs() / 1000.0
    var chapters by remember { mutableStateOf(emptyList<ChapterMark>()) }
    val skipped = remember { mutableSetOf<Long>() }
    /**
     * CM を飛ぶときに、CM の終わりからどれだけ先をリクエストするか。元の TS から流してもらう (元の TS をそのまま・元の TS から変換) と、
     * denpa はリクエストした位置の 1 秒手前の映像から流す (denpa の docs/api.md「録画を流す」) ので、そのぶん先をリクエストして CM の尻を映さない
     */
    val hopLead = if (raw || (baked && info.axis == "ts")) TS_STREAM_LEAD_MS else 0L

    // 観はじめの通知 (番組名・続きから・速さ)
    LaunchedEffect(Unit) {
        val resume = recording.resumeMs ?: 0L
        val pace = repo.app.settings.playbackSpeed.first().takeIf { it != 1f }?.let { "  速さ ${speedLabel(it)}" } ?: ""
        flash(
            buildAnnotatedString {
                appendBroadcast(recording.title)
                append(if (resume > 0) "\n続きから (${position(resume)})$pace" else pace)
                append("\n$SEEK_HINT")
            },
        )
    }
    if (stream == null) {
        LaunchedEffect(choice) {
            val file = choice as PlayChoice.File
            val url = repo.url(PlaybackPaths.file(recording.id, file.source)) ?: return@LaunchedEffect
            player.load(url, MimeTypes.VIDEO_MATROSKA, recording.resumeMs ?: 0L)
        }
    } else {
        /** 裏から戻ったときに、止めたままリクエストし直すか (止めていたなら止めたまま) */
        var playOnLoad by remember { mutableStateOf(true) }
        // リクエストし直すたび (位置を変えた・音声を替えた・繋ぎ直す)。変換したストリームは、覚えている音声の側を読み終えてから
        // 元の TS は音声をリクエストしないので、覚えている側を読み終えてもリクエストし直さない
        val audioKey = if (baked) bakedAudio.audio?.id to bakedAudio.ready else null
        LaunchedEffect(timeline.requests, audioKey) {
            if (baked && !bakedAudio.ready) return@LaunchedEffect
            val url = repo.url(
                PlaybackPaths.stream(recording.id, stream.codec, timeline.from, bakedAudio.audio.takeIf { baked }),
            ) ?: return@LaunchedEffect
            recovery.requested()
            player.load(url, stream.mime)
            if (!playOnLoad) {
                // 止めたままリクエストし直した (裏から戻った)。動かすときにもう一度リクエストし直さないよう、止めた時刻をいまにする
                player.playWhenReady = false
                pausedAt = System.nanoTime()
            }
            playOnLoad = true
        }
        // 読みはじめたら、プレーヤーの 0 が録画の何ミリ秒かを決める
        LaunchedEffect(timeline.requests) {
            while (!timeline.settle()) delay(100)
        }
        // チャプターは denpa から (ストリームには入っていない)
        LaunchedEffect(Unit) {
            chapters = try {
                repo.api.chapters(repo.base, recording.id)
            } catch (_: Unauthorized) {
                return@LaunchedEffect onUnauthorized()
            }
        }
        // 続けて押した左右は、止まってから1度だけリクエストし直す
        LaunchedEffect(timeline.pending) {
            val target = timeline.pending ?: return@LaunchedEffect
            delay(STEP_SETTLE_MS)
            timeline.request(target)
        }
        /*
         * **裏に回ったら止めて denpa から降り、観た位置を預ける** (変換していれば denpa の変換の枠も空く)。
         * 流しっぱなしの1本なので、戻ったら止めた位置からリクエストし直す (止めていたなら止めたまま)
         */
        var stoppedAt by remember { mutableStateOf<Long?>(null) }
        OnBackground(
            onStop = {
                if (ended) return@OnBackground
                val here = timeline.position()
                stoppedAt = here
                playOnLoad = player.playWhenReady
                playing = false
                if (here > 0 && !deleted) saveAt(repo, recording, here, length())
                player.stop()
            },
            onStart = {
                val here = stoppedAt ?: return@OnBackground
                stoppedAt = null
                playing = playOnLoad
                timeline.request(here)
            },
        )
    }
    /**
     * **最後まで観た。** すぐ終わりの位置を預ける。終わりの CM を飛ばして終わると、閉じるまで預けないうちは
     * denpa が観終えたと分からない (ブラウザの denpa の `finished()` と同じ)。末尾の位置を渡せば denpa が続きを消し、観終えた印 (`watchedAt`) を付ける。
     * 流してもらっているときに尻をリクエストせずに終えたなら、ストリームも止めて denpa から降りる
     */
    fun finish() {
        if (ended) return
        ended = true
        bar = null
        if (stream != null && player.playbackState != Player.STATE_ENDED) player.stop()
        val length = length()
        repo.app.scope.launch {
            repo.api.saveResume(repo.base, recording.id, length, length)
            repo.watchNext(recording, (length * 1000).toLong(), (length * 1000).toLong(), finished = true)
            repo.positionSaved()
        }
    }
    /** `to` へ動く。流してもらっているときに尻 (もう流すものが無いところ) なら、リクエストせずに最後まで観たことにする */
    fun go(to: Long) {
        if (timeline.reachesEnd(to)) finish() else timeline.seekTo(to)
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                // ファイルは動画に入っているチャプター
                if (stream == null) chaptersOf(tracks).takeIf { it.isNotEmpty() }?.let { chapters = it }
            }

            /*
             * **最後まで来たら、すぐ終わりの位置を預ける** (`finish`)。
             * 流してもらっているときは、尻より手前で終わったのは切れた (denpa の入れ替え・変換が止まった) ので、いた位置からリクエストし直す
             */
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED || ended) return
                val here = timeline.position()
                if (stream != null && here < timeline.durationMs() - STREAM_END_MARGIN_MS) {
                    // 映したあとに終わったのは入れ替え。映る前に終わったのは、何度かリクエストしてもだめなら終わりとみなす
                    val verdict = if (recovery.pictured) Reconnect.Verdict.Retry else Reconnect.Verdict.RetryFew
                    if (recovery.retry("ended ストリームが終わった (位置 ${here / 1000} 秒 / ${timeline.durationMs() / 1000} 秒、映して ${recovery.playedSeconds()} 秒)", verdict)) return
                }
                finish()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    /*
     * **CM の頭の少し手前 (`CM_LEAD_MS`) で終わりまで飛ぶ。** 入ってから見つけて飛ぶと、見つけるまでの CM のコマが映る。
     * 飛ぶところは ExoPlayer に預ける (`PlayerMessage`。流している位置がそこに来たら、再生の糸で呼ばれる)。
     * 呼ばれたら主の糸の列の先頭に割り込んで飛ぶ (列の後ろに並ぶと、そのぶん遅れて CM のコマが出ることがある)。
     * 飛んでいる間は前の映像 (本編の末尾) が残る。速いほど先に来るので、手前の幅も速さに合わせる。
     * 飛んだ・戻した・送ったら (`moves`) 預け直す。開いた・飛んだ先が CM の中なら、すぐ飛ぶ (映る前に。止めていれば動かしたら)。
     * 流してもらっているときは、預ける位置はプレーヤーの位置 (録画の時刻から戻したもの。決まるまで待つ) で、飛ぶのは再リクエスト
     *
     * **続きの位置が CM の中だったときは、飛んでも「CM を飛ばしました」と言わない** (ブラウザの denpa と同じ。開いたときの
     * 「続きから」だけを残す — 観る人にとっては続きから始まっただけ)。CM の判定はチャプターが届いてから
     * (`chapters` が空なら何もしない) で、続きの位置は開くときに渡してある (`load`) ので、頭の CM を見て飛んでから続きへ移ることは無い
     */
    LaunchedEffect(player, chapters, skipCm, moves, speed, timeline.baseMs) {
        if (!skipCm || chapters.none { it.isCm } || timeline.baseMs == null) return@LaunchedEffect
        val lead = (CM_LEAD_MS * speed).toLong()
        fun hop(cm: ChapterMark) {
            // 左右で動かしている途中 (まとめてリクエストする前)。押した行き先を捨てない
            if (timeline.pending != null) return
            skipped += cm.startMs
            go(cm.endMs + hopLead)
            if (!resumedInCm(cm, recording.resumeMs, moves)) flash("CM を飛ばしました")
        }
        val main = Handler(Looper.getMainLooper())
        /** 預け直したあとに、前に預けたぶんが割り込んでこないように */
        var live = true
        val messages = cmHopPoints(chapters, timeline.position(), skipped, lead).mapNotNull { (at, cm) ->
            val point = timeline.playerPosition(at)?.takeIf { it >= 0 } ?: return@mapNotNull null
            player.createMessage { _, _ -> main.postAtFrontOfQueue { if (live && cm.startMs !in skipped) hop(cm) } }
                .setPosition(point)
                .send()
        }
        try {
            while (true) {
                val cm = cmSkipTarget(chapters, timeline.position(), skipped, lead) ?: break
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
            if (player.isPlaying) repo.api.saveResume(repo.base, recording.id, timeline.position() / 1000.0, length())
        }
    }
    /**
     * 観た位置を預け、途中なら「続きを視聴」に出す (最後まで来ていれば消す)。画面はもう閉じる・裏に回るので、アプリの寿命で送る
     * (画面の scope だと送る前に取り消される)
     */
    fun saveNow() {
        if (deleted) return
        val here = timeline.position()
        if (here > 0) saveAt(repo, recording, here, length(), finished = ended)
    }
    // 閉じるときに1回。player を畳むのは rememberPlayer の後始末で、こちらが先に走る
    DisposableEffect(Unit) { onDispose { saveNow() } }
    /*
     * **ファイルは、裏に回ったら止めて、観た位置を預ける** (ホーム・別のアプリ。流しっぱなしにすると裏で進んでしまう)。
     * 戻ったら、流していたなら続きから流す (止めていたなら止めたまま)。流してもらっているときは上で denpa から降りる
     */
    if (stream == null) {
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
    }

    /** 消して一覧へ戻る。一覧からも抜き、隣にフォーカスする */
    fun deleteNow() = scope.deleteFromPlayer(repo, recording.id, flash, onUnauthorized) { deleted = true; ui.leave() }
    /**
     * 止める・動かす。**止めたら操作の列を開いて「再生」にフォーカスする** (決定でそのまま動かせる)。
     * 動かしたら操作バーを閉じて何も出さない (映像を観たいだけなので)。操作バーを閉じて止めたままなら位置のバーを出す
     */
    fun togglePause() {
        if (player.playWhenReady) {
            pausedAt = System.nanoTime()
        } else if (stream != null && System.nanoTime() - pausedAt > LONG_PAUSE_NS) {
            timeline.request(timeline.position())
        }
        player.playWhenReady = !player.playWhenReady
        playing = player.playWhenReady
        at = timeline.position()
        if (!playing) flasher.clear()
        bar = if (playing) null else Bar.Actions
    }
    /**
     * 10 秒ずつ戻す・送る (左右とシークバー)。戻して CM を観に行ったなら、そこは飛ばさない。
     * 流してもらっているときは、続けて押したぶんをまとめてリクエストする (`pending`)
     */
    fun step(direction: Int): Long {
        val total = timeline.durationMs()
        val ahead = timeline.position() + direction * SEEK_STEP_MS
        val to = if (total > 0) ahead.coerceIn(0, total) else ahead.coerceAtLeast(0)
        if (direction < 0) cmRunAt(chapters, to)?.let { skipped += it.startMs }
        when {
            stream == null -> player.seekTo(to)
            timeline.reachesEnd(to) -> finish()
            else -> timeline.pending = to
        }
        at = to
        return to
    }
    fun jump(to: ChapterMark) {
        if (to.isCm) cmRunAt(chapters, to.startMs)?.let { skipped += it.startMs }
        go(to.startMs)
        at = to.startMs
        flash("${to.title}  ${position(to.startMs)}")
    }
    // CM を飛ばしているなら、送り先も本編だけ (CM の頭に止まっても、すぐ飛ばされるだけ)
    fun nextChapterNow() {
        val next = nextChapter(if (skipCm) chapters.filterNot { it.isCm } else chapters, timeline.position())
        if (next == null) flash(if (chapters.isEmpty()) "チャプターがありません" else "最後のチャプターです")
        else jump(next)
    }
    fun previousChapterNow() {
        val previous = previousChapter(if (skipCm) chapters.filterNot { it.isCm } else chapters, timeline.position())
        if (previous == null) flash("チャプターがありません") else jump(previous)
    }
    /** 操作バーを開く。開く前の通知 (開いてすぐの番組名など) は消す (操作バーのタイトルと同じことを言うだけなので、操作バーのタイトルに持ち込まない) */
    fun open(which: Bar) {
        at = timeline.position()
        playing = player.playWhenReady
        flasher.clear()
        bar = which
    }
    val deleteControl = Control(deleteLabel(delete.armed), icon = R.drawable.ic_delete) { if (delete.press()) deleteNow() }
    val seekFocus = remember { FocusRequester() }

    // 操作バー (終わり・止めている間・操作の列) を出している間は、通知を操作バーのタイトルに添える (`covered`)
    val covered = ended || bar != null || !playing
    PlayerFrame(player, overlay, error, active = bar == null && !details && !ended && !ui.leaving, captions = captions, recovery = recovery, covered = covered, above = {
        if (ended) {
            ControlBar(
                "最後まで観ました",
                // 観終えたものは消すことが多いので、最初は削除にフォーカスする (2回押しなので、1回では消えない)
                listOf("" to listOf(Control(BACK_TO_LIST, icon = R.drawable.ic_back) { ui.leave() }, deleteControl.copy(initial = true))),
            )
        } else if (bar == null && !playing) {
            val total = timeline.durationMs().takeIf { it > 0 }
            PausedBar(recording.title, "${position(at)}${total?.let { " / ${position(it)}" } ?: ""}") { ProgressLine(at, total ?: 0, chapters) }
        } else bar?.let { which ->
            val total = timeline.durationMs().takeIf { it > 0 }
            LaunchedEffect(which) { if (which == Bar.SeekBar) runCatching { seekFocus.requestFocus() } }
            ControlBar(
                buildAnnotatedString {
                    appendBroadcast(recording.title)
                    append("\n${position(at)}${total?.let { " / ${position(it)}" } ?: ""}")
                },
                listOf(
                    "" to listOf(
                        playControl(playing) { togglePause() },
                        // ボタンは短く (リモコンの「前へ」「次へ」と同じ名前)。操作バーが1行に収まるように
                        Control("前へ", icon = R.drawable.ic_previous, description = "前のチャプター") { previousChapterNow() },
                        Control("次へ", icon = R.drawable.ic_next, description = "次のチャプター") { nextChapterNow() },
                    ),
                    "" to listOf(
                        speedControl(speed) { scope.stepSpeed(repo, speed) },
                    ),
                    "" to listOf(
                        Control(if (skipCm) "CM 飛ばし オン" else "CM 飛ばし オフ", on = skipCm, icon = R.drawable.ic_skip_cm) {
                            toggleCm()
                        },
                    ),
                    "" to tracks.controls() + bakedAudio.controls(),
                    "" to listOf(deleteControl),
                ),
                header = { actions ->
                    // シークバーが操作バーのいちばん上の列。そこで上キーを押すと閉じて映像に戻る (ライブのメニューの操作の列と同じ)
                    ProgressLine(at, total ?: 0, chapters, seekFocus, down = actions, onUp = { bar = null }) { direction -> step(direction) }
                },
                focusActions = which == Bar.Actions,
                onActivity = { ui.touched = System.nanoTime() },
            )
        }
    }, onKey = { event ->
        when (recordingCommand(event.nativeKeyEvent.keyCode)) {
            // 止めている間は位置のバーに出るので、通知は出さない
            RecordingCommand.Back -> { step(-1).let { if (playing) flash(position(it)) }; true }
            RecordingCommand.Forward -> { step(1).let { if (playing) flash(position(it)) }; true }
            RecordingCommand.SeekBar -> { open(Bar.SeekBar); true }
            RecordingCommand.Actions -> { open(Bar.Actions); true }
            RecordingCommand.NextChapter -> { nextChapterNow(); true }
            RecordingCommand.PreviousChapter -> { previousChapterNow(); true }
            RecordingCommand.PlayPause -> { togglePause(); true }
            RecordingCommand.NextSpeed -> { flash(scope.stepSpeed(repo, speed)); true }
            RecordingCommand.Details -> { ui.details = true; true }
            null -> false
        }
    }, onCenter = { press -> ui.center(press) { togglePause() } })

    if (details) PlayerDetailDialog(repo, recording, onDelete = { deleteNow() }, onClose = { details = false }, onUnauthorized = onUnauthorized)
}

/** 観た位置を預け、「続きを視聴」を直す。画面はもう閉じる・裏に回るので、アプリの寿命で送る */
private fun saveAt(repo: Repository, recording: Recording, atMs: Long, lengthSeconds: Double, finished: Boolean = false) {
    repo.app.scope.launch {
        repo.api.saveResume(repo.base, recording.id, atMs / 1000.0, lengthSeconds)
        repo.watchNext(recording, atMs, (lengthSeconds * 1000).toLong(), finished = finished)
        repo.positionSaved()
    }
}

/** 流してもらっているときに、尻からこれより手前でストリームが終わったら切れたとみなす (ミリ秒。追っかけと同じ) */
private const val STREAM_END_MARGIN_MS = 30_000L

/** 続けて押した左右を、止まってからまとめてリクエストするまで (ミリ秒。追っかけと同じ) */
private const val STEP_SETTLE_MS = 700L

/** 流してもらっているときに、これより長く止めていたら動かすときにリクエストし直す (ナノ秒。追っかけと同じ 10 秒) */
private const val LONG_PAUSE_NS = 10_000_000_000L

/** 元の TS から流してもらうとき、denpa がリクエストした位置より手前から流しはじめる幅 (ミリ秒。denpa の docs/api.md「録画を流す」) */
private const val TS_STREAM_LEAD_MS = 1_000L

/** 録画・追っかけの操作バーをどこにフォーカスして開いたか。下キーならシークバー、上キー・Menu なら操作の列 */
internal enum class Bar { SeekBar, Actions }

/**
 * 録画・追っかけの操作バーと戻る (`rememberPlayerBar`)。操作バーは 5 秒触らなければ閉じる (止めている間も)。
 * 戻るは1つで受け、操作バーが開いていれば閉じ、無ければ一覧へ (ライブと同じ理由)。続けて押しても1つだけ戻る
 */
@Stable
internal class PlayerBar(private val onLeave: () -> Unit) {
    /** 開いている操作バー (null なら何も出していない) */
    var bar by mutableStateOf<Bar?>(null)
    /** 操作バーで最後にキーを押したとき */
    var touched by mutableLongStateOf(0L)
    /** 番組の詳しいところを開いているか (決定の長押し) */
    var details by mutableStateOf(false)
    /** 一覧へ戻るところ (映像にフォーカスを取り返させない。戻った先の一覧が開いた録画にフォーカスするので) */
    var leaving by mutableStateOf(false)
        private set

    fun leave() {
        if (leaving) return
        leaving = true
        onLeave()
    }

    /** 決定の短押しで止める・動かす、長押しで詳細 */
    fun center(press: CenterPress.Action, togglePause: () -> Unit) = when (recordingCenter(press)) {
        RecordingCenter.PlayPause -> togglePause()
        RecordingCenter.Details -> details = true
    }
}

@Composable
internal fun rememberPlayerBar(onLeave: () -> Unit, playing: Boolean): PlayerBar {
    val leave by rememberUpdatedState(onLeave)
    val state = remember { PlayerBar { leave() } }
    BackHandler(enabled = !state.leaving) { if (state.bar != null) state.bar = null else state.leave() }
    LaunchedEffect(state.bar, state.touched, playing) {
        if (state.bar == null) return@LaunchedEffect
        delay(5_000)
        state.bar = null
    }
    return state
}

/**
 * 止める・動かすのボタン (録画・追っかけ)。**塗らない** (塗るのはオン・オフのボタンでオンのものだけ。「一時停止」がオンのように見える)。
 * 操作バーを開いたときはここにフォーカスする (`initial`。止めて開いた操作バーは決定でそのまま動かせる)
 */
internal fun playControl(playing: Boolean, onClick: () -> Unit) =
    Control(if (playing) "一時停止" else "再生", icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play, initial = true, onClick = onClick)

/**
 * 止めたまま操作バーが閉じたときに出したままにする位置のバー (録画・追っかけ)。1行目は「一時停止」と番組名、2行目に位置 (`where`) とキーの手引き。
 * ボタンは無く、キーは映像が受けたまま (左右で 10 秒、決定で動かす、下でシークバー、上で操作の列、長押しで詳細)
 */
@Composable
internal fun BoxScope.PausedBar(title: String, where: String, progress: @Composable ColumnScope.() -> Unit) {
    ControlBar(
        buildAnnotatedString {
            append("一時停止  ")
            appendBroadcast(title)
            append("\n$where  ・決定で再生  $SEEK_HINT")
        },
        emptyList(),
        header = { progress() },
        focusActions = false,
    )
}

/** 終わりの操作バーの、一覧へ戻るボタン (ブラウザの denpa と同じ言い方) */
internal const val BACK_TO_LIST = "一覧へ戻る"

/**
 * 速さのボタン (録画・追っかけ)。1つのボタンで送る (`stepSpeed`)。列を短くして、押す回数も少なく。
 * **等速ならメーターの印だけの丸、速めている間は数字だけ (「1.25」)** — ブラウザの denpa の速さのボタンと同じ。
 * ふだん (等速) はボタンに数字が出たままにならず、速めているときは数字そのものが「速めている」の印になる (塗るのも同じとき)。
 * ボタンの上では × を付けない (速さのボタンだと分かっている)。読み上げは速さも読む (`speedDescription`)
 */
internal fun speedControl(speed: Float, onClick: () -> Unit) =
    if (speed == 1f) {
        Control("", icon = R.drawable.ic_speed, description = speedDescription(speed), onClick = onClick)
    } else {
        Control(speedNumber(speed), on = true, description = speedDescription(speed), onClick = onClick)
    }

/** 速さを1段送る (1 → 1.25 → 1.5 → 2 → 1。端末ごとに覚える)。知らせる文を返す */
internal fun CoroutineScope.stepSpeed(repo: Repository, speed: Float): String {
    val next = nextSpeed(speed)
    launch { repo.app.settings.setPlaybackSpeed(next) }
    return "速さ ${speedLabel(next)}"
}

/** 録画・追っかけのキーの手引き */
internal const val SEEK_HINT = "下でシークバー・上でメニュー"

/** 流してもらっているときに、尺のこれだけ手前より先は尻とみなす (ミリ秒)。そこをリクエストしても流すものがほとんど無い */
private const val END_TOLERANCE_MS = 2_000L
