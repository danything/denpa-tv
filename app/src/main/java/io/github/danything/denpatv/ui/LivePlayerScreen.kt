package io.github.danything.denpatv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import io.github.danything.denpatv.data.liveCodec

/**
 * ライブ。`api/services/<id>/live?codec=` を流しっぱなしで開く (fragmented MP4)。
 * 上下キー (とリモコンのチャンネル送り) で隣の局へ
 */
@Composable
fun LivePlayerScreen(repo: Repository, serviceId: Long) {
    val services = repo.services
    var index by remember { mutableIntStateOf(services.indexOfFirst { it.id == serviceId }.coerceAtLeast(0)) }
    val (player, error) = rememberPlayer(repo)
    val (overlay, flash) = rememberFlash()
    val codec = remember { liveCodec(repo.app.decoders) }

    LaunchedEffect(index) {
        val service = services.getOrNull(index) ?: return@LaunchedEffect
        val url = repo.url("${service.live}?codec=$codec") ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.Builder().uri(url, MimeTypes.VIDEO_MP4))
        player.prepare()
        player.playWhenReady = true
        flash(listOfNotNull(service.remoteControlKey?.toString(), service.name, codec.uppercase()).joinToString("  "))
    }

    if (services.isEmpty()) {
        Centered("局がありません")
        return
    }
    PlayerFrame(player, overlay, error) { event ->
        when {
            event.key in listOf(Key.DirectionUp, Key.ChannelUp) -> { index = (index - 1 + services.size) % services.size; true }
            event.key in listOf(Key.DirectionDown, Key.ChannelDown) -> { index = (index + 1) % services.size; true }
            event.key in listOf(Key.DirectionCenter, Key.Enter, Key.Info) -> {
                services.getOrNull(index)?.let { flash(it.name) }
                true
            }
            else -> false
        }
    }
}
