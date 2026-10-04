package io.github.danything.denpatv.ui

import androidx.activity.compose.BackHandler
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
import io.github.danything.denpatv.data.Unauthorized
import io.github.danything.denpatv.data.number
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * ライブ。**開いたらすぐ、最後に観ていた局を映す** (初めてなら局の一覧の先頭)。denpa の画面のライブと同じ。
 *
 * - 上下 / チャンネル送りで隣の局へ
 * - 左・決定・メニューで局の一覧を開く (種別で切り替え、いま放送中の番組つき)。戻るで閉じる
 * - 情報キーで、いまの局と番組を出す
 *
 * 画質は設定で選んだもの (既定は端末がハードで MPEG-2 を解ければ低遅延の生の TS)
 */
@Composable
fun LivePlayerScreen(repo: Repository, onUnauthorized: () -> Unit) {
    val saved by repo.app.settings.liveQuality.collectAsState(initial = LOADING_QUALITY)
    if (saved == LOADING_QUALITY) return
    val quality = remember(saved) { LiveQuality.choose(saved, repo.app.decoders) }
    val buffering = if (quality == LiveQuality.Raw) Buffering.LowLatency else Buffering.Live

    var services by remember { mutableStateOf(repo.services) }
    /** -1 は、まだ決めていない (覚えている局を読むまで) */
    var index by remember { mutableIntStateOf(-1) }
    var panel by remember { mutableStateOf(false) }
    val (player, error) = rememberPlayer(repo, buffering, onUnauthorized)
    val (overlay, flash) = rememberFlash()
    CatchUp(player, buffering)

    LaunchedEffect(Unit) {
        if (services.isEmpty()) {
            try {
                repo.refreshServices()
            } catch (_: Unauthorized) {
                return@LaunchedEffect onUnauthorized()
            } catch (_: Exception) {
            }
            services = repo.services
        }
        val last = repo.app.settings.lastService.first()
        index = services.indexOfFirst { it.id == last }.takeIf { it >= 0 } ?: 0
    }
    LaunchedEffect(index, quality) {
        val service = services.getOrNull(index) ?: return@LaunchedEffect
        val url = repo.url("${service.live}?codec=${quality.codec}") ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.Builder().uri(url, quality.mime))
        player.prepare()
        player.playWhenReady = true
        flash(describe(service, quality))
        repo.app.settings.setLastService(service.id)
    }
    // いま放送中の番組は変わっていく。1分ごとに取り直す (古い denpa では now が来ないだけ)
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            runCatching { repo.refreshServices() }.onSuccess { services = repo.services }
        }
    }
    BackHandler(enabled = panel) { panel = false }

    if (index < 0) return Centered("読み込んでいます…")
    if (services.isEmpty()) return Centered("局がありません")
    val current = services.getOrNull(index)
    PlayerFrame(
        player,
        overlay,
        error,
        active = !panel,
        onKey = { event ->
            when (event.key) {
                Key.DirectionUp, Key.ChannelUp -> { index = (index - 1 + services.size) % services.size; true }
                Key.DirectionDown, Key.ChannelDown -> { index = (index + 1) % services.size; true }
                Key.DirectionLeft, Key.DirectionCenter, Key.Enter, Key.Menu -> { panel = true; true }
                Key.Info -> { current?.let { flash(describe(it, quality)) }; true }
                else -> false
            }
        },
    ) {
        if (panel) {
            ChannelPanel(repo, services, current) { picked ->
                index = services.indexOf(picked)
                panel = false
            }
        }
    }
}

/** 1行目に局、2行目にいま放送中の番組と残り */
private fun describe(service: Service, quality: LiveQuality): String {
    val head = listOfNotNull(service.number?.toString(), service.name, quality.label).joinToString("  ")
    val now = service.now ?: return head
    val left = "あと${now.remainingMinutes(System.currentTimeMillis())}分"
    // サブチャンネルは番組名が空で来る
    return if (now.title.isBlank()) "$head\n$left" else "$head\n${now.title}  $left"
}

private const val LOADING_QUALITY = "\u0000loading"
