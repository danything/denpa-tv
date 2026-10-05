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
import io.github.danything.denpatv.data.CaptionPaths
import io.github.danything.denpatv.data.CenterPress
import io.github.danything.denpatv.data.DenpaEvent
import io.github.danything.denpatv.data.LiveCommand
import io.github.danything.denpatv.data.liveCommand
import io.github.danything.denpatv.data.LiveQuality
import io.github.danything.denpatv.data.Service
import io.github.danything.denpatv.data.Unauthorized
import io.github.danything.denpatv.data.audioQuery
import io.github.danything.denpatv.data.neighbor
import io.github.danything.denpatv.data.number
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ライブ。**開いたらすぐ、最後に観ていた局を映す** (初めてなら局の一覧の先頭)。denpa の画面のライブと同じ。
 *
 * - 上下 (チャンネル送りも) で前・次の局 (同じものを流しているサブチャンネルは飛ばす)。替えたら局・番組・番組の進みを
 *   数秒だけ下に出す。いちばん押すのは局替えなので十字キーの上下に (キーの割り当ては data/Remote.kt)
 * - 決定 (と左) で局の一覧を開く (種別で切り替え、いま放送中の番組つき)。戻るで閉じる
 * - **決定の長押し** (か Menu キー) で操作の列: **画質 (コーデック)** (H.264 / AV1 / MPEG-2 のうち、この端末で解けるもの)、
 *   字幕・音声 (あれば)、情報。ブラウザの denpa のライブと同じく、すぐ切り替わってこの端末で覚える。既定は端末がハードで MPEG-2 を
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
    val clock = remember { TsClock() }
    val (player, error, dualMono) = rememberPlayer(repo, buffering, onUnauthorized, clock)
    val (overlay, flash) = rememberFlash()
    // 生の TS の字幕は denpa が描いた絵を別の口で受け取る (焼いたものは映像に入っている)
    val captions = rememberRawCaptions(
        repo,
        player,
        clock,
        path = playing?.takeIf { quality == LiveQuality.Raw }?.let { CaptionPaths.live(it.live) },
        generation = playing?.id,
        onUnauthorized = onUnauthorized,
    )
    // デュアルモノの主・副は、生の TS のときだけ配り直す (焼いたライブは denpa が選んだ1つだけを焼く。下の `baked`)
    val tracks = rememberTracks(
        repo,
        player,
        flash,
        captions,
        dualMono,
        denpaAudios = playing?.takeIf { quality == LiveQuality.Raw }?.now?.audios.orEmpty(),
    )
    /*
     * 焼いたライブの音声は denpa に頼んで選ぶ (`?audio=<id>`)。変われば頼み直す。
     * **番組が替わって音声の並びが変わったときも頼み直す** (二カ国語の映画が終わってステレオに戻るなど)。denpa は焼きはじめに
     * 選んだ音声のまま焼き続けるので、頼み直さないと、ステレオの番組の片側だけを両耳に配ったままになる。並びが同じなら頼み直さない
     */
    val baked = rememberBakedAudio(
        repo,
        audios = playing?.takeIf { quality != LiveQuality.Raw }?.now?.audios.orEmpty(),
        key = playing?.id,
        onChange = flash,
    )
    CatchUp(player, buffering)

    LaunchedEffect(Unit) {
        if (services.isEmpty() || repo.servicesStale) {
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
    /** 局・画質を最後に出したもの。音声だけを替えて頼み直したときは出し直さない (「音声 …」の知らせを消さない) */
    var shown by remember { mutableStateOf<Pair<Long, LiveQuality>?>(null) }
    LaunchedEffect(playing?.id, quality, baked.ready, baked.audio?.id) {
        val service = playing ?: return@LaunchedEffect
        if (!baked.ready) return@LaunchedEffect
        val url = repo.url("${service.live}?codec=${quality.codec}${audioQuery(baked.audio)}") ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.Builder().uri(url, quality.mime))
        player.prepare()
        player.playWhenReady = true
        if (shown == service.id to quality) return@LaunchedEffect
        shown = service.id to quality
        flash(describe(service, quality) + if (hinted) "" else "\n$LIVE_HINT")
        hinted = true
        repo.app.settings.setLastService(service.id)
    }
    /** 局を取り直して、映している局を新しいものに替える (取れなければそのまま) */
    suspend fun refresh() {
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
    // いま放送中の番組は変わっていく。1分ごとに取り直す (古い denpa では now が来ないだけ)
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            refresh()
        }
    }
    // 番組が終わったら、すぐ取り直す (次の番組の音声の構成 (デュアルモノか) と進みを、1分ごとの取り直しを待たずに替える)
    LaunchedEffect(playing?.now?.endAt) {
        val end = playing?.now?.endAt ?: return@LaunchedEffect
        delay((end - System.currentTimeMillis()).coerceAtLeast(0) + PROGRAM_END_GRACE_MS)
        if (ready) refresh()
    }
    // denpa の知らせ (局・番組表が変わった、繋ぎ直した) でも取り直す。番組表は1局集めるたびに来るので、まとめて1回 (1 秒待つ)
    LaunchedEffect(Unit) {
        val changed = setOf(DenpaEvent.Opened, DenpaEvent.Changed("services"), DenpaEvent.Changed("programs"))
        repo.events.filter { it in changed }.collectLatest {
            delay(1_000)
            if (ready) refresh()
        }
    }
    BackHandler(enabled = panel || controls) { panel = false; controls = false }
    /** メニューの画面へ戻るところ (映像に合いを取り返させない。戻った先が合いを取るので) */
    var leaving by remember { mutableStateOf(false) }
    // 戻るを続けて押しても、1つだけ戻る (2回目は受けない)
    BackHandler(enabled = !panel && !controls && !leaving) { leaving = true; onLeave() }

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
        captions = captions,
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
                    "" to tracks.controls() + baked.controls() + listOf(
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

/** 番組の終わりから取り直すまでの間 (ミリ秒)。denpa の時計とのずれのぶん */
private const val PROGRAM_END_GRACE_MS = 3_000L
