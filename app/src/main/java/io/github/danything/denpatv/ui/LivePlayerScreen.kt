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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.media3.common.MediaItem
import io.github.danything.denpatv.R
import io.github.danything.denpatv.data.CaptionPaths
import io.github.danything.denpatv.data.DenpaEvent
import io.github.danything.denpatv.data.LiveCommand
import io.github.danything.denpatv.data.liveCenter
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
 * - **左右** (チャンネル送りも) で前・次の局 (同じものを流しているサブチャンネルは飛ばす)。押している間は行き先の局・番組を
 *   下に出すだけで、**キーを離して少し (`ZAP_SETTLE_MS`) たってから、最後の局だけを頼む** (押し続けて局を送っても、途中の局ごとに
 *   denpa にチューナーを替えさせない)。替えたら局・番組・番組の進みを数秒だけ下に出す (キーの割り当ては data/Remote.kt)
 * - **下・決定・Menu でメニュー** (YouTube・Prime Video・ABEMA などのテレビのアプリと同じく下で開く)。
 *   下の端に、ブラウザの denpa のライブの操作列にあたる**操作の列** (画質・字幕・音声・録画) と、その下に**局の列**
 *   (地上波 / BS / CS ごとの列。ブラウザのタブにあたる) が覗く。下キーで局の列に入り、左右で選んで決定で替える。
 *   **上で開くと、はじめから局の列のいま映している局に合う** (一覧から局を選ぶ近道)。
 *   **操作の列 (いちばん上) でもう一度上を押すと閉じて映像に戻る** (`UpToClose`)。
 *   画質は選んだらすぐ頼み直してこの端末で覚える (既定は端末がハードで MPEG-2 を解ければ生の TS)。映るまでは前の絵のまま
 *   「AV1 に切り替え中」、映ったら「AV1 にしました」。映せなければ元の画質に戻して理由を出す (`rememberCodecSwitching`)。**戻るで閉じる** (もう一度でメニューの画面へ)。
 *   8 秒触らなければ閉じる
 * - **録画** はいま観ている番組を denpa に予約する (ブラウザのライブの録画ボタンと同じ。何度押しても二重には録らない)
 * - 局を替えている間は前の局の絵を残し、長くかかったら (1.5 秒) 回るものと「選局しています」→「映像を待っています」を出す (PlayerFrame)
 * - **切れたら (エラー・denpa が流れを閉じた・10 秒進まない) 同じ局を頼み直す** (`Recovery`)。前の絵を残し、1.5 秒たったら
 *   回るものと「繋ぎ直しています」。待ちは 1 秒から倍々で 10 秒まで、回数の上限は無い。局が無い (404) などは理由を出して止める
 * - アプリが裏に回ったら (ホーム・別のアプリ) 止めて denpa から降りる (チューナーを空ける)。戻ったら同じ局を映し直す
 * - 情報キーと決定の長押しで、いまの局と番組を出す
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
    /** メニュー (操作の列と局の列) を開いているか。開いているなら、開いたときにどちらに合わせるか */
    var menu by remember { mutableStateOf<MenuStart?>(null) }
    /** メニューで最後にキーを押したとき (しばらく触らなければ閉じる) */
    var touched by remember { mutableLongStateOf(0L) }
    LaunchedEffect(menu, touched) {
        if (menu == null) return@LaunchedEffect
        delay(MENU_IDLE_MS)
        menu = null
    }
    /** キーの手引きを出したか (開いて最初の1回だけ) */
    var hinted by remember { mutableStateOf(false) }
    /** 裏に回っている (ホーム・別のアプリ)。その間は流さない (`OnBackground`) */
    var background by remember { mutableStateOf(false) }
    /** 裏から戻った回数。戻るたびに同じ局を頼み直す */
    var returns by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val clock = remember { TsClock() }
    /*
     * **切れた・終わった・止まったら、同じ局を頼み直す** (`Recovery`。ブラウザのライブの `reconnect` と同じく何度でも、待ちを倍々に)。
     * 頼み直すのは裏から戻ったときと同じ口 (`returns`)
     */
    val (player, error, dualMono, recovery) = rememberPlayer(
        repo,
        buffering,
        onUnauthorized,
        clock,
        ReconnectPlan("live 局 ${playing?.id}", stream = true, endedIsLost = true) { returns++ },
    )
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
    val codec = rememberCodecSwitching(repo, player, quality, error, flash)

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
    /**
     * 局を最後に出したもの。音声だけを替えて頼み直したときは出し直さない (「音声 …」の知らせを消さない)。
     * 画質だけを替えたときも出さない (切り替え中・切り替えたは `codec` が言う)
     */
    var shown by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(playing?.id, quality, baked.ready, baked.audio?.id, returns, codec.chosen) {
        val service = playing ?: return@LaunchedEffect
        if (!baked.ready || background) return@LaunchedEffect
        // 選んだ画質が覚えている画質に届くまでは頼まない (届いたら頼む。選び直しが1こまにまとまって画質が変わらなくても、ここで頼み直す)
        if (codec.chosen != quality) return@LaunchedEffect
        val url = repo.url("${service.live}?codec=${quality.codec}${audioQuery(baked.audio)}") ?: return@LaunchedEffect
        recovery.requested()
        player.setMediaItem(MediaItem.Builder().uri(url, quality.mime))
        player.prepare()
        player.playWhenReady = true
        codec.requested(quality)
        if (shown == service.id) return@LaunchedEffect
        shown = service.id
        flash(describe(service, codec.label) + if (hinted) "" else "\n$LIVE_HINT")
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
                    flash("${current.name} は局の一覧から無くなりました (スキャンし直した?)。左右で別の局へ")
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
        if (menu != null) {
            menu = null
        } else {
            leaving = true
            repo.menuOnReturn = true
            onLeave()
        }
    }

    /**
     * 左右 (チャンネル送り) で送っている途中の行き先。押すたびに隣へ進め、**キーを離して `ZAP_SETTLE_MS` たったら映す** —
     * 押し続けても途中の局は頼まない (denpa がそのたびにチューナーを替えずに済む)。その間は前の局を流したまま。
     * 待ちを最後に押したときから数えると、押し続けたときの最初の繰り返し (古い Android は 0.5 秒後) より先に切れて、
     * 途中の局を頼んでしまう。離したのが届かなかったとき (合いが外れたなど) は、最後に押してから `ZAP_HELD_MS` で映す
     */
    var stepping by remember { mutableStateOf<Service?>(null) }
    /** 局送りのキーを押したまま (離すまで頼まない) */
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(stepping, held) {
        val target = stepping ?: return@LaunchedEffect
        delay(if (held) ZAP_HELD_MS else ZAP_SETTLE_MS)
        held = false
        playing = target
        stepping = null
    }

    if (!ready) return Centered("読み込んでいます…")
    val current = playing ?: return Centered("局がありません")
    fun zap(step: Int) {
        val next = neighbor(services, (stepping ?: current).id, step) ?: return
        held = true
        stepping = next
        // 行き先を出す (映ったら同じものを出し直して、数秒残す)
        flash(describe(next, codec.label))
    }
    fun openMenu(start: MenuStart) {
        // 送っている途中なら、待たずにそこへ替える (メニューの局の列と映しているものを揃える)
        stepping?.let { playing = it }
        stepping = null
        held = false
        touched = System.nanoTime()
        menu = start
    }
    /** いまの番組を録る (ブラウザのライブの録画ボタンと同じ口)。結果は1行で知らせ、局を取り直して印を合わせる */
    fun record() {
        val service = current
        menu = null
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
    fun run(command: LiveCommand) = when (command) {
        LiveCommand.PreviousChannel -> zap(-1)
        LiveCommand.NextChannel -> zap(1)
        LiveCommand.Menu -> openMenu(MenuStart.Controls)
        LiveCommand.Channels -> openMenu(MenuStart.Channels)
        LiveCommand.Info -> flash(describe(stepping ?: current, codec.label) + "\n$LIVE_HINT")
    }
    val currentCard = remember { FocusRequester() }
    PlayerFrame(
        player,
        // メニューを開いている間は1行の知らせを出さない (メニューの帯の下から透けて重なる)
        overlay.takeUnless { menu != null },
        error,
        active = menu == null && !leaving,
        // `::run` (関数の参照) にしない。Compose が参照を覚えたままにして、前の局から送ってしまう
        onKey = { event -> liveCommand(event.nativeKeyEvent.keyCode)?.let { run(it) } != null },
        onKeyUp = { held = false },
        // 決定の短押しはメニュー、長押しは情報キーと同じ (いまの局と番組)
        onCenter = { press -> run(liveCenter(press)) },
        // 送っている途中は行き先の番組の進み (知らせの文と揃える)
        progress = (stepping ?: current).now?.let { System.currentTimeMillis() - it.startAt to it.endAt - it.startAt },
        captions = captions,
        busyLabel = "選局しています",
        recovery = recovery,
    ) {
        menu?.let { start ->
            val now = current.now
            ControlBar(
                describe(current, codec.heading),
                groups = listOf(
                    "画質" to LiveQuality.available(repo.app.decoders).map { choice ->
                        // 選んだらすぐ入れる (映るまでは見出しが「… に切り替え中」)
                        Control(choice.label, on = choice == codec.chosen, icon = if (choice == codec.chosen) R.drawable.ic_quality else null) {
                            codec.choose(choice)
                        }
                    },
                    "" to tracks.controls() + baked.controls(),
                    "" to listOf(recordControl(now) { record() }),
                ),
                // 上で開いたときは、操作の列ではなく局の列のいま映している局に合わせる (下の LaunchedEffect)
                focusActions = start == MenuStart.Controls,
                onActivity = { touched = System.nanoTime() },
                // 操作の列がいちばん上の列。そこで上キーを押すと閉じて映像に戻る (戻ると同じ。局の列から押し続けて上がってきた分では閉じない)
                onUp = { menu = null },
                // いまの番組の進み
                header = now?.let { program ->
                    { ProgressLine(System.currentTimeMillis() - program.startAt, program.endAt - program.startAt) }
                },
                down = currentCard,
                below = {
                    ChannelRows(repo, services, current, currentCard) { picked ->
                        playing = picked
                        menu = null
                    }
                },
            )
            LaunchedEffect(Unit) {
                if (start != MenuStart.Channels) return@LaunchedEffect
                // 局の札は局の列を並べたあとに出来るので、合うまで何こまか試す (合わなければ帯が操作の列に合わせる)
                for (attempt in 0 until CARD_FOCUS_TRIES) {
                    withFrameNanos { }
                    if (runCatching { currentCard.requestFocus() }.getOrDefault(false)) break
                }
            }
        }
    }
}

/** メニューを開いたときにどこに合わせるか。下・決定・Menu は操作の列、上は局の列のいま映している局 */
private enum class MenuStart { Controls, Channels }

/** 録画の札。録っている・録る予定ならそう出す (押しても二重には録らない。denpa が番組ごとに1本にまとめる) */
private fun recordControl(now: NowProgram?, onClick: () -> Unit): Control = when {
    now?.recording == true -> Control("録画中", on = true, icon = R.drawable.ic_record, onClick = onClick)
    now?.reserved == true -> Control("録画予約済み", on = true, icon = R.drawable.ic_record, onClick = onClick)
    else -> Control("録画", icon = R.drawable.ic_record, onClick = onClick)
}

/** 1行目に局と画質 (`CodecSwitch.label`)、2行目にいま放送中の番組と残り */
private fun describe(service: Service, quality: String): String {
    val head = listOfNotNull(service.number?.toString(), service.name, quality).joinToString("  ")
    val now = service.now ?: return head
    val left = "あと${now.remainingMinutes(System.currentTimeMillis())}分"
    // サブチャンネルは番組名が空で来る
    return if (now.title.isBlank()) "$head\n$left" else "$head\n${now.title}  $left"
}

private const val LIVE_HINT = "左右で局送り・決定か下でメニュー・上で局の列・決定の長押しで番組"

/**
 * 局送りでキーを離してから映すまで (ミリ秒)。続けて押す間 (キーの繰り返しや、手で続けて押す間) より長く、
 * 1回だけ押したときに待たされたと感じない短さ
 */
private const val ZAP_SETTLE_MS = 500L

/** 押したまま離したのが届かないときに、最後に押してから映すまで (ミリ秒)。押し続けている間はキーの繰り返しが数十ミリ秒ごとに来る */
private const val ZAP_HELD_MS = 2_000L

/** 上で開いたメニューで、いま映している局の札に合わせるのを試すこま数 */
private const val CARD_FOCUS_TRIES = 10

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
