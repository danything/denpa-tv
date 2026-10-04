package io.github.danything.denpatv.ui

import android.content.Context
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
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.PlayerSurface
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

/** API と同じ OkHttp で流す (接続の溜めを分け合う) */
@OptIn(UnstableApi::class)
fun buildPlayer(context: Context, http: OkHttpClient): ExoPlayer =
    ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(OkHttpDataSource.Factory(http)))
        .build()

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
                    .background(Color(0xB3000000))
                    .padding(horizontal = 48.dp, vertical = 24.dp),
            ) {
                Text(text, style = MaterialTheme.typography.titleLarge, color = Color.White)
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
fun rememberPlayer(repo: Repository): Pair<ExoPlayer, String?> {
    val context = LocalContext.current
    val player = remember { buildPlayer(context, repo.app.http) }
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

