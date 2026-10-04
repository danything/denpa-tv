package io.github.danything.denpatv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.HttpEngine
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
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
import io.github.danything.denpatv.data.ChapterMark
import androidx.media3.ui.compose.PlayerSurface
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/**
 * 溜め方。**ライブは少なく溜めて、放送に近いところで観る。**
 *
 * ExoPlayer の既定は 50 秒まで溜めて 1 秒溜まったら動き出す。録画にはそれでよいが、ライブでは
 * 溜めたぶんだけ放送から遅れる。denpa のライブは流しっぱなしの1本 (区切られた HLS ではない) なので、
 * Media3 の LiveConfiguration (目標の遅れ) は効かない。溜める量で決め、遅れたら追いつく (`catchUp`)
 */
enum class Buffering(val minMs: Int, val maxMs: Int, val startMs: Int, val afterRebufferMs: Int) {
    /** 録画。ExoPlayer の既定 */
    Recording(50_000, 50_000, 1_000, 2_000),
    /** ライブ (denpa が焼く H.264 / AV1)。焼き上がりが塊で届くので少しは溜める */
    Live(2_000, 8_000, 1_000, 1_500),
    /** 低遅延 (生の TS)。届いたそばから出す */
    LowLatency(500, 2_000, 250, 500),
}

/**
 * 映像を取る道。**Media3 のネットワーク スタックの頁の勧めどおり**: Android 14 からは OS の
 * HttpEngine (アプリで1つ)、それより前は DefaultHttpDataSource (OS の HttpURLConnection)。
 * どちらも `DefaultDataSource.Factory` で包む (http(s) 以外も同じ口で開けるように)。docs/libraries.md
 */
@OptIn(UnstableApi::class)
@SuppressLint("NewApi") // HttpEngine は hasHttpEngine() で確かめてから作る (DenpaApp.httpEngine)
fun dataSourceFactory(context: Context, engine: HttpEngine?): DataSource.Factory {
    val http: HttpDataSource.Factory = if (engine != null) {
        HttpEngineDataSource.Factory(engine, Executors.newSingleThreadExecutor())
            .setConnectionTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
    } else {
        DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
    }
    return DefaultDataSource.Factory(context, http)
}

private const val CONNECT_TIMEOUT_MS = 10_000
// ライブは流しっぱなしなので読みの時間切れは長めに (止まったら ExoPlayer が言う)
private const val READ_TIMEOUT_MS = 30_000

@OptIn(UnstableApi::class)
fun buildPlayer(context: Context, engine: HttpEngine?, buffering: Buffering): ExoPlayer =
    ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory(context, engine)))
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
 *
 * 字幕は `SubtitleView` (View) で出す。焼いたものの字幕は PGS (絵) で、Compose の部品はまだ絵の字幕を描けない
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerFrame(
    player: ExoPlayer,
    overlay: String?,
    error: String?,
    onKey: (KeyEvent) -> Boolean,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { if (it.type == KeyEventType.KeyDown) onKey(it) else false }
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
        val text = error ?: overlay
        if (text != null) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0xE6000000)) // 映像の上でも読めるように濃いめに
                    .padding(horizontal = 48.dp, vertical = 24.dp),
            ) {
                text.lines().forEachIndexed { index, line ->
                    Text(
                        line,
                        style = if (index == 0) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                    )
                }
            }
        }
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

/** ExoPlayer を画面の寿命に合わせる。エラーは文にして返す */
@Composable
fun rememberPlayer(repo: Repository, buffering: Buffering): Pair<ExoPlayer, String?> {
    val context = LocalContext.current
    val player = remember(buffering) { buildPlayer(context, repo.app.httpEngine, buffering) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
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
    return player to error
}

fun MediaItem.Builder.uri(url: String, mime: String): MediaItem = setUri(url).setMimeType(mime).build()

