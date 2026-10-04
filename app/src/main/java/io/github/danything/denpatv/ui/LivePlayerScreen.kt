package io.github.danything.denpatv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import io.github.danything.denpatv.R
import io.github.danything.denpatv.data.CenterPress
import io.github.danything.denpatv.data.LiveCommand
import io.github.danything.denpatv.data.liveCommand
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
 * - 上下 (チャンネル送りも) で前・次の局 (同じものを流しているサブチャンネルは飛ばす)。替えたら局・番組・番組の進みを
 *   数秒だけ下に出す。いちばん押すのは局替えなので十字キーの上下に (キーの割り当ては data/Remote.kt)
 * - 決定 (と左) で局の一覧を開く (種別で切り替え、いま放送中の番組つき)。戻るで閉じる
 * - **決定の長押し** (か Menu キー) で操作の列: **画質 (コーデック)** (H.264 / AV1 / MPEG-2 のうち、この端末で解けるもの) と
 *   情報。ブラウザの denpa のライブと同じく、すぐ切り替わってこの端末で覚える。既定は端末がハードで MPEG-2 を
 *   解ければ生の TS (いちばん遅れが少ない)。5 秒触らなければ閉じる
 * - 情報キーで、いまの局と番組を出す
 * - 何も開いていないときの戻るは、メニューの画面へ
 */
@Composable
fun LivePlayerScreen(repo: Repository, onLeave: () -> Unit, onUnauthorized: () -> Unit) {
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
    /** 操作の列で最後にキーを押したとき (5 秒触らなければ閉じる) */
    var touched by remember { mutableLongStateOf(0L) }
    LaunchedEffect(controls, touched) {
        if (!controls) return@LaunchedEffect
        delay(5_000)
        controls = false
    }
    /** 長押しでメニューが開くと知らせたか (開いて最初の1回だけ) */
    var hinted by remember { mutableStateOf(false) }
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
        flash(describe(service, quality) + if (hinted) "" else "\n$LIVE_HINT")
        hinted = true
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
    /** メニューの画面へ戻るところ (映像に合いを取り返させない。戻った先が合いを取るので) */
    var leaving by remember { mutableStateOf(false) }
    BackHandler(enabled = !panel && !controls) { leaving = true; onLeave() }

    if (!ready) return Centered("読み込んでいます…")
    val current = playing ?: return Centered("局がありません")
    fun zap(step: Int) {
        neighbor(services, current.id, step)?.let { playing = it }
    }
    PlayerFrame(
        player,
        overlay,
        error,
        active = !panel && !controls && !leaving,
        onKey = { event ->
            when (liveCommand(event.nativeKeyEvent.keyCode)) {
                LiveCommand.PreviousChannel -> { zap(-1); true }
                LiveCommand.NextChannel -> { zap(1); true }
                LiveCommand.ChannelList -> { panel = true; true }
                LiveCommand.Actions -> { controls = true; true }
                LiveCommand.Info -> { flash(describe(current, quality) + "\n$LIVE_HINT"); true }
                null -> false
            }
        },
        onCenter = { press ->
            when (press) {
                CenterPress.Action.Short -> panel = true
                CenterPress.Action.Long -> controls = true
            }
        },
        progress = current.now?.let { System.currentTimeMillis() - it.startAt to it.endAt - it.startAt },
    ) {
        if (controls) {
            val now = current.now
            ControlBar(
                describe(current, quality),
                groups = listOf(
                    "画質 (コーデック)" to LiveQuality.available(repo.app.decoders).map { choice ->
                        Control(choice.label, on = choice == quality, icon = if (choice == quality) R.drawable.ic_quality else null) {
                            controls = false
                            scope.launch { repo.app.settings.setLiveQuality(choice) }
                        }
                    },
                    "" to listOf(
                        Control("情報", icon = R.drawable.ic_info) {
                            controls = false
                            flash(describe(current, quality) + "\n$LIVE_HINT")
                        },
                    ),
                ),
                onActivity = { touched = System.nanoTime() },
                // いまの番組の進み
                header = now?.let { program ->
                    { ProgressLine(System.currentTimeMillis() - program.startAt, program.endAt - program.startAt) }
                },
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

private const val LIVE_HINT = "決定で局の一覧・長押しでメニュー (画質)"

private const val LOADING_QUALITY = "\u0000loading"
