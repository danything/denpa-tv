package io.github.danything.denpatv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.util.Log
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.metadata.Chapter
import io.github.danything.denpatv.data.BUSY_MESSAGE
import io.github.danything.denpatv.data.BusyLoadErrorPolicy
import io.github.danything.denpatv.data.EMPTY_STREAM_MESSAGE
import io.github.danything.denpatv.data.NO_PLAYABLE_MESSAGE
import io.github.danything.denpatv.data.EmptyGuardDataSource
import io.github.danything.denpatv.data.EmptyStreamException
import io.github.danything.denpatv.data.EngineHttp
import io.github.danything.denpatv.data.FirstFrameWatch
import io.github.danything.denpatv.data.CenterPress
import io.github.danything.denpatv.data.ChapterMark
import io.github.danything.denpatv.data.Http
import io.github.danything.denpatv.data.LiveQuality
import io.github.danything.denpatv.data.Reconnect
import io.github.danything.denpatv.data.isBusyAnswer
import io.github.danything.denpatv.data.isMediaContentType
import io.github.danything.denpatv.data.playbackMessage
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
    /** ライブ (denpa がエンコードする H.264 / AV1)。エンコードした映像が塊で届くので少しは溜める */
    Live(2_000, 8_000, 1_000, 1_500),
    /** 元の TS (MPEG-2)。届いたそばから出す */
    LowLatency(500, 2_000, 250, 500),
}

/** ライブ・追っかけの溜め方。流れている形式 (応答ヘッダ `X-Denpa-Codec`) で決める。元の TS は届いたそばから出す */
val LiveQuality?.buffering: Buffering get() = if (this == LiveQuality.Raw) Buffering.LowLatency else Buffering.Live

/**
 * **溜め方を、流しているあいだに替えられる LoadControl。** 自動 (`codec=auto`) では、MPEG-2 が来るか fMP4 が来るかは応答ヘッダを読むまで分からない。
 * 溜め方ごとに `DefaultLoadControl` を作っておき (割り当てる領域 `allocator` は1つを使い回す)、`buffering` に当たるものに溜めるか・動き出すかを聞く。
 * `buffering` は応答ヘッダを読んだときに読み込みのスレッドから替える (`TsClock.onCodec`)。溜めはじめる前なので、新しいストリームは初めから新しい溜め方になる。
 * プレーヤーを作り直さないので、前の映像を残したまま MPEG-2 と fMP4 を行き来できる
 */
@OptIn(UnstableApi::class)
class BufferingControl(initial: Buffering) : LoadControl {
    private val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
    private val controls: Map<Buffering, DefaultLoadControl> = Buffering.entries.associateWith { b ->
        DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(b.minMs, b.maxMs, b.startMs, b.afterRebufferMs)
            .build()
    }

    @Volatile
    var buffering: Buffering = initial

    private val active: DefaultLoadControl get() = controls.getValue(buffering)

    // 状態 (プレーヤーごとの溜めている途中か・目標の量) はどれにも同じように渡す。溜めるか・動き出すかだけ `active` に聞く
    override fun onPrepared(playerId: PlayerId) = controls.values.forEach { it.onPrepared(playerId) }

    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
        controls.values.forEach { it.onTracksSelected(parameters, trackGroups, trackSelections) }

    override fun onStopped(playerId: PlayerId) = controls.values.forEach { it.onStopped(playerId) }

    override fun onReleased(playerId: PlayerId) = controls.values.forEach { it.onReleased(playerId) }

    override fun getAllocator(playerId: PlayerId): Allocator = allocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = active.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = active.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = active.shouldContinueLoading(parameters)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = active.shouldStartPlayback(parameters)
}

/**
 * 映像を取得する経路。**Media3 のネットワーク スタックのページの勧めどおり**: Android 14 からは OS の
 * HttpEngine (アプリで1つ)、それより前は DefaultHttpDataSource (OS の HttpURLConnection)。
 * どちらも `DefaultDataSource.Factory` で包む (http(s) 以外も同じ仕組みで開けるように)。docs/libraries.md
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
            .setContentTypePredicate(::isMediaContentType)
    }
    // 家の外の denpa に登録してあれば、映像にもアクセストークンを付ける
    http.setDefaultRequestProperties(authorizationHeaders(token))
    // 200 のまま空で閉じたストリーム (選局・エンコードに失敗した) を見分ける (`EmptyStreamException`)
    return DefaultDataSource.Factory(context, EmptyGuardDataSource.Factory(http))
}

/** 映像の要求に足すヘッダ。アクセストークンが無ければ空 */
fun authorizationHeaders(token: String?): Map<String, String> =
    Http.bearer(token)?.let { mapOf("Authorization" to it) } ?: emptyMap()

/**
 * `clock` は TsExtractor が 0 に寄せた幅を覚える (元の TS の字幕を放送の PTS で突き合わせるため。RawCaptions.kt)。
 * Extractor の作り方は Media3 の既定と同じ。`dualMono` は音の出口の手前に挟む (デュアルモノの片側を両耳へ。DualMono.kt)
 */
@OptIn(UnstableApi::class)
fun buildPlayer(
    context: Context,
    engine: EngineHttp?,
    token: String?,
    loadControl: BufferingControl,
    clock: TsClock,
    dualMono: DualMonoProcessor,
): ExoPlayer =
    ExoPlayer.Builder(context, DualMonoRenderersFactory(context, dualMono))
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(dataSourceFactory(context, engine, token), clock)
                // 変換の枠が埋まって断られたら、Media3 の中では読み直さない (繋ぎ直しは `Recovery` が何度かだけ)
                .setLoadErrorHandlingPolicy(BusyLoadErrorPolicy()),
        )
        .setLoadControl(loadControl)
        .build()

/**
 * ライブで放送から遅れたら追いつく。溜まりすぎたら少し速く回し、もっと溜まったら飛ぶ。
 * 止まっている (一時停止) 間は触らない
 */
@Composable
fun CatchUp(player: ExoPlayer, buffering: Buffering) {
    val (speedUpMs, jumpMs) = if (buffering == Buffering.LowLatency) 1_500L to 4_000L else 4_000L to 10_000L
    // 溜め方は流れている形式で替わる (`BufferingControl`)。替わったら見張り直す
    LaunchedEffect(player, buffering) {
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
 * 映像と字幕と、上に重ねる文字。キーは呼ぶ側が受ける (ライブは局の切り替え、録画は送り戻し)。
 * **決定 (OK) は短押しと長押しを分けて `onCenter` に渡す** (録画・追っかけ・ライブとも長押しで番組の詳しいところ。情報キーの無いリモコンが多いので)。
 * 長押しは繰り返しの印でも、押したままの長さでも決まる (`CenterPress`)。
 *
 * 上に重ねたもの (ライブのメニュー・操作バー) を閉じたら、**必ず映像にキーを戻す** — 閉じたものにフォーカスがあったまま
 * 消えると、どこにもフォーカスが無くなりリモコンが効かなくなる。`active` の間は**映像そのものにフォーカスがあるか見張り、外れていたら
 * 取り戻す** (閉じたものが消える間・操作バーが勝手に消えたとき・端末によって遅れて合いが外れる場合も)。
 * 開いている間と、画面を離れるとき (一覧に戻る間に一覧がフォーカスを取るので、取り返さない) は `active = false`
 *
 * **字幕 (`captions`) は、下に重ねたもの (通知・`above` の操作バーやメニュー) の上へ逃がす** (`OverlayInsets`)。出したらすぐ上へ、閉じたら滑らかに戻す
 *
 * **`above` が操作バーを出している間 (`covered`) は、下の端の通知を出さない** (どちらも下の端のパネルなので重なる)。通知とエラーは
 * 操作バーのタイトルの2行目に添える (`LocalBarNotice`。`ControlBar` が読む)。操作バーの中で押したもの (CM 飛ばし・画質) の返事もそこに出る
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerFrame(
    player: ExoPlayer,
    overlay: CharSequence?,
    error: String?,
    /** 映像がキーを受けるか。上に重ねたもの (メニュー・操作バー) が開いている間は false。閉じたら映像に戻す */
    active: Boolean = true,
    onKey: (KeyEvent) -> Boolean,
    /** キーを離したとき (ライブの局の切り替えは、離してから待ってリクエストする) */
    onKeyUp: (KeyEvent) -> Unit = {},
    onCenter: (CenterPress.Action) -> Unit = {},
    /** 通知の下に出す進み (ライブの番組の進み)。null なら出さない */
    progress: Pair<Long, Long>? = null,
    /** denpa から受け取る字幕 (`rememberRawCaptions`・`rememberCaptionPages`)。通知の下に重ねる */
    captions: CaptionState,
    /** 実況のコメント (`rememberJikkyo`)。映像の上・字幕の下に流す */
    jikkyo: JikkyoState? = null,
    /** 映るまでの間に出す、何をしているか (ライブは「選局しています」)。ストリームが届いたら「映像を待っています」に替わる */
    busyLabel: String = "読み込んでいます",
    /** 繋ぎ直しの様子 (`rememberPlayer` の `recovery`)。繋ぎ直している間は前の映像を残して「繋ぎ直しています」 */
    recovery: Recovery,
    /** `above` が操作バーを出しているか。出している間は、通知を下の端に出さずに操作バーのタイトルに添える */
    covered: Boolean = false,
    above: @Composable BoxScope.() -> Unit = {},
) {
    val loading = rememberLoading(player)
    val focus = remember { FocusRequester() }
    /** 映像そのものにフォーカスがあるか */
    var focused by remember { mutableStateOf(false) }
    /** 下に重ねたものの高さ。字幕をその上へ逃がす */
    val insets = remember { OverlayInsets() }
    val inset = rememberCaptionInset(insets)
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        // 閉じたものが消えるのを1フレーム待ってからフォーカスし、その後も外れたら取り戻す
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
            // 開いていたものが閉じてフォーカスがどこにも無くなったら、映像に戻す
            .onFocusChanged { focused = it.isFocused }
            // 上に重ねたものが開いている間は受けない (そちらのキーがここまで上がってくるので)
            .centerPresses({ onCenter(CenterPress.Action.Short) }, { onCenter(CenterPress.Action.Long) }, enabled = active)
            .onKeyEvent { event ->
                if (!active) return@onKeyEvent false
                if (event.type == KeyEventType.KeyUp) onKeyUp(event)
                event.type == KeyEventType.KeyDown && onKey(event)
            }
            .focusable(),
    ) {
        // 字幕より下に置く (上に重ねた View の下にある字幕は、読み上げのツリーから外れる)
        AndroidView(
            factory = ::View,
            /*
             * **流している間は画面を点けたままにする** (スクリーンセーバーを出さない)。この画面の View に付けるので、
             * 画面を離れれば一緒に外れる (ComposeView に付けると、次の画面と取り合って消し合う)
             */
            update = { view ->
                // 繋ぎ直している間も (点けっぱなしで戻ってきたときに映っていてほしい)
                view.keepScreenOn = loading.awake || recovery.active
            },
            // 画面を離れたら必ず外す (外した View が窓の印を持ったまま残らないように)
            onReset = { view -> view.keepScreenOn = false },
            onRelease = { view -> view.keepScreenOn = false },
        )
        /*
         * 実況のコメントの一覧を出している間は、映像を左に寄せて 16:9 のまま縮め、右に一覧の列を置く (コメントと字幕は縮めた映像の中)。
         * 木の形は出していないときも同じにしておく (映像の面を作り直さない)
         */
        val listed = jikkyo?.listShown == true
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Box(if (listed) Modifier.fillMaxWidth().aspectRatio(16f / 9f) else Modifier.fillMaxSize()) {
                    PlayerSurface(player = player, modifier = Modifier.fillMaxSize())
                    // 重ねる順は 映像 < 実況のコメント < 字幕 < 操作バー (コメントは操作バーの影の下を通り、字幕のように持ち上げない)
                    jikkyo?.let { JikkyoLayer(it) }
                    CaptionLayer(captions, inset)
                }
            }
            if (listed && jikkyo != null) JikkyoListColumn(jikkyo, Modifier.width(JIKKYO_LIST_WIDTH))
        }
        if (error == null) LoadingVeil(loading, busyLabel, recovery)
        val notice = error ?: overlay
        CompositionLocalProvider(LocalOverlayInsets provides insets, LocalBarNotice provides notice.takeIf { covered }) {
            if (!covered) Notice(notice, if (error == null) progress else null)
            above()
        }
    }
}

/** 下の端に出す通知。操作バーと同じく、下の端に小さく (下から薄く暗くするだけ) */
@Composable
private fun BoxScope.Notice(text: CharSequence?, progress: Pair<Long, Long>?) {
    if (text == null) return
    BottomPanel(spacing = 4.dp) {
        TitleLines(text)
        progress?.let { (at, length) -> ProgressLine(at, length) }
    }
}

/** 操作バー (`ControlBar`) のタイトルの2行目に添える通知。`PlayerFrame` が操作バーを出している間 (`covered`) だけ渡す */
val LocalBarNotice = compositionLocalOf<CharSequence?> { null }

/** 実況のコメントの一覧の列の幅 */
private val JIKKYO_LIST_WIDTH = 280.dp

/** 映像にフォーカスがあるか見張る間 (ミリ秒) */
private const val FOCUS_WATCH_MS = 250L

/**
 * 映るまでの様子 (`rememberLoading`)。**ブラウザの denpa の幕 (`PlayerVeil`) にあたる。**
 *
 * - `busy` … 動かすつもりで読み込んでいる (選局・再リクエスト・シークの直後)
 * - `arrived` … ストリームが届きはじめた (中身の形式が分かった)。届くまでは「選局しています」、届いたら「映像を待っています」
 * - `pictured` … 一度でも映像を出したか。出していれば、次が映るまで**前の映像が画面に残っている** (Media3 は替えるときに面を消さない)
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
 * プレーヤーから映るまでの様子を取得する。`awake` は**動いている間は画面を点けたままにする**か (スクリーンセーバーを出さない)。
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
 * - 前の映像が残っている間は塗り潰さず、回るものは `SPINNER_DELAY_MS` 待ってから出す (局替えはたいてい1秒前後で終わるので、
 *   すぐ出すとちらつくだけ。テレビと同じく前の映像のまま止めておく)
 * - 前の映像を残すのは `HOLD_MOST_MS` まで。それを過ぎたら暗くする (選局に失敗したのに前の局が止まって見えるのがいちばん悪い)
 * - まだ何も映していなければ (開いた直後)、すぐ回るものを出す
 *
 * 回るものの下に、いま何をしているかを1行 (「選局しています」→「映像を待っています」。切れて繋ぎ直している間は「繋ぎ直しています」)
 */
@Composable
private fun BoxScope.LoadingVeil(state: LoadingState, label: String, recovery: Recovery) {
    val reconnecting = recovery.active
    if (!state.busy && !reconnecting) return
    // 繋ぎ直しは、切れたと見つけたときから数える (待っている間はプレーヤーが読み込んでいないので)
    val since = if (reconnecting) recovery.since else state.since
    var elapsed by remember(since) { mutableLongStateOf(0L) }
    LaunchedEffect(since) {
        while (true) {
            elapsed = SystemClock.uptimeMillis() - since
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
        Text(
            when {
                reconnecting -> "繋ぎ直しています"
                state.arrived -> "映像を待っています"
                else -> label
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
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

/** 前の映像を残したまま回るものを出すまで (ミリ秒)。ブラウザの denpa の `PlayerVeil` と同じ 1.5 秒 */
private const val SPINNER_DELAY_MS = 1_500L

/** 前の映像を残しておく上限 (ミリ秒)。ブラウザの denpa の `HOLD_MOST` と同じ 6 秒 */
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

/**
 * 何秒かだけ出して消える文字 (`rememberFlash`)。放送から来た字 (番組名・局名) は `appendBroadcast` で印を付けた AnnotatedString で渡す (`TitleLines`)。
 * `val (overlay, flash) = …` で、いま出している文と出す関数を受けられる
 */
@Stable
class Flash internal constructor() {
    /** いま出している文 (無ければ null) */
    var text by mutableStateOf<CharSequence?>(null)
        private set
    internal var shownAt by mutableLongStateOf(0L)
        private set

    /** 出す (4 秒で消える) */
    val show: (CharSequence) -> Unit = { value ->
        text = value
        shownAt = System.nanoTime()
    }

    /** すぐ消す。操作バーを開いたとき (開く前の通知 (開いてすぐの番組名など) を、操作バーのタイトルに持ち込まない) */
    fun clear() {
        text = null
    }

    operator fun component1(): CharSequence? = text
    operator fun component2(): (CharSequence) -> Unit = show
}

@Composable
fun rememberFlash(): Flash {
    val flash = remember { Flash() }
    LaunchedEffect(flash.shownAt) {
        if (flash.shownAt == 0L) return@LaunchedEffect
        delay(4_000)
        flash.clear()
    }
    return flash
}

/**
 * `rememberPlayer` が返すもの。`val (player, error) = …` で受けられる。`dualMono` はデュアルモノの配り直し (`rememberTracks` に渡す)、
 * `recovery` は繋ぎ直しの様子 (`PlayerFrame` に渡す。リクエストし直すたびに `requested` を呼ぶ)。
 * `codec` は最後に開いたストリームの形式 (応答ヘッダ `X-Denpa-Codec`)。まだ開いていない・ヘッダが無ければ null。
 * 新しいストリームの応答が届くまでは前のストリームの形式のまま (前の映像が残っている間は、その形式で数える)
 */
data class PlayerHandle(
    val player: ExoPlayer,
    val error: String?,
    val dualMono: DualMonoProcessor,
    val recovery: Recovery,
    val codec: LiveQuality?,
)

/**
 * ExoPlayer を画面の寿命に合わせる。エラーは文にして返す。
 * `clock` は元の TS の字幕の時計 (`rememberRawCaptions` と同じものを渡す)。応答ヘッダの形式もここから受け取る (`TsClock.onCodec`)。
 *
 * `reconnect` の決まりで、**切れた・止まったら繋ぎ直す** (`Recovery`)。繋ぎ直している間はエラーを出さず、前の映像を残して幕が
 * 「繋ぎ直しています」を出す。諦めたとき (直らないもの・回数を使い切った) だけエラーを返す。
 *
 * `buffering` が録画でなければ (ライブ・追っかけ)、**溜め方は応答ヘッダの形式で替える** (MPEG-2 なら `Buffering.LowLatency`。`BufferingControl`)。
 * プレーヤーは作り直さない。
 *
 * `unplayable` を渡すと、**端末の側で再生できなかった形式**を知らせる: デコーダを作れない・形式に対応していない
 * (`Reconnect.deviceCannotPlay`) か、データが溜まりはじめてから 15 秒たっても最初のフレームが出ないとき (`FirstFrameWatch`)。形式は `codec` (`fixedCodec` を渡せばそれ)。
 * 呼ぶ側がその形式を外してリクエストし直すなら true を返す (繋ぎ直しもエラーもしない)。false ならいつもどおり
 */
@OptIn(UnstableApi::class)
@Composable
fun rememberPlayer(
    repo: Repository,
    buffering: Buffering,
    onUnauthorized: () -> Unit,
    clock: TsClock,
    reconnect: ReconnectPlan,
    unplayable: ((ExoPlayer, LiveQuality) -> Boolean)? = null,
    fixedCodec: LiveQuality? = null,
): PlayerHandle {
    val context = LocalContext.current
    val dualMono = remember(buffering) { DualMonoProcessor() }
    val loadControl = remember(buffering) { BufferingControl(buffering) }
    val player = remember(buffering) { buildPlayer(context, repo.app.engineHttp, repo.token, loadControl, clock, dualMono) }
    val scope = rememberCoroutineScope()
    val recovery = remember(player) { Recovery(player, scope, reconnect) }
    recovery.plan = reconnect
    val unauthorized by rememberUpdatedState(onUnauthorized)
    val unplaying by rememberUpdatedState(unplayable)
    val fixed by rememberUpdatedState(fixedCodec)
    var error by remember { mutableStateOf<String?>(null) }
    var codec by remember { mutableStateOf<LiveQuality?>(null) }
    /** 最後の応答の形式と、それが届いたとき (uptime ミリ秒)。読み込みのスレッドが書く */
    val seen = remember(player) { SeenCodec() }
    DisposableEffect(clock, player) {
        val main = Handler(player.applicationLooper)
        clock.onCodec = { header ->
            seen.codec = header
            seen.at = SystemClock.uptimeMillis()
            if (buffering != Buffering.Recording) loadControl.buffering = header.buffering
            main.post { codec = header }
        }
        onDispose { clock.onCodec = null }
    }
    /** 端末の側で再生できなかった形式を、画面に渡す。受け取ってリクエストし直すなら true */
    fun cannotPlay(reason: String): Boolean {
        val handler = unplaying ?: return false
        val failed = fixed ?: seen.codec ?: return false
        if (!handler(player, failed)) return false
        Log.i(Recovery.TAG, "${failed.codec} を外してリクエストし直します (${recovery.plan.label}): $reason")
        recovery.cancel()
        error = null
        return true
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                val http = e.cause as? HttpDataSource.InvalidResponseCodeException
                val code = http?.responseCode
                if (Reconnect.deviceCannotPlay(e.errorCode) && cannotPlay("error=${e.errorCodeName}")) return
                // 変換の枠が埋まって断られた (503 の言い分で見分ける)。何度かだけリクエストし直し、諦めたら「サーバが混んでいます」
                val busy = isBusyAnswer(code, http?.responseBody)
                val verdict = Reconnect.verdict(e.errorCode, code, busy)
                // アクセストークンが外された・期限切れ。繋ぐ画面へ
                if (verdict == Reconnect.Verdict.Unauthorized) unauthorized()
                val reason = "error=${e.errorCodeName}" + (code?.let { " HTTP $it" } ?: "") + (if (busy) " 変換の枠が埋まっている" else "") +
                    (e.cause?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: "") +
                    " 映して ${recovery.playedSeconds()} 秒"
                if (verdict != Reconnect.Verdict.Unauthorized && recovery.retry(reason, verdict)) {
                    error = null
                    return
                }
                error = when {
                    e.cause is EmptyStreamException -> EMPTY_STREAM_MESSAGE
                    // 短く。勧め (MPEG-2) は画質の分かる画面が足す (`withBusyAdvice`)
                    busy -> BUSY_MESSAGE
                    // denpa が playable の中から選べなかった
                    code == 406 -> NO_PLAYABLE_MESSAGE
                    else -> {
                        val type = (e.cause as? HttpDataSource.InvalidContentTypeException)?.contentType
                        PLAYBACK_ERROR_PREFIX + playbackMessage(e.errorCode, e.errorCodeName, code, type)
                    }
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED || !recovery.plan.endedIsLost) return
                // 映したあとに終わったのは denpa の入れ替え (続けて来たら待ちを伸ばす)。映る前に終わったのは、エンコードを断られたのかもしれない (何度か)
                if (recovery.pictured) recovery.restarted()
                val verdict = if (recovery.pictured) Reconnect.Verdict.Retry else Reconnect.Verdict.RetryFew
                if (!recovery.retry("ended ストリームが終わった (映して ${recovery.playedSeconds()} 秒)", verdict)) error = EMPTY_STREAM_MESSAGE
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = recovery.onPlayWhenReady(playWhenReady)
            override fun onRenderedFirstFrame() = recovery.onPictured()
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) return
                error = null
                recovery.onPictured()
            }
        }
        player.addListener(listener)
        onDispose {
            recovery.cancel()
            player.removeListener(listener)
            player.release()
        }
    }
    // 止まったかの見張りと、最初のフレームが来ない形式の見張り
    LaunchedEffect(recovery) {
        val firstFrame = FirstFrameWatch()
        while (true) {
            delay(1_000)
            recovery.watch()
            val asked = recovery.requestedAt
            // 形式は、決めて開くもの (録画) はそれ、denpa が選ぶものはこのリクエストの応答ヘッダで分かったもの
            val known = fixed != null || (seen.codec != null && seen.at >= asked)
            val watching = unplaying != null && known && !recovery.pictured && !recovery.active && recovery.started && player.playWhenReady &&
                (player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY)
            // 溜まりはじめてから数える (回線が遅いだけなら溜まらないので、形式を外さない)
            val ahead = player.bufferedPosition - player.currentPosition
            if (firstFrame.check(SystemClock.uptimeMillis(), asked, watching, ahead)) {
                cannotPlay("溜まってから最初のフレームが ${FirstFrameWatch.FIRST_FRAME_MS / 1000} 秒来ない")
            }
        }
    }
    // 裏に回っている間は繋ぎ直さない
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, recovery) {
        recovery.started = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> recovery.started = false
                Lifecycle.Event.ON_START -> recovery.started = true
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return PlayerHandle(player, error, dualMono, recovery, codec)
}

/** 最後の応答の形式 (`X-Denpa-Codec`) と、それが届いたとき。読み込みのスレッドが書き、見張りが読む */
private class SeenCodec {
    @Volatile
    var codec: LiveQuality? = null

    @Volatile
    var at = 0L
}

/** 再生のエラーの頭 (画質の切り替えに失敗したときは外して理由だけ出す) */
const val PLAYBACK_ERROR_PREFIX = "再生できません: "

/**
 * `url` をリクエストして流しはじめる。`mime` は中身の形式。分からなければ null (自動の `codec=auto` は応答を見るまで分からないので、
 * Extractor が中身を見て決める)。`startMs` は頭からの位置 (録画の続き)。流しっぱなしのもの (ライブ・追っかけ) は渡さない
 */
fun ExoPlayer.load(url: String, mime: String?, startMs: Long? = null) {
    val item = MediaItem.Builder().setUri(url).setMimeType(mime).build()
    if (startMs == null) setMediaItem(item) else setMediaItem(item, startMs)
    prepare()
    playWhenReady = true
}
