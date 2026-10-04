package io.github.danything.denpatv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.media3.common.MediaItem
import io.github.danything.denpatv.data.LiveQuality
import io.github.danything.denpatv.data.Service
import kotlinx.coroutines.delay

/**
 * ライブ。`api/services/<id>/live?codec=` を流しっぱなしで開く。画質は設定で選んだもの
 * (既定は端末がハードで MPEG-2 を解ければ低遅延の生の TS)。
 * 上下キー (とリモコンのチャンネル送り) で隣の局へ。決定でいま放送中の番組を出す
 */
@Composable
fun LivePlayerScreen(repo: Repository, serviceId: Long) {
    val saved by repo.app.settings.liveQuality.collectAsState(initial = LOADING_QUALITY)
    if (saved == LOADING_QUALITY) return
    val quality = remember(saved) { LiveQuality.choose(saved, repo.app.decoders) }
    val buffering = if (quality == LiveQuality.Raw) Buffering.LowLatency else Buffering.Live

    var services by remember { mutableStateOf(repo.services) }
    var index by remember { mutableIntStateOf(services.indexOfFirst { it.id == serviceId }.coerceAtLeast(0)) }
    val (player, error) = rememberPlayer(repo, buffering)
    val (overlay, flash) = rememberFlash()
    CatchUp(player, buffering)

    LaunchedEffect(index, quality) {
        val service = services.getOrNull(index) ?: return@LaunchedEffect
        val url = repo.url("${service.live}?codec=${quality.codec}") ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.Builder().uri(url, quality.mime))
        player.prepare()
        player.playWhenReady = true
        flash(describe(service, quality))
    }
    // いま放送中の番組は変わっていく。1分ごとに取り直す (古い denpa では now が来ないだけ)
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            runCatching { repo.refreshServices() }.onSuccess { services = repo.services }
        }
    }

    if (services.isEmpty()) {
        Centered("局がありません")
        return
    }
    PlayerFrame(player, overlay, error) { event ->
        when (event.key) {
            Key.DirectionUp, Key.ChannelUp -> { index = (index - 1 + services.size) % services.size; true }
            Key.DirectionDown, Key.ChannelDown -> { index = (index + 1) % services.size; true }
            Key.DirectionCenter, Key.Enter, Key.Info -> {
                services.getOrNull(index)?.let { flash(describe(it, quality)) }
                true
            }
            else -> false
        }
    }
}

/** 1行目に局、2行目にいま放送中の番組と残り */
private fun describe(service: Service, quality: LiveQuality): String {
    val head = listOfNotNull(service.remoteControlKey?.toString(), service.name, quality.label).joinToString("  ")
    val now = service.now ?: return head
    val left = "あと${now.remainingMinutes(System.currentTimeMillis())}分"
    // サブチャンネルは番組名が空で来る
    return if (now.title.isBlank()) "$head\n$left" else "$head\n${now.title}  $left"
}

private const val LOADING_QUALITY = "\u0000loading"
