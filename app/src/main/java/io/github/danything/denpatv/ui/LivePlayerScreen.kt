package io.github.danything.denpatv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import io.github.danything.denpatv.R
import io.github.danything.denpatv.data.CaptionPaths
import io.github.danything.denpatv.data.ChaseEnd
import io.github.danything.denpatv.data.DenpaEvent
import io.github.danything.denpatv.data.LiveCommand
import io.github.danything.denpatv.data.LiveQuality
import io.github.danything.denpatv.data.NowProgram
import io.github.danything.denpatv.data.RecordResult
import io.github.danything.denpatv.data.Reconnect
import io.github.danything.denpatv.data.Service
import io.github.danything.denpatv.data.Unauthorized
import io.github.danything.denpatv.data.YieldPlan
import io.github.danything.denpatv.data.YieldedRecording
import io.github.danything.denpatv.data.audioQuery
import io.github.danything.denpatv.data.chaseEnd
import io.github.danything.denpatv.data.liveCenter
import io.github.danything.denpatv.data.liveChannelKey
import io.github.danything.denpatv.data.liveCommand
import io.github.danything.denpatv.data.neighbor
import io.github.danything.denpatv.data.number
import io.github.danything.denpatv.data.remoteKeyService
import io.github.danything.denpatv.data.withBusyAdvice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ライブ。**開いたらすぐ、最後に観ていた局を映す** (初めてなら局の一覧の先頭)。denpa の画面のライブと同じ。
 *
 * - **左右** (チャンネル送りも) で前・次の局 (同じものを流しているサブチャンネルは飛ばす)。押している間は行き先の局・番組を
 *   下に出すだけで、**キーを離して少し (`ZAP_SETTLE_MS`) たってから、最後の局だけをリクエストする** (押し続けて局を送っても、途中の局ごとに
 *   denpa にチューナーを替えさせない)。替えたら局・番組・番組の進みを数秒だけ下に出す (キーの割り当ては data/Remote.kt)
 * - **数字キーで、そのリモコン番号の局へすぐ替える** (テレビのリモコンと同じワンタッチ選局。同じ番号の局が地上波と BS にあれば、いま観ている種別のほう)
 * - **下・決定・Menu でメニュー** (YouTube・Prime Video・ABEMA などのテレビのアプリと同じく下で開く)。
 *   下の端に、ブラウザの denpa のライブの操作列にあたる**操作の列** (画質・字幕・音声・録画) と、その下に**局の列**
 *   (地上波 / BS / CS / SKY ごとの列。ブラウザのタブにあたる) が覗く。下キーで局の列に入り、左右で選んで決定で替える。
 *   **上で開くと、はじめから局の列のいま映している局にフォーカスがある** (一覧から局を選ぶ近道)。
 *   **操作の列 (いちばん上) でもう一度上を押すと閉じて映像に戻る** (`UpToClose`)。
 *   画質は選んだらすぐリクエストし直してこの端末で覚える (既定は端末がハードで MPEG-2 をデコードできれば元の TS)。映るまでは前の映像のまま
 *   「AV1 に切り替え中」、映ったら「AV1 にしました」。映せなければ元の画質に戻して理由を出す (`rememberCodecSwitching`)。**戻るで閉じる** (もう一度でメニューの画面へ)。
 *   8 秒触らなければ閉じる
 * - **録画** はいま観ている番組を denpa に予約する (ブラウザのライブの録画ボタンと同じ。何度押しても二重には録らない)
 * - 局を替えている間は前の局の映像を残し、長くかかったら (1.5 秒) 回るものと「選局しています」→「映像を待っています」を出す (PlayerFrame)
 * - **切れたら (エラー・denpa がストリームを閉じた・10 秒進まない) 同じ局をリクエストし直す** (`Recovery`)。前の映像を残し、1.5 秒たったら
 *   回るものと「繋ぎ直しています」。待ちは 1 秒から倍々で 10 秒まで、回数の上限は無い。局が無い (404) などは理由を出して止める
 * - **録画にチューナーを譲ったら** (denpa の通知 `yielded`)、同じ局をリクエストし直し、映らなかったときだけ譲った録画の追っかけに切り替えて
 *   通知を出しっぱなしにする。メニューは「ライブに戻る」(再リクエストを試し、だめなら追っかけに戻る) と画質と局の列だけ (`YieldPlan`)。
 *   追っかけは denpa の変換の枠を使うので、埋まっていれば断られる。何度かだけリクエストし直し、諦めたら「サーバが混んでいます」(MPEG-2 を勧める)
 * - アプリが裏に回ったら (ホーム・別のアプリ) 止めて denpa から降りる (チューナーを空ける)。戻ったら同じ局を映し直す
 * - **決定の長押しと情報キーで、いま放送中の番組の詳細** (`LiveDetailDialog`。録画の詳細と同じもの)。そこからも録画できる
 * - 何も開いていないときの戻るは、メニューの画面へ (「ライブ」にフォーカス)
 */
@Composable
fun LivePlayerScreen(repo: Repository, onLeave: () -> Unit, onUnauthorized: () -> Unit) {
    val quality = rememberLiveQuality(repo) ?: return
    val buffering = quality.buffering

    var services by remember { mutableStateOf(repo.services) }
    /**
     * 映している局。**番号ではなく局で持つ** — 1 分ごとの取り直しで一覧の並びや顔ぶれが変わると、
     * 番号だと隣の局を指してしまう。一覧から消えても、映しているものは止めない (通知だけ出す)
     */
    var playing by remember { mutableStateOf<Service?>(null) }
    /** 局の一覧を読み終えたか (「読み込み中」と「局が無い」を分ける) */
    var ready by remember { mutableStateOf(false) }
    /** 映している局が一覧から消えたと知らせたか */
    var gone by remember { mutableStateOf(false) }
    /** メニュー (操作の列と局の列) を開いているか。開いているなら、開いたときにどちらにフォーカスするか */
    var menu by remember { mutableStateOf<MenuStart?>(null) }
    /** メニューで最後にキーを押したとき (しばらく触らなければ閉じる) */
    var touched by remember { mutableLongStateOf(0L) }
    LaunchedEffect(menu, touched) {
        if (menu == null) return@LaunchedEffect
        delay(MENU_IDLE_MS)
        menu = null
    }
    /** 番組の詳細を開いているか (決定の長押し・情報キー) */
    var detail by remember { mutableStateOf(false) }
    /** 詳細の「録画」を押した結果 (詳細のボタンの下に出す) */
    var recordNote by remember { mutableStateOf<String?>(null) }
    /** 詳細の「録画」を押した回数。続けて押したときに、前の押しの答えで最後の答えを上書きしない */
    var recordPresses by remember { mutableIntStateOf(0) }
    /** キーの手引きを出したか (開いて最初の1回だけ) */
    var hinted by remember { mutableStateOf(false) }
    /** 裏に回っている (ホーム・別のアプリ)。その間は流さない (`OnBackground`) */
    var background by remember { mutableStateOf(false) }
    /** 裏から戻った回数。戻るたびに同じ局をリクエストし直す */
    var returns by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val clock = remember { TsClock() }
    /*
     * ---- 録画に譲ったとき (denpa の docs/stream.md「録画に譲る」。ブラウザのライブと同じ動き) ----
     *
     * denpa のライブは録画にだけ譲る。チューナーを取り上げられると denpa はストリームを閉じ、どの録画に譲ったかを通知 (`yielded`) で流す。
     * 閉じたらいつもどおり同じ局をリクエストし直し (使ってよい台が空いていればそこに入り、見ているものは変わらない)、
     * **リクエストし直しても映らなかったときだけ**、その録画の追っかけに切り替える (`YieldPlan`)。メニューの「ライブに戻る」は
     * 再リクエストを試し、映らなければ追っかけに戻って「録画が終わるまでライブは見られません」
     */
    /** 通知で来た、譲った録画 (局と録画)。映している局のものだけ使う */
    var kicked by remember { mutableStateOf<DenpaEvent.Yielded?>(null) }
    /** 録画に譲って、その追っかけを観ている (局 `serviceId` のライブの代わりに) */
    var yielded by remember { mutableStateOf<Yielded?>(null) }
    /** 「ライブに戻る」を試している */
    var returning by remember { mutableStateOf(false) }
    /** いまのリクエストは、取り上げられたあと・「ライブに戻る」の再リクエストで、まだ映っていない (映らずに終わったら追っかけへ) */
    var trying by remember { mutableStateOf(false) }
    /** 追っかけをリクエストし直す (切れた・止まった) たびに増やす */
    var chaseLoads by remember { mutableIntStateOf(0) }
    /** いま追っかけを観ているなら、その譲り。局を替えたら (別の局を映すなら) 観ない */
    val chasing = yielded?.takeIf { it.serviceId == playing?.id && !returning }
    /**
     * 再リクエストが映らずに終わった。取り上げられたあと・「ライブに戻る」の再リクエストなら、譲った録画の追っかけへ切り替えて true。
     * そうでなければ false (いつもどおりリクエストし直す)。繋ぎ直しの番 (`Recovery`) と、繋ぎ直さずに止まったとき (404 など) の両方から
     */
    fun giveWay(): Boolean {
        val service = playing ?: return false
        val to = YieldPlan.fallback(trying, returning, yielded?.recording, kicked?.takeIf { it.serviceId == service.id }?.recording) ?: return false
        yielded = Yielded(service.id, to, refused = returning)
        returning = false
        trying = false
        kicked = null
        return true
    }
    /*
     * **切れた・終わった・止まったら、同じ局をリクエストし直す** (`Recovery`。ブラウザのライブの `reconnect` と同じく何度でも、待ちを倍々に)。
     * リクエストし直すのは裏から戻ったときと同じ経路 (`returns`)。再リクエストが映らずに終わったのが取り上げられたあと・「ライブに戻る」なら、
     * リクエストし直さずに譲った録画の追っかけへ。追っかけを観ている間は、切れたら追っかけをリクエストし直す (ストリームが終わったときは、録り終えたのかを聞いてから。下)
     */
    val (player, error, dualMono, recovery) = rememberPlayer(
        repo,
        buffering,
        onUnauthorized,
        clock,
        if (chasing != null) {
            ReconnectPlan("live 局 ${playing?.id} の代わりの追っかけ 録画 ${chasing.recording.id}", stream = true) { chaseLoads++ }
        } else {
            ReconnectPlan("live 局 ${playing?.id}", stream = true, endedIsLost = true) { if (!giveWay()) returns++ }
        },
    )
    val flasher = rememberFlash()
    val (overlay, flash) = flasher
    // 元の TS の字幕は denpa がデコードした文字の配置を別のエンドポイントで受け取る (変換したライブには字幕が載らない)
    val captions = rememberRawCaptions(
        repo,
        player,
        clock,
        // 裏に回っている間は字幕のストリームも閉じる。追っかけを観ている間は録画の字幕 (映像と同じく、録れているところの少し手前から)
        path = when {
            quality != LiveQuality.Raw || background -> null
            chasing != null -> CaptionPaths.recording(chasing.recording.id)
            else -> playing?.let { CaptionPaths.live(it.live) }
        },
        generation = chasing?.let { "chase ${it.recording.id} $chaseLoads" } ?: playing?.id,
        fromMs = chasing?.let { { YieldPlan.EDGE_MS } },
        onUnauthorized = onUnauthorized,
    )
    // デュアルモノの主・副は、元の TS のときだけ配り直す (変換したライブは denpa が選んだ1つだけをエンコードする。下の `baked`)。追っかけの音声は分からないので並べない
    val tracks = rememberTracks(
        repo,
        player,
        flash,
        captions,
        dualMono,
        denpaAudios = playing?.takeIf { quality == LiveQuality.Raw && chasing == null }?.now?.audios.orEmpty(),
    )
    /*
     * 変換したライブの音声は denpa にリクエストして選ぶ (`?audio=<id>`)。変わればリクエストし直す。
     * **番組が替わって音声の並びが変わったときもリクエストし直す** (二カ国語の映画が終わってステレオに戻るなど)。denpa はエンコードの始めに
     * 選んだ音声のままエンコードし続けるので、リクエストし直さないと、ステレオの番組の片側だけを両耳に配ったままになる。並びが同じならリクエストし直さない
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
     * 局を最後に出したもの。音声だけを替えてリクエストし直したときは出し直さない (「音声 …」の通知を消さない)。
     * 画質だけを替えたときも出さない (切り替え中・切り替えたは `codec` が言う)
     */
    var shown by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(playing?.id, quality, baked.ready, baked.audio?.id, returns, codec.chosen, chasing?.recording?.id, chaseLoads) {
        val service = playing ?: return@LaunchedEffect
        if (background) return@LaunchedEffect
        // 選んだ画質が覚えている画質に届くまではリクエストしない (届いたらリクエストする。選び直しが1フレームにまとまって画質が変わらなくても、ここでリクエストし直す)
        if (codec.chosen != quality) return@LaunchedEffect
        // 録画に譲っている間は、その追っかけ (録れているところの少し手前から。音声は denpa が主音声をエンコードする)
        if (chasing != null) {
            val url = repo.url(YieldPlan.chaseUrl(chasing.recording.id, quality.codec)) ?: return@LaunchedEffect
            trying = false
            recovery.requested()
            player.load(url, quality.mime)
            // 画質はメニューで替えられる (変換の枠が埋まって断られたら MPEG-2 へ)。替えたら、映ったかを見る
            codec.requested(quality)
            return@LaunchedEffect
        }
        if (!baked.ready) return@LaunchedEffect
        val url = repo.url("${service.live}?codec=${quality.codec}${audioQuery(baked.audio)}") ?: return@LaunchedEffect
        // 取り上げられたあと・「ライブに戻る」の再リクエスト。映らずに終わったら追っかけへ (`rememberPlayer` に渡した決まり)
        trying = returning || kicked?.serviceId == service.id
        recovery.requested()
        player.load(url, quality.mime)
        codec.requested(quality)
        if (shown == service.id) return@LaunchedEffect
        shown = service.id
        flash(
            buildAnnotatedString {
                append(describe(service, codec.label))
                if (!hinted) append("\n$LIVE_HINT")
            },
        )
        hinted = true
        repo.app.settings.setLastService(service.id)
    }
    /*
     * **裏に回ったら止めて、denpa から降りる** (ストリームを閉じる。ほかに観ている人がいなければ denpa がチューナーを空ける)。
     * 流しっぱなしにすると、ホームに戻っても音が鳴り続け、チューナーも使ったままになる。戻ったら同じ局をリクエストし直す
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
    /*
     * **リクエストし直したライブが映ったら、入れた。** 譲った録画のことは忘れる。「ライブに戻る」で戻れたなら通知を消し、局と番組を出す。
     * 映像の無い局 (ラジオ) では最初のフレームの通知が来ないので、流れはじめたことでも済ませる
     */
    val codecLabel by rememberUpdatedState(codec.label)
    DisposableEffect(player) {
        fun pictured() {
            if (!trying) return
            trying = false
            kicked = null
            if (!returning) return
            returning = false
            yielded = null
            playing?.let { flash(describe(it, codecLabel)) }
        }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() = pictured()
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) pictured()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    /*
     * **譲った録画の追っかけが終わった。** H.264 / AV1 の追っかけは、番組が替わって映像・音声・字幕の並び (PMT) が変わったところでも
     * denpa が閉じる (録り終えたのではない。denpa の docs/api.md「追っかけ再生」)。まだ録っているかを denpa に聞いて、録っていれば
     * (聞けなければ) リクエストし直す (追っかけの画面の `chaseEnd` と同じ)。録り終えていれば、終わったまま
     */
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                val chase = yielded?.takeIf { it.serviceId == playing?.id && !returning } ?: return
                val loads = chaseLoads
                val pictured = recovery.pictured
                scope.launch {
                    val still = try {
                        repo.api.recordingState(repo.base, chase.recording.id)?.recording ?: false
                    } catch (_: Unauthorized) {
                        return@launch onUnauthorized()
                    } catch (_: Exception) {
                        // 聞けない: denpa が入れ替わっている最中
                        null
                    }
                    // 聞いている間に局を替えた・ライブに戻った・リクエストし直した
                    if (yielded !== chase || chase.serviceId != playing?.id || returning || chaseLoads != loads) return@launch
                    // 録り終えていれば終わったまま (長さは聞かない。譲った録画は録れているところの少し手前から観ているので、録り終えたなら最後まで観た)
                    if (chaseEnd(still, positionMs = 0, durationMs = null, pictured = pictured) == ChaseEnd.Finished) return@launch
                    // 映る前に終わったのは、何度かだけ (ライブと同じ)
                    recovery.retry(
                        "ended 追っかけが終わった (録画中=$still、映して ${recovery.playedSeconds()} 秒)",
                        if (pictured) Reconnect.Verdict.Retry else Reconnect.Verdict.RetryFew,
                    )
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // 譲った録画の通知 (`yielded`)。映している局のものだけ。もう追っかけを観ていれば要らない
    LaunchedEffect(Unit) {
        repo.events.collect { event ->
            if (event is DenpaEvent.Yielded && event.serviceId == playing?.id && yielded?.serviceId != event.serviceId) kicked = event
        }
    }
    // 再リクエストが繋ぎ直さずに止まった (404 など理由を出して止めるもの) ときも、譲っていたなら追っかけへ戻る (エラーで行き止まりにしない)
    LaunchedEffect(error) {
        if (error != null) giveWay()
    }
    /*
     * 通知は、閉じたストリームの再リクエストが映ったあとに届くこともある (そのときは使わない)。残しておくと、ずっと後の別の再リクエストで
     * 古い録画へ切り替えかねないので、しばらくしたら忘れる
     */
    LaunchedEffect(kicked) {
        if (kicked == null) return@LaunchedEffect
        delay(KICKED_FOR_MS)
        if (!trying) kicked = null
    }
    // 局を替えたら、前の局の譲りは忘れる (戻ってきたら、追っかけではなくライブをリクエストする)
    LaunchedEffect(playing?.id) {
        if (yielded?.serviceId != playing?.id) {
            yielded = null
            returning = false
        }
        if (kicked?.serviceId != playing?.id) kicked = null
    }
    /** 局を取り直して、映している局を新しいものに替える (取れなければそのまま) */
    suspend fun refresh() {
        runCatching { repo.refreshServices() }.onSuccess {
            services = repo.services
            val current = playing ?: return@onSuccess
            val fresh = services.firstOrNull { it.id == current.id }
            when {
                fresh != null -> { playing = fresh; gone = false }
                // 通知は消えたときに1度だけ (取り直しのたびに出さない)
                !gone -> {
                    gone = true
                    flash(broadcastPrefix("${current.name} は局の一覧から無くなりました (スキャンし直した?)。左右で別の局へ", current.name))
                }
            }
        }
    }
    // いま放送中の番組は変わっていく。1分ごとに取り直す
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
    // denpa の通知 (局・番組表が変わった、繋ぎ直した) でも取り直す。番組表は1局集めるたびに来るので、まとめて1回 (1 秒待つ)
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
    /** メニューの画面へ戻るところ (映像にフォーカスを取り返させない。戻った先がフォーカスを取るので) */
    var leaving by remember { mutableStateOf(false) }
    /*
     * **戻るは1つで受ける。** メニューが開いていれば閉じ、何も無ければメニューの画面へ (戻った先は「ライブ」にフォーカス)。
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
     * 押し続けても途中の局はリクエストしない (denpa がそのたびにチューナーを替えずに済む)。その間は前の局を流したまま。
     * 待ちを最後に押したときから数えると、押し続けたときの最初の繰り返し (古い Android は 0.5 秒後) より先に切れて、
     * 途中の局をリクエストしてしまう。離したのが届かなかったとき (フォーカスが外れたなど) は、最後に押してから `ZAP_HELD_MS` で映す
     */
    var stepping by remember { mutableStateOf<Service?>(null) }
    /** 局を切り替えるキーを押したまま (離すまでリクエストしない) */
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
    /** 送っている途中なら、待たずにそこへ替える (メニューの局の列・詳細の番組と、映しているものを揃える) */
    fun settle() {
        stepping?.let { playing = it }
        stepping = null
        held = false
    }
    /** メニューを開く。開く前の通知 (局と番組・キーの手引き) は消す (メニューのタイトルと同じことを言うだけなので、タイトルに持ち込まない) */
    fun openMenu(start: MenuStart) {
        settle()
        touched = System.nanoTime()
        flasher.clear()
        menu = start
    }
    /**
     * 数字キーで、そのリモコン番号の局へすぐ替える (`remoteKeyService`。同じ番号が何局かあれば、いまの種別の局)。
     * 局の切り替えの途中なら、その行き先は捨てる
     */
    fun tune(key: Int) {
        val target = remoteKeyService(services, key, current.type) ?: return flash("リモコン番号 $key の局がありません")
        stepping = null
        held = false
        // いまの局なら局と番組を出し直すだけ
        if (target.id == current.id) flash(describe(current, codec.label)) else playing = target
    }
    /**
     * いまの番組を録る (ブラウザのライブの録画ボタンと同じエンドポイント)。結果は1行で知らせ (メニューからは下の通知、詳細からはボタンの下)、
     * 局を取り直して印を合わせる
     */
    fun record(fromDetail: Boolean) {
        val service = current
        menu = null
        val press = if (fromDetail) ++recordPresses else 0
        if (fromDetail) recordNote = "録画を開始しています…"
        scope.launch {
            val result = try {
                repo.api.recordNow(repo.base, service.id)
            } catch (_: Unauthorized) {
                return@launch onUnauthorized()
            }
            val message = when (result) {
                is RecordResult.Recorded ->
                    if (result.reserved) "録画を始めます: ${result.title}"
                    else "予約はありますが、録画できない状態です (チューナーの競合など): ${result.title}"
                is RecordResult.Failed -> "録画できません: ${result.message}"
            }
            if (!fromDetail) flash(message) else if (press == recordPresses) recordNote = message
            if (result is RecordResult.Recorded) refresh()
        }
    }
    fun run(command: LiveCommand) = when (command) {
        LiveCommand.PreviousChannel -> zap(-1)
        LiveCommand.NextChannel -> zap(1)
        LiveCommand.Menu -> openMenu(MenuStart.Controls)
        LiveCommand.Channels -> openMenu(MenuStart.Channels)
        LiveCommand.Details -> {
            settle()
            menu = null
            recordNote = null
            detail = true
        }
    }
    /** 「ライブに戻る」。同じ局をリクエストし直し、映らなければ追っかけに戻る (`rememberPlayer` に渡した決まり) */
    fun backToLive() {
        menu = null
        if (chasing != null) returning = true
    }
    val currentCard = remember { FocusRequester() }
    PlayerFrame(
        player,
        /*
         * 詳細を開いている間は通知を出さない。メニューを開いている間はメニューのタイトルに添える (`covered`)。
         * 録画に譲っている間は、その通知を出しっぱなしにする (ほかの通知が出ている間はそちら)
         */
        (overlay ?: chasing?.let(::yieldNotice)).takeUnless { detail },
        // 譲った録画の追っかけは変換の枠を使う。埋まって断られたら MPEG-2 を勧める (ライブそのものは断られない)
        withBusyAdvice(error, quality, repo.app.decoders),
        active = menu == null && !detail && !leaving,
        // `::run` (関数の参照) にしない。Compose が参照を覚えたままにして、前の局から送ってしまう
        onKey = { event ->
            val code = event.nativeKeyEvent.keyCode
            // 数字キーは押し続けた繰り返しを捨てる (1回押したのと同じ。局を何度もリクエストし直さない)
            liveCommand(code)?.let { run(it) } != null ||
                liveChannelKey(code)?.let { if (event.nativeKeyEvent.repeatCount == 0) tune(it) } != null
        },
        onKeyUp = { held = false },
        // 決定の短押しはメニュー、長押しは情報キーと同じ (番組の詳細)
        onCenter = { press -> run(liveCenter(press)) },
        // 送っている途中は行き先の番組の進み (通知の文と揃える)。録画に譲っている間は出さない (映しているのはその局の番組ではない)
        progress = (stepping ?: current.takeIf { chasing == null })?.now
            ?.let { System.currentTimeMillis() - it.startAt to it.endAt - it.startAt },
        captions = captions,
        busyLabel = "選局しています",
        recovery = recovery,
        covered = menu != null,
    ) {
        menu?.let { start ->
            val now = current.now
            // 下の局の列 (どちらのメニューにも出す)。選んだらその局に替えて閉じる
            val channels: @Composable ColumnScope.() -> Unit = {
                ChannelRows(repo, services, current, currentCard) { picked ->
                    playing = picked
                    menu = null
                }
            }
            // 画質の列 (どちらのメニューにも出す)。選んだらすぐ入れる (映るまではタイトルが「… に切り替え中」)
            val qualities = "画質" to LiveQuality.available(repo.app.decoders).map { choice ->
                Control(choice.label, on = choice == codec.chosen, icon = if (choice == codec.chosen) R.drawable.ic_quality else null) {
                    codec.choose(choice)
                }
            }
            /*
             * 録画に譲っている間のメニューは「ライブに戻る」(開いたらそこにフォーカス) と画質だけ。画質は、追っかけが変換の枠が埋まって
             * 断られたときに MPEG-2 へ替えるため。下の局の列から別の局も選べる。
             * タイトルは観ている録画の局。何を観ているかは通知 (タイトルの2行目に添わる) が言う
             */
            if (chasing != null) {
                ControlBar(
                    buildAnnotatedString {
                        appendBroadcast(chasing.recording.serviceName)
                        append("  録画中")
                    },
                    groups = listOf(
                        "" to listOf(Control(YieldPlan.BACK_TO_LIVE, icon = R.drawable.ic_live, initial = true) { backToLive() }),
                        qualities,
                    ),
                    focusActions = start == MenuStart.Controls,
                    onActivity = { touched = System.nanoTime() },
                    onUp = { menu = null },
                    down = currentCard,
                    below = channels,
                )
            } else ControlBar(
                describe(current, codec.label),
                groups = listOf(
                    qualities,
                    "" to tracks.controls() + baked.controls(),
                    "" to listOf(recordControl(now) { record(fromDetail = false) }),
                ),
                // 上で開いたときは、操作の列ではなく局の列のいま映している局にフォーカスする (下の LaunchedEffect)
                focusActions = start == MenuStart.Controls,
                onActivity = { touched = System.nanoTime() },
                // 操作の列がいちばん上の列。そこで上キーを押すと閉じて映像に戻る (戻ると同じ。局の列から押し続けて上がってきた分では閉じない)
                onUp = { menu = null },
                // いまの番組の進み
                header = now?.let { program ->
                    { ProgressLine(System.currentTimeMillis() - program.startAt, program.endAt - program.startAt) }
                },
                down = currentCard,
                // 局ロゴ (取得できている局だけ。無ければタイトルだけ)
                leading = current.logo?.let { { ChannelLogo(repo, current, Modifier.width(56.dp).height(32.dp)) } },
                below = channels,
            )
            LaunchedEffect(Unit) {
                if (start != MenuStart.Channels) return@LaunchedEffect
                // 局のカードは局の列を並べたあとに出来るので、フォーカスできるまで何フレームか試す (できなければ操作バーが操作の列にフォーカスする)
                for (attempt in 0 until CARD_FOCUS_TRIES) {
                    withFrameNanos { }
                    if (runCatching { currentCard.requestFocus() }.getOrDefault(false)) break
                }
            }
        }
    }
    if (detail) {
        LiveDetailDialog(
            repo,
            current,
            onRecord = { record(fromDetail = true) },
            note = recordNote,
            onClose = { detail = false },
            onUnauthorized = onUnauthorized,
        )
    }
}

/** 録画に譲って観ている追っかけ。局 `serviceId` のライブの代わり。`refused` は「ライブに戻る」を試して入れなかった */
private data class Yielded(val serviceId: Long, val recording: YieldedRecording, val refused: Boolean)

/** 録画に譲っている間の通知 (ブラウザのライブと同じ言い方)。2行目は戻り方か、戻れなかったわけ。番組名は放送の字 */
private fun yieldNotice(yielded: Yielded): AnnotatedString = buildAnnotatedString {
    append(YieldPlan.SWITCHED_HEAD)
    appendBroadcast(yielded.recording.title)
    append(YieldPlan.SWITCHED_TAIL)
    append("\n")
    append(if (yielded.refused) YieldPlan.refused(yielded.recording.endAt) else YIELD_HINT)
}

/** 譲った録画の通知を、再リクエストに使うまで覚えておく長さ (ミリ秒)。チューナーを取り上げられてから再リクエストが映らずに終わるまで (数秒〜十数秒) より十分長く */
private const val KICKED_FOR_MS = 60_000L

/** 譲っている間の通知の2行目。メニューを開くと「ライブに戻る」にフォーカスする */
private const val YIELD_HINT = "決定でメニュー → 「${YieldPlan.BACK_TO_LIVE}」"

/** メニューを開いたときにどこにフォーカスするか。下・決定・Menu は操作の列、上は局の列のいま映している局 */
private enum class MenuStart { Controls, Channels }

/** 録画のボタン。録っている・録る予定ならそう出す (押しても二重には録らない。denpa が番組ごとに1本にまとめる) */
private fun recordControl(now: NowProgram?, onClick: () -> Unit): Control =
    Control(recordLabel(now), on = now?.recording == true || now?.reserved == true, icon = R.drawable.ic_record, onClick = onClick)

/** 1行目に局と画質 (`CodecSwitch.label`)、2行目にいま放送中の番組と残り。局名と番組名は放送の字 (`appendBroadcast`) */
private fun describe(service: Service, quality: String): AnnotatedString = buildAnnotatedString {
    service.number?.let { append("$it  ") }
    appendBroadcast(service.name)
    append("  $quality")
    val now = service.now ?: return@buildAnnotatedString
    append("\n")
    // サブチャンネルは番組名が空で来る
    if (now.title.isNotBlank()) {
        appendBroadcast(now.title)
        append("  ")
    }
    append("あと${now.remainingMinutes(System.currentTimeMillis())}分")
}

private const val LIVE_HINT = "左右で局を切り替え・決定か下でメニュー・上で局の列・決定の長押しで番組"

/**
 * 局の切り替えでキーを離してから映すまで (ミリ秒)。続けて押す間 (キーの繰り返しや、手で続けて押す間) より長く、
 * 1回だけ押したときに待たされたと感じない短さ
 */
private const val ZAP_SETTLE_MS = 500L

/** 押したまま離したのが届かないときに、最後に押してから映すまで (ミリ秒)。押し続けている間はキーの繰り返しが数十ミリ秒ごとに来る */
private const val ZAP_HELD_MS = 2_000L

/** 上で開いたメニューで、いま映している局のカードにフォーカスするのを試すフレーム数 */
private const val CARD_FOCUS_TRIES = 10

/** メニューを閉じるまで (ミリ秒)。局の列で番組名を読むので、録画の操作バー (5 秒) より長く */
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
