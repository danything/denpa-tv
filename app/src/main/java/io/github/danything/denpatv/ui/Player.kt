package io.github.danything.denpatv.ui

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.os.SystemClock
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.ui.SubtitleView
import io.github.danything.denpatv.data.EngineHttp
import io.github.danything.denpatv.data.CenterPress
import io.github.danything.denpatv.data.ChapterMark
import io.github.danything.denpatv.data.Http
import io.github.danything.denpatv.data.LongPressGuard
import io.github.danything.denpatv.data.LiveQuality
import androidx.media3.ui.compose.PlayerSurface
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * 溜め方。**ライブは少なく溜めて、放送に近いところで観る。**
 *
 * ExoPlayer の既定は 50 秒まで溜めて 1 秒溜まったら動き出す。録画にはそれでよいが、ライブでは
 * 溜めたぶんだけ放送から遅れる。denpa のライブは流しっぱなしの1本 (区切られた HLS ではない) なので、
 * Media3 の LiveConfiguration (目標の遅れ) は効かない。溜める量で決め、遅れたら追いつく (`CatchUp`)
 */
enum class Buffering(val minMs: Int, val maxMs: Int, val startMs: Int, val afterRebufferMs: Int) {
    /** 録画。ExoPlayer の既定 */
    Recording(50_000, 50_000, 1_000, 2_000),
    /** ライブ (denpa が焼く H.264 / AV1)。焼き上がりが塊で届くので少しは溜める */
    Live(2_000, 8_000, 1_000, 1_500),
    /** 生の TS (MPEG-2)。届いたそばから出す */
    LowLatency(500, 2_000, 250, 500),
}

/** ライブ・追っかけの溜め方。生の TS は届いたそばから出す */
val LiveQuality.buffering: Buffering get() = if (this == LiveQuality.Raw) Buffering.LowLatency else Buffering.Live

/**
 * 映像を取る道。**Media3 のネットワーク スタックの頁の勧めどおり**: Android 14 からは OS の
 * HttpEngine (アプリで1つ)、それより前は DefaultHttpDataSource (OS の HttpURLConnection)。
 * どちらも `DefaultDataSource.Factory` で包む (http(s) 以外も同じ口で開けるように)。docs/libraries.md
 */
@OptIn(UnstableApi::class)
@SuppressLint("NewApi") // EngineHttp は hasHttpEngine() が通ったときしか作られない (DenpaApp.engineHttp)
fun dataSourceFactory(context: Context, engine: EngineHttp?, token: String?): DataSource.Factory {
    val http: HttpDataSource.Factory = if (engine != null) {
        engine.factory()
    } else {
        DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(Http.CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(Http.READ_TIMEOUT_MS)
    }
    // 家の外の denpa に登録してあれば、映像にもトークンを付ける
    http.setDefaultRequestProperties(authorizationHeaders(token))
    return DefaultDataSource.Factory(context, http)
}

/** 映像の要求に足すヘッダ。トークンが無ければ空 */
fun authorizationHeaders(token: String?): Map<String, String> =
    Http.bearer(token)?.let { mapOf("Authorization" to it) } ?: emptyMap()

/**
 * `clock` は TS の読み手が 0 に寄せた幅を覚える (生の TS の字幕を放送の PTS で突き合わせるため。RawCaptions.kt)。
 * 読み手の作り方は Media3 の既定と同じ。`dualMono` は音の出口の手前に挟む (デュアルモノの片側を両耳へ。DualMono.kt)
 */
@OptIn(UnstableApi::class)
fun buildPlayer(
    context: Context,
    engine: EngineHttp?,
    token: String?,
    buffering: Buffering,
    clock: TsClock,
    dualMono: DualMonoProcessor,
): ExoPlayer =
    ExoPlayer.Builder(context, DualMonoRenderersFactory(context, dualMono))
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory(context, engine, token), clock))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(buffering.minMs, buffering.maxMs, buffering.startMs, buffering.afterRebufferMs)
                .build(),
        )
        .build()

/**
 * ライブで放送から遅れたら追いつく。溜まりすぎたら少し速く回し、もっと溜まったら飛ぶ。
 * 止まっている (一時停止) 間は触らない
 */
@Composable
fun CatchUp(player: ExoPlayer, buffering: Buffering) {
    val (speedUpMs, jumpMs) = if (buffering == Buffering.LowLatency) 1_500L to 4_000L else 4_000L to 10_000L
    LaunchedEffect(player) {
        while (true) {
            delay(500)
            if (!player.playWhenReady || player.playbackState != Player.STATE_READY) continue
            val ahead = player.bufferedPosition - player.currentPosition
            when {
                ahead > jumpMs -> player.seekTo(player.bufferedPosition - buffering.startMs)
                ahead > speedUpMs -> if (player.playbackParameters.speed == 1f) player.setPlaybackSpeed(1.05f)
                ahead < speedUpMs / 3 -> if (player.playbackParameters.speed != 1f) player.setPlaybackSpeed(1f)
            }
        }
    }
}

/** 動画に入っているチャプター (Media3 が Matroska の Chapters を `Chapter` として出す) */
@OptIn(UnstableApi::class)
fun chaptersOf(tracks: Tracks): List<ChapterMark> =
    tracks.groups.asSequence()
        .flatMap { group -> (0 until group.length).asSequence().map { group.getTrackFormat(it) } }
        .mapNotNull { it.metadata }
        .flatMap { metadata -> (0 until metadata.length()).asSequence().map { metadata[it] } }
        .filterIsInstance<Chapter>()
        .filterNot { it.isHidden }
        .filter { it.startTimeMs != C.TIME_UNSET }
        .map { ChapterMark(it.startTimeMs, if (it.endTimeMs == C.TIME_UNSET) Long.MAX_VALUE else it.endTimeMs, it.title?.value ?: "") }
        .distinctBy { it.startMs }
        .sortedBy { it.startMs }
        .toList()

/**
 * 映像と字幕と、上に重ねる文字。キーは呼ぶ側が受ける (ライブは局送り、録画は送り戻し)。
 * **決定 (OK) は短押しと長押しを分けて `onCenter` に渡す** (録画は長押しで操作の列、ライブは長押しで局と番組。Menu キーの無いリモコンが多いので)。
 *
 * 上に重ねたもの (ライブのメニュー・操作の帯) を閉じたら、**必ず映像にキーを戻す** — 閉じたものに合っていたまま
 * 消えると、どこにも合わずリモコンが効かなくなる。`active` の間は**映像そのものに合っているか見張り、外れていたら
 * 取り戻す** (閉じたものが消える間・帯が勝手に消えたとき・端末によって遅れて合いが外れる場合も)。
 * 開いている間と、画面を離れるとき (一覧に戻る間に一覧が合いを取るので、取り返さない) は `active = false`
 *
 * 字幕は `SubtitleView` (View) で出す。焼いたものの字幕は PGS (絵) で、Compose の部品はまだ絵の字幕を描けない。
 * **字幕は、下に重ねたもの (知らせ・`above` の帯やメニュー) の上へ逃がす** (`OverlayInsets`)。出したらすぐ上へ、閉じたら滑らかに戻す
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerFrame(
    player: ExoPlayer,
    overlay: String?,
    error: String?,
    /** 映像がキーを受けるか。上に重ねたもの (メニュー・帯) が開いている間は false。閉じたら映像に戻す */
    active: Boolean = true,
    onKey: (KeyEvent) -> Boolean,
    /** キーを離したとき (ライブの局送りは、離してから待って頼む) */
    onKeyUp: (KeyEvent) -> Unit = {},
    onCenter: (CenterPress.Action) -> Unit = {},
    /** 知らせの下に出す進み (ライブの番組の進み)。null なら出さない */
    progress: Pair<Long, Long>? = null,
    /** 生の TS の字幕 (`rememberRawCaptions`)。焼いた映像の字幕の上、知らせの下に重ねる */
    captions: RawCaptionState? = null,
    /** 映るまでの間に出す、何をしているか (ライブは「選局しています」)。流れが届いたら「映像を待っています」に替わる */
    busyLabel: String = "読み込んでいます",
    above: @Composable BoxScope.() -> Unit = {},
) {
    val loading = rememberLoading(player)
    val focus = remember { FocusRequester() }
    val center = remember { CenterPress() }
    /** 映像そのものに合っているか */
    var focused by remember { mutableStateOf(false) }
    /** 下に重ねたものの高さ。字幕をその上へ逃がす */
    val insets = remember { OverlayInsets() }
    val inset = rememberCaptionInset(insets)
    /** いま出している字幕 (`SubtitleView` に渡し、どこまで逃がすかも見る) */
    var cues by remember { mutableStateOf(emptyList<Cue>()) }
    val captionSpan = remember { { width: Int, height: Int -> cueSpan(cues, width, height) } }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            // プレーヤーを作り直したら、前のプレーヤーの字幕を残さない
            cues = emptyList()
        }
    }
    LaunchedEffect(active) {
        center.reset()
        if (!active) return@LaunchedEffect
        // 閉じたものが消えるのを1こま待ってから合わせ、その後も外れたら取り戻す
        while (true) {
            withFrameNanos { }
            if (!focused) runCatching { focus.requestFocus() }
            delay(FOCUS_WATCH_MS)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            // 開いていたものが閉じて合いがどこにも無くなったら、映像に戻す
            .onFocusChanged { focused = it.isFocused }
            // 上に重ねたものが開いている間は受けない (そちらのキーがここまで上がってくるので)
            .onKeyEvent { event ->
                if (!active) return@onKeyEvent false
                if (event.nativeKeyEvent.keyCode in LongPressGuard.CENTER_KEYS) {
                    val action = when (event.type) {
                        KeyEventType.KeyDown -> center.down(event.nativeKeyEvent.repeatCount, event.nativeKeyEvent.isLongPress)
                        KeyEventType.KeyUp -> center.up()
                        else -> null
                    }
                    action?.let(onCenter)
                    return@onKeyEvent true
                }
                if (event.type == KeyEventType.KeyUp) onKeyUp(event)
                event.type == KeyEventType.KeyDown && onKey(event)
            }
            .focusable(),
    ) {
        PlayerSurface(player = player, modifier = Modifier.fillMaxSize())
        AndroidView(
            factory = ::SubtitleView,
            /*
             * **流している間は画面を点けたままにする** (スクリーンセーバーを出さない)。この画面の View に付けるので、
             * 画面を離れれば一緒に外れる (ComposeView に付けると、次の画面と取り合って消し合う)
             */
            update = { view ->
                view.keepScreenOn = loading.awake
                view.setCues(cues)
            },
            // 画面を離れたら必ず外す (外した View が窓の印を持ったまま残らないように)
            onReset = { view -> view.keepScreenOn = false },
            onRelease = { view -> view.keepScreenOn = false },
            modifier = Modifier.fillMaxSize().liftCaptions(inset, captionSpan),
        )
        captions?.let { RawCaptionLayer(it, inset) }
        if (error == null) LoadingVeil(loading, busyLabel)
        CompositionLocalProvider(LocalOverlayInsets provides insets) {
            Notice(error ?: overlay, if (error == null) progress else null)
            above()
        }
    }
}

/** 下の端に出す知らせ。操作の帯と同じく、下の端に小さく (下から薄く暗くするだけ) */
@Composable
private fun BoxScope.Notice(text: String?, progress: Pair<Long, Long>?) {
    if (text == null) return
    BottomPanel(spacing = 4.dp) {
        TitleLines(text)
        progress?.let { (at, length) -> ProgressLine(at, length) }
    }
}

/** 映像に合っているか見張る間 (ミリ秒) */
private const val FOCUS_WATCH_MS = 250L

/**
 * 映るまでの様子 (`rememberLoading`)。**ブラウザの denpa の幕 (`PlayerVeil`) にあたる。**
 *
 * - `busy` … 動かすつもりで読み込んでいる (選局・頼み直し・シークの直後)
 * - `arrived` … 流れが届きはじめた (中身の形が分かった)。届くまでは「選局しています」、届いたら「映像を待っています」
 * - `pictured` … 一度でも絵を出したか。出していれば、次が映るまで**前の絵が画面に残っている** (Media3 は替えるときに面を消さない)
 * - `since` … `busy` になったとき (uptime ミリ秒)
 */
@Stable
class LoadingState {
    var busy by mutableStateOf(false)
    var arrived by mutableStateOf(false)
    var pictured by mutableStateOf(false)
    var since by mutableLongStateOf(0L)
    /** 画面を点けたままにするか (流している・流すつもりで読み込んでいる) */
    var awake by mutableStateOf(false)
}

/**
 * プレーヤーから映るまでの様子を拾う。`awake` は**動いている間は画面を点けたままにする**か (スクリーンセーバーを出さない)。
 * 止めた・終わった・止まった (`stop`) ら離す
 */
@Composable
private fun rememberLoading(player: ExoPlayer): LoadingState {
    val state = remember(player) { LoadingState() }
    DisposableEffect(player) {
        fun update() {
            val busy = player.playWhenReady && player.playbackState == Player.STATE_BUFFERING
            if (busy && !state.busy) state.since = SystemClock.uptimeMillis()
            state.busy = busy
            state.arrived = !player.currentTracks.isEmpty
            // 流しているか、流すつもりで読み込んでいる間だけ。止めたら (一時停止・終わり・エラー・stop) スクリーンセーバーに任せる
            state.awake = player.playWhenReady &&
                (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = update()
            override fun onRenderedFirstFrame() {
                state.pictured = true
            }
        }
        player.addListener(listener)
        update()
        onDispose { player.removeListener(listener) }
    }
    return state
}

/**
 * 読み込んでいる間の幕。**決まりはブラウザの denpa (`PlayerVeil`) と同じ:**
 *
 * - 前の絵が残っている間は塗り潰さず、回るものは `SPINNER_DELAY_MS` 待ってから出す (局替えはたいてい1秒前後で終わるので、
 *   すぐ出すとちらつくだけ。テレビと同じく前の絵のまま止めておく)
 * - 前の絵を残すのは `HOLD_MOST_MS` まで。それを過ぎたら暗くする (選局に失敗したのに前の局が止まって見えるのがいちばん悪い)
 * - まだ何も映していなければ (開いた直後)、すぐ回るものを出す
 *
 * 回るものの下に、いま何をしているかを1行 (「選局しています」→「映像を待っています」)
 */
@Composable
private fun BoxScope.LoadingVeil(state: LoadingState, label: String) {
    if (!state.busy) return
    var elapsed by remember(state.since) { mutableLongStateOf(0L) }
    LaunchedEffect(state.since) {
        while (true) {
            elapsed = SystemClock.uptimeMillis() - state.since
            if (elapsed > HOLD_MOST_MS) break
            delay(100)
        }
    }
    val holding = state.pictured && elapsed < HOLD_MOST_MS
    if (!holding) Box(Modifier.fillMaxSize().background(Color(0x99000000)))
    if (state.pictured && elapsed < SPINNER_DELAY_MS) return
    Column(
        Modifier
            .align(Alignment.Center)
            .background(Color(0x73000000), RoundedCornerShape(16.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spinner()
        Text(if (state.arrived) "映像を待っています" else label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
}

/** 回るもの (Compose for TV には無いので、弧を回すだけ) */
@Composable
private fun Spinner() {
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_000, easing = LinearEasing)),
        label = "turn",
    )
    Canvas(Modifier.size(40.dp)) {
        val stroke = 4.dp.toPx()
        drawArc(Color(0x40FFFFFF), 0f, 360f, false, style = Stroke(stroke))
        drawArc(Color.White, turn, 90f, false, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** 前の絵を残したまま回るものを出すまで (ミリ秒)。ブラウザの denpa の `PlayerVeil` と同じ 1.5 秒 */
private const val SPINNER_DELAY_MS = 1_500L

/** 前の絵を残しておく上限 (ミリ秒)。ブラウザの denpa の `HOLD_MOST` と同じ 6 秒 */
private const val HOLD_MOST_MS = 6_000L

/**
 * アプリが裏に回った (ホーム・別のアプリ・画面が消えた) ときと、前に戻ったとき。**最初に開いたときの `onStart` は呼ばない。**
 * 再生の画面はここで止め、戻ったら続ける (裏で流し続けない。ライブならチューナーを空ける)
 */
@Composable
fun OnBackground(onStop: () -> Unit, onStart: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val stop by rememberUpdatedState(onStop)
    val start by rememberUpdatedState(onStart)
    DisposableEffect(owner) {
        var stopped = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (!stopped) { stopped = true; stop() }
                Lifecycle.Event.ON_START -> if (stopped) { stopped = false; start() }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/** 何秒かだけ出して消える文字 */
@Composable
fun rememberFlash(): Pair<String?, (String) -> Unit> {
    var text by remember { mutableStateOf<String?>(null) }
    var shownAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(shownAt) {
        if (shownAt == 0L) return@LaunchedEffect
        delay(4_000)
        text = null
    }
    return text to { value: String ->
        text = value
        shownAt = System.nanoTime()
    }
}

/** `rememberPlayer` が返すもの。`val (player, error) = …` で受けられる。`dualMono` はデュアルモノの配り直し (`rememberTracks` に渡す) */
data class PlayerHandle(val player: ExoPlayer, val error: String?, val dualMono: DualMonoProcessor)

/**
 * ExoPlayer を画面の寿命に合わせる。エラーは文にして返す。
 * `clock` は生の TS の字幕を出す画面だけが渡す (`rememberRawCaptions` と同じものを)
 */
@Composable
fun rememberPlayer(repo: Repository, buffering: Buffering, onUnauthorized: () -> Unit = {}, clock: TsClock = remember { TsClock() }): PlayerHandle {
    val context = LocalContext.current
    val dualMono = remember(buffering) { DualMonoProcessor() }
    val player = remember(buffering) { buildPlayer(context, repo.app.engineHttp, repo.token, buffering, clock, dualMono) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            @OptIn(UnstableApi::class)
            override fun onPlayerError(e: PlaybackException) {
                // トークンが外された・期限切れ。繋ぐ画面へ
                val code = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                if (code == 401) onUnauthorized()
                error = "再生できません: ${e.errorCodeName}"
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) error = null
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    return PlayerHandle(player, error, dualMono)
}

fun MediaItem.Builder.uri(url: String, mime: String): MediaItem = setUri(url).setMimeType(mime).build()

