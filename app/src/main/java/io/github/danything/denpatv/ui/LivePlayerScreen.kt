package io.github.danything.denpatv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.media3.common.MediaItem
import io.github.danything.denpatv.R
import io.github.danything.denpatv.data.CaptionPaths
import io.github.danything.denpatv.data.DenpaEvent
import io.github.danything.denpatv.data.LiveCommand
import io.github.danything.denpatv.data.liveCommand
import io.github.danything.denpatv.data.LiveQuality
import io.github.danything.denpatv.data.NowProgram
import io.github.danything.denpatv.data.RecordResult
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
 * - **ほかのキー (決定・左右・決定の長押し・Menu) はどれもメニュー** (YouTube のアプリと同じく、何を押しても十字キーで辿れるものが出る)。
 *   下の端に、ブラウザの denpa のライブの操作列にあたる**操作の列** (画質・字幕・音声・録画) と、その下に**局の列**
 *   (地上波 / BS / CS ごとの列。ブラウザのタブにあたる) が覗く。下キーで局の列に入り、左右で選んで決定で替える。
 *   画質はすぐ切り替わってこの端末で覚える (既定は端末がハードで MPEG-2 を解ければ生の TS)。**戻るで閉じる** (もう一度でメニューの画面へ)。
 *   8 秒触らなければ閉じる
 * - **録画** はいま観ている番組を denpa に予約する (ブラウザのライブの録画ボタンと同じ。何度押しても二重には録らない)
 * - 局を替えている間は前の局の絵を残し、長くかかったら (1.5 秒) 回るものと「選局しています」→「映像を待っています」を出す (PlayerFrame)
 * - アプリが裏に回ったら (ホーム・別のアプリ) 止めて denpa から降りる (チューナーを空ける)。戻ったら同じ局を映し直す
 * - 情報キーで、いまの局と番組を出す
 * - 何も開いていないときの戻るは、メニューの画面へ (「ライブ」に合う)
 */
@Composable
fun LivePlayerScreen(repo: Repository, onLeave: () -> Unit, onUnauthorized: () -> Unit) {
    val quality = rememberLiveQuality(repo) ?: return
    val buffering = quality.buffering

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
    /** メニュー (操作の列と局の列) を開いているか */
    var menu by remember { mutableStateOf(false) }
    /** メニューで最後にキーを押したとき (しばらく触らなければ閉じる) */
    var touched by remember { mutableLongStateOf(0L) }
    LaunchedEffect(menu, touched) {
        if (!menu) return@LaunchedEffect
        delay(MENU_IDLE_MS)
        menu = false
    }
    /** キーの手引きを出したか (開いて最初の1回だけ) */
    var hinted by remember { mutableStateOf(false) }
    /** 裏に回っている (ホーム・別のアプリ)。その間は流さない (`OnBackground`) */
    var background by remember { mutableStateOf(false) }
    /** 裏から戻った回数。戻るたびに同じ局を頼み直す */
    var returns by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val clock = remember { TsClock() }
    val (player, error, dualMono) = rememberPlayer(repo, buffering, onUnauthorized, clock)
    val (overlay, flash) = rememberFlash()
    // 生の TS の字幕は denpa が描いた絵を別の口で受け取る (焼いたものは映像に入っている)
    val captions = rememberRawCaptions(
        repo,
        player,
        clock,
        // 裏に回っている間は字幕の流れも閉じる
        path = playing?.takeIf { quality == LiveQuality.Raw && !background }?.let { CaptionPaths.live(it.live) },
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
    LaunchedEffect(playing?.id, quality, baked.ready, baked.audio?.id, returns) {
        val service = playing ?: return@LaunchedEffect
        if (!baked.ready || background) return@LaunchedEffect
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
    /*
     * **裏に回ったら止めて、denpa から降りる** (流れを閉じる。ほかに観ている人がいなければ denpa がチューナーを空ける)。
     * 流しっぱなしにすると、ホームに戻っても音が鳴り続け、チューナーも掴んだままになる。戻ったら同じ局を頼み直す
     */
    OnBackground(
        onStop = {
            background = true
            player.stop()
        },
        onStart = {
            background = false
            returns++
        },
    )
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
        // 予約・録画が変わったときも (メニューの「録画」と局の列の印を合わせる)
        val changed = setOf(
            DenpaEvent.Opened,
            DenpaEvent.Changed("services"),
            DenpaEvent.Changed("programs"),
            DenpaEvent.Changed("reservations"),
            DenpaEvent.Changed("recordings"),
        )
        repo.events.filter { it in changed }.collectLatest {
            delay(1_000)
            if (ready) refresh()
        }
    }
    /** メニューの画面へ戻るところ (映像に合いを取り返させない。戻った先が合いを取るので) */
    var leaving by remember { mutableStateOf(false) }
    /*
     * **戻るは1つで受ける。** メニューが開いていれば閉じ、何も無ければメニューの画面へ (戻った先は「ライブ」に合う)。
     * 開いているときと無いときで別々に置くと、端末によって (並べた順と効く順が食い違って) 開いているのに画面ごと戻ることがある。
     * 続けて押しても1つだけ戻る (2回目は受けない)
     */
    BackHandler(enabled = !leaving) {
        if (menu) {
            menu = false
        } else {
            leaving = true
            repo.menuOnReturn = true
            onLeave()
        }
    }

    if (!ready) return Centered("読み込んでいます…")
    val current = playing ?: return Centered("局がありません")
    fun zap(step: Int) {
        neighbor(services, current.id, step)?.let { playing = it }
    }
    fun openMenu() {
        touched = System.nanoTime()
        menu = true
    }
    /** いまの番組を録る (ブラウザのライブの録画ボタンと同じ口)。結果は1行で知らせ、局を取り直して印を合わせる */
    fun record() {
        val service = current
        menu = false
        scope.launch {
            val result = try {
                repo.api.recordNow(repo.base, service.id)
            } catch (_: Unauthorized) {
                return@launch onUnauthorized()
            }
            flash(
                when (result) {
                    is RecordResult.Recorded ->
                        if (result.reserved) "録画を始めます: ${result.title}"
                        else "予約しましたが、チューナーが足りず録れません (競合): ${result.title}"
                    is RecordResult.Failed -> "録画できません: ${result.message}"
                    RecordResult.Unsupported -> "この denpa はアプリからの録画に対応していません (denpa を新しくしてください)"
                },
            )
            if (result is RecordResult.Recorded) refresh()
        }
    }
    val currentCard = remember { FocusRequester() }
    PlayerFrame(
        player,
        // メニューを開いている間は1行の知らせを出さない (メニューの帯の下から透けて重なる)
        overlay.takeUnless { menu },
        error,
        active = !menu && !leaving,
        onKey = { event ->
            when (liveCommand(event.nativeKeyEvent.keyCode)) {
                LiveCommand.PreviousChannel -> { zap(-1); true }
                LiveCommand.NextChannel -> { zap(1); true }
                LiveCommand.Menu -> { openMenu(); true }
                LiveCommand.Info -> { flash(describe(current, quality) + "\n$LIVE_HINT"); true }
                null -> false
            }
        },
        // 決定は短押しも長押しもメニュー (長押しは、決定を押し続けがちな人とリモコンのため)
        onCenter = { openMenu() },
        progress = current.now?.let { System.currentTimeMillis() - it.startAt to it.endAt - it.startAt },
        captions = captions,
        busyLabel = "選局しています",
    ) {
        if (menu) {
            val now = current.now
            ControlBar(
                describe(current, quality),
                groups = listOf(
                    "画質" to LiveQuality.available(repo.app.decoders).map { choice ->
                        Control(choice.label, on = choice == quality, icon = if (choice == quality) R.drawable.ic_quality else null) {
                            scope.launch { repo.app.settings.setLiveQuality(choice) }
                        }
                    },
                    "" to tracks.controls() + baked.controls(),
                    "" to listOf(recordControl(now) { record() }),
                ),
                onActivity = { touched = System.nanoTime() },
                // いまの番組の進み
                header = now?.let { program ->
                    { ProgressLine(System.currentTimeMillis() - program.startAt, program.endAt - program.startAt) }
                },
                down = currentCard,
                below = {
                    ChannelRows(repo, services, current, currentCard) { picked ->
                        playing = picked
                        menu = false
                    }
                },
            )
        }
    }
}

/** 録画の札。録っている・録る予定ならそう出す (押しても二重には録らない。denpa が番組ごとに1本にまとめる) */
private fun recordControl(now: NowProgram?, onClick: () -> Unit): Control = when {
    now?.recording == true -> Control("録画中", on = true, icon = R.drawable.ic_record, onClick = onClick)
    now?.reserved == true -> Control("録画予約済み", on = true, icon = R.drawable.ic_record, onClick = onClick)
    else -> Control("録画", icon = R.drawable.ic_record, onClick = onClick)
}

/** 1行目に局、2行目にいま放送中の番組と残り */
private fun describe(service: Service, quality: LiveQuality): String {
    val head = listOfNotNull(service.number?.toString(), service.name, quality.label).joinToString("  ")
    val now = service.now ?: return head
    val left = "あと${now.remainingMinutes(System.currentTimeMillis())}分"
    // サブチャンネルは番組名が空で来る
    return if (now.title.isBlank()) "$head\n$left" else "$head\n${now.title}  $left"
}

private const val LIVE_HINT = "上下で局送り・決定でメニュー (局・画質・録画)"

/** メニューを閉じるまで (ミリ秒)。局の列で番組名を読むので、録画の帯 (5 秒) より長く */
private const val MENU_IDLE_MS = 8_000L

/** 覚えているライブの画質 (追っかけも同じものを使う)。この端末で選べなければ選び直す。読み終えるまでは null */
@Composable
fun rememberLiveQuality(repo: Repository): LiveQuality? {
    val saved by repo.app.settings.liveQuality.collectAsState(initial = LOADING_QUALITY)
    if (saved == LOADING_QUALITY) return null
    return remember(saved) { LiveQuality.choose(saved, repo.app.decoders) }
}

private const val LOADING_QUALITY = "\u0000loading"

/** 番組の終わりから取り直すまでの間 (ミリ秒)。denpa の時計とのずれのぶん */
private const val PROGRAM_END_GRACE_MS = 3_000L
