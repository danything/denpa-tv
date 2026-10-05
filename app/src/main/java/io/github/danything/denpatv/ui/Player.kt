package io.github.danything.denpatv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.HttpEngine
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
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
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpEngineDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.ui.SubtitleView
import io.github.danything.denpatv.data.CenterPress
import io.github.danything.denpatv.data.ChapterMark
import io.github.danything.denpatv.data.Http
import io.github.danything.denpatv.data.LongPressGuard
import io.github.danything.denpatv.data.LiveQuality
import androidx.media3.ui.compose.PlayerSurface
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.util.concurrent.Executor

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
@SuppressLint("NewApi") // HttpEngine は hasHttpEngine() で確かめてから作る (DenpaApp.httpEngine)
fun dataSourceFactory(context: Context, engine: HttpEngine?, executor: Executor, token: String?): DataSource.Factory {
    val http: HttpDataSource.Factory = if (engine != null) {
        HttpEngineDataSource.Factory(engine, executor)
            .setConnectionTimeoutMs(Http.CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(Http.READ_TIMEOUT_MS)
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
    engine: HttpEngine?,
    executor: Executor,
    token: String?,
    buffering: Buffering,
    clock: TsClock,
    dualMono: DualMonoProcessor,
): ExoPlayer =
    ExoPlayer.Builder(context, DualMonoRenderersFactory(context, dualMono))
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory(context, engine, executor, token), clock))
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
 * **決定 (OK) は短押しと長押しを分けて `onCenter` に渡す** (長押しでメニュー。Menu キーの無いリモコンが多いので)。
 *
 * 上に重ねたもの (局の一覧・操作の帯) を閉じたら、**必ず映像にキーを戻す** — 閉じたものに合っていたまま
 * 消えると、どこにも合わずリモコンが効かなくなる。`active` の間は**映像そのものに合っているか見張り、外れていたら
 * 取り戻す** (閉じたものが消える間・帯が勝手に消えたとき・端末によって遅れて合いが外れる場合も)。
 * 開いている間と、画面を離れるとき (一覧に戻る間に一覧が合いを取るので、取り返さない) は `active = false`
 *
 * 字幕は `SubtitleView` (View) で出す。焼いたものの字幕は PGS (絵) で、Compose の部品はまだ絵の字幕を描けない
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerFrame(
    player: ExoPlayer,
    overlay: String?,
    error: String?,
    /** 映像がキーを受けるか。上に重ねたもの (局の一覧) が開いている間は false。閉じたら映像に戻す */
    active: Boolean = true,
    onKey: (KeyEvent) -> Boolean,
    onCenter: (CenterPress.Action) -> Unit = {},
    /** 知らせの下に出す進み (ライブの番組の進み)。null なら出さない */
    progress: Pair<Long, Long>? = null,
    /** 生の TS の字幕 (`rememberRawCaptions`)。焼いた映像の字幕の上、知らせの下に重ねる */
    captions: RawCaptionState? = null,
    above: @Composable BoxScope.() -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    val center = remember { CenterPress() }
    /** 映像そのものに合っているか */
    var focused by remember { mutableStateOf(false) }
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
                event.type == KeyEventType.KeyDown && onKey(event)
            }
            .focusable(),
    ) {
        PlayerSurface(player = player, modifier = Modifier.fillMaxSize())
        AndroidView(
            factory = { context ->
                SubtitleView(context).also { view ->
                    player.addListener(object : Player.Listener {
                        override fun onCues(cueGroup: CueGroup) = view.setCues(cueGroup.cues)
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        captions?.let { RawCaptionLayer(it) }
        val text = error ?: overlay
        if (text != null) {
            // 操作の帯と同じく、下の端に小さく (下から薄く暗くするだけ)
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(SCRIM)
                    .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                text.lines().forEachIndexed { index, line ->
                    Text(
                        line,
                        style = if (index == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                        color = if (index == 0) Color.White else Color(0xFFD0D0D0),
                    )
                }
                if (error == null) progress?.let { (at, length) -> ProgressLine(at, length) }
            }
        }
        above()
    }
}

/** 映像に合っているか見張る間 (ミリ秒) */
private const val FOCUS_WATCH_MS = 250L

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
    val player = remember(buffering) { buildPlayer(context, repo.app.httpEngine, repo.app.httpExecutor, repo.token, buffering, clock, dualMono) }
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

