package io.github.danything.denpatv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.media3.common.MediaItem
import io.github.danything.denpatv.data.LiveQuality
import io.github.danything.denpatv.data.Service
import io.github.danything.denpatv.data.Unauthorized
import io.github.danything.denpatv.data.neighbor
import io.github.danything.denpatv.data.number
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ライブ。**開いたらすぐ、最後に観ていた局を映す** (初めてなら局の一覧の先頭)。denpa の画面のライブと同じ。
 *
 * - 上下 / チャンネル送りで隣の局へ
 * - 左・決定で局の一覧を開く (種別で切り替え、いま放送中の番組つき)。戻るで閉じる
 * - メニューで操作の帯を開き、**画質**を変える (低遅延 MPEG-2 / H.264 / AV1 のうち、この端末で解けるもの)。
 *   ブラウザの denpa のライブと同じく、すぐ切り替わってこの端末で覚える。既定は端末がハードで MPEG-2 を
 *   解ければ低遅延の生の TS
 * - 情報キーで、いまの局と番組を出す
 */
@Composable
fun LivePlayerScreen(repo: Repository, onUnauthorized: () -> Unit) {
    val saved by repo.app.settings.liveQuality.collectAsState(initial = LOADING_QUALITY)
    if (saved == LOADING_QUALITY) return
    val quality = remember(saved) { LiveQuality.choose(saved, repo.app.decoders) }
    val buffering = if (quality == LiveQuality.Raw) Buffering.LowLatency else Buffering.Live

    var services by remember { mutableStateOf(repo.services) }
    /**
     * 映している局。**番号ではなく局で持つ** — 1 分ごとの取り直しで一覧の並びや顔ぶれが変わると、
     * 番号だと隣の局を指してしまう。一覧から消えても、映しているものは止めない (知らせだけ出す)
     */
    var playing by remember { mutableStateOf<Service?>(null) }
    /** 局の一覧を読み終えたか (「読み込み中」と「局が無い」を分ける) */
    var ready by remember { mutableStateOf(false) }
    /** 映している局が一覧から消えたと知らせたか */
    var gone by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
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
        playing = services.firstOrNull { it.id == last } ?: services.firstOrNull()
        ready = true
    }
    LaunchedEffect(playing?.id, quality) {
        val service = playing ?: return@LaunchedEffect
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
            runCatching { repo.refreshServices() }.onSuccess {
                services = repo.services
                val current = playing ?: return@onSuccess
                val fresh = services.firstOrNull { it.id == current.id }
                when {
                    fresh != null -> { playing = fresh; gone = false }
                    // 知らせは消えたときに1度だけ (取り直しのたびに出さない)
                    !gone -> {
                        gone = true
                        flash("${current.name} は局の一覧から無くなりました (スキャンし直した?)。上下で別の局へ")
                    }
                }
            }
        }
    }
    BackHandler(enabled = panel || controls) { panel = false; controls = false }

    if (!ready) return Centered("読み込んでいます…")
    val current = playing ?: return Centered("局がありません")
    fun zap(step: Int) {
        neighbor(services, current.id, step)?.let { playing = it }
    }
    PlayerFrame(
        player,
        overlay,
        error,
        active = !panel && !controls,
        onKey = { event ->
            when (event.key) {
                Key.DirectionUp, Key.ChannelUp -> { zap(-1); true }
                Key.DirectionDown, Key.ChannelDown -> { zap(1); true }
                Key.DirectionLeft, Key.DirectionCenter, Key.Enter -> { panel = true; true }
                Key.Menu -> { controls = true; true }
                Key.Info -> { flash(describe(current, quality)); true }
                else -> false
            }
        },
    ) {
        if (controls) {
            ControlBar(
                describe(current, quality).lines().first(),
                listOf(
                    "画質" to LiveQuality.available(repo.app.decoders).map { choice ->
                        Control(choice.label, on = choice == quality) {
                            controls = false
                            scope.launch { repo.app.settings.setLiveQuality(choice) }
                        }
                    },
                ),
            )
        }
        if (panel) {
            ChannelPanel(repo, services, current) { picked ->
                playing = picked
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
