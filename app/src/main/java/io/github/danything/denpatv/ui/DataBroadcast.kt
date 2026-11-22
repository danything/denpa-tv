package io.github.danything.denpatv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import io.github.danything.denpatv.R
import io.github.danything.denpatv.data.AppCookie
import io.github.danything.denpatv.data.BaseUrl
import io.github.danything.denpatv.data.CaptionFeed
import io.github.danything.denpatv.data.DataFeed
import io.github.danything.denpatv.data.DataFrame
import io.github.danything.denpatv.data.DataKeyAction
import io.github.danything.denpatv.data.DataKeys
import io.github.danything.denpatv.data.DataPage
import io.github.danything.denpatv.data.DataRowButton
import io.github.danything.denpatv.data.PageMessage
import io.github.danything.denpatv.data.Unauthorized
import io.github.danything.denpatv.data.VideoPlace
import io.github.danything.denpatv.data.WebOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** どれのデータ放送か。ライブは局 (`GET api/services` の `id`)、録画は録画の id */
sealed interface DataTarget {
    data class Live(val service: Long) : DataTarget
    data class Recording(val id: Long) : DataTarget
}

/** ページに渡す再生の時刻 (`DataPage.time`) */
class DataTime(val air: Long?, val pos: Long, val rate: Float, val paused: Boolean)

/**
 * **データ放送を、映像の上に重ねた透明な WebView で出す** (描くのは denpa のページ `embed/data`。約束は denpa の docs/api.md
 * 「データ放送のページ `/embed/data`」)。映像はプレーヤーのまま、ページが言う場所 (`place`) に縮める。
 *
 * - **WebView を作るのは、開いている・開くのを頼まれた・自動で受信している間だけ** (`keep`)。どれでもなくなってから `CLOSE_GRACE_MS` で捨てる
 *   (局を替えて次の局の `autoStart` を待つ間に作り直さない)。局を替えても作り直さず、ページへ `channel` を送る
 * - JavaScript と Kotlin の間は MessagePort 1 本だけ (`createWebMessageChannel`)。**`addJavascriptInterface` は使わない**
 *   (WebView の中では放送の文書と Hybridcast のアプリが動く。どのフレームからも Kotlin を呼べないように)。ポートはメインフレームの
 *   denpa のオリジンにだけ渡し (`postWebMessage` の相手)、メインフレームが denpa のオリジンの外へ移る読み込みは断る
 * - WebView は `Authorization` を付けられないので、アクセストークンがあれば WebView を作るたびに Cookie を取り直して入れる (`POST api/device/web`)
 * - WebView はフォーカスを取らない。キーは映像が受けて、開いている間だけページへ渡す (`keyDown`・`keyUp`・`press`)
 * - ページが `ready` を返さない (`READY_TIMEOUT_MS`)・エンジンを読めない (`failed`)・WebView が落ちたら、この画面では出さない (`broken`)
 */
@Stable
class DataBroadcast internal constructor(private val base: URI) {
    /** 出せるか (denpa がページを持ち、出せるものを観ている)。false ならメニューに「データ放送」を出さず、d も受けない */
    var available by mutableStateOf(false)
        internal set

    /** 開いているか (ページの `open`)。開いている間はキーをページへ渡し、実況を隠す */
    var open by mutableStateOf(false)
        private set

    /**
     * 色のボタンの列 (`DataKeyRow`) を出しているか。開いている間に決定の長押しで出し、押す・戻る・閉じるで消す。
     * 出している間は、十字・決定・戻るを列が受ける (文書へ渡さない)
     */
    var keyRow by mutableStateOf(false)
        private set

    /** 映像を置く場所 (ページの `video`) */
    var place by mutableStateOf<VideoPlace>(VideoPlace.Full)
        private set

    /** 映像の上に出しておく通知 (「データ取得中…」など。ページの `note`)。空なら出さない */
    var note by mutableStateOf("")
        private set

    /** WebView を作っている (Cookie を入れ終えた) */
    internal var loaded by mutableStateOf(false)

    /** ページがポートを受けた (`ready`) */
    internal var ready by mutableStateOf(false)
        private set

    /** 開くのを頼まれて、ページが動き出すのを待っている */
    internal var requested by mutableStateOf(false)

    /** この画面ではもう出さない (エンジンを読めない・WebView が無い・落ちた) */
    internal var broken by mutableStateOf(false)
        private set

    /** ページが受けた局 (`init`・`channel` で送ったもの)。替わったら `channel` を送る */
    internal var sentService: Long? = null
    internal var sentReceive: Boolean? = null
    internal var sentCaptions: Boolean? = null

    /** 画面から渡すもの。組み直すたびに替える (`rememberDataBroadcast`) */
    internal var hooks = Hooks()

    private var port: WebMessagePort? = null

    /** 渡したポートのうち、まだ `ready` が来ていないもの (`offer`) */
    private val offered = mutableListOf<WebMessagePort>()

    /** いまの WebView (渡し直すのは、これが替わっていない間だけ) */
    private var view: WebView? = null
    private val main = Handler(Looper.getMainLooper())

    /** ページへ渡すキー (押したのを渡したら、離したのも渡す) */
    private val keys = DataKeys()

    /** ページの場所 */
    internal val pageUrl: String? = BaseUrl.resolve(base, DataPage.PATH)?.toString()

    internal class Hooks(
        val init: () -> String? = { null },
        val onTune: (Long) -> Unit = {},
        val onAudio: (Int?) -> Unit = {},
        val onCaptions: (Boolean) -> Unit = {},
        val flash: (CharSequence) -> Unit = {},
    )

    /** ページへ1通送る (メインスレッドから送る)。まだポートが無ければ捨てる */
    fun send(json: String) {
        val p = port ?: return
        if (Looper.myLooper() == main.looper) post(p, json) else main.post { if (port === p) post(p, json) }
    }

    /**
     * ページへ1通送り、送り終えるまで待つ (読み込みのスレッドから。ライブのデータは繋いだときに数十 MB がまとめて届くので、
     * メインスレッドの列に溜め込まない)。ポートが無ければ false
     */
    internal fun sendAndWait(json: String): Boolean {
        val p = port ?: return false
        val done = CountDownLatch(1)
        main.post {
            if (port === p) post(p, json)
            done.countDown()
        }
        return done.await(SEND_WAIT_S, TimeUnit.SECONDS) && port === p
    }

    private fun post(p: WebMessagePort, json: String) {
        runCatching { p.postMessage(WebMessage(json)) }.onFailure { Log.w(TAG, "ページへ送れません: $it") }
    }

    /**
     * d。ページが動いていれば d を渡し (文書が受け付けていれば文書へ、受け付けていなければ開く・閉じる)、
     * まだ WebView が無ければ作って、動き出したら開く
     */
    fun pressD() {
        when {
            ready -> send(DataPage.D)
            broken -> hooks.flash(BROKEN_MESSAGE)
            available -> requested = true
        }
    }

    /** 開く・閉じる (メニューの「データ放送」)。d と違って文書には渡さない */
    fun toggle() {
        if (open) close() else pressD()
    }

    /** 閉じる (色のボタンの列の「閉じる」・メニュー) */
    fun close() {
        requested = false
        keyRow = false
        if (ready && open) send(DataPage.CLOSE)
    }

    /** 色のボタンの列を出す (開いている間の決定の長押し) */
    fun showKeyRow() {
        if (ready && open) keyRow = true
    }

    /** 色のボタンの列を消す (戻る) */
    fun hideKeyRow() {
        keyRow = false
    }

    /** 色のボタンの列で押した。色はそのキーを押して離したのと同じに文書へ送り、「閉じる」は閉じる。列は消す */
    fun pressRow(button: DataRowButton) {
        keyRow = false
        val code = button.code ?: return close()
        press(code)
    }

    /** 押して離したキー (決定・戻る)。開いていなければ何もしない */
    fun press(code: Int) {
        if (!ready || !open) return
        send(DataPage.key(code, true))
        send(DataPage.key(code, false))
    }

    /** キーを押した。ページへ渡したら true (TV では使わない) */
    fun keyDown(keyCode: Int, repeatCount: Int): Boolean =
        act(keys.down(keyCode, repeatCount, open = ready && open, canOpen = available || ready || broken))

    /** キーを離した。押したのをページへ渡していれば、離したのも渡して true */
    fun keyUp(keyCode: Int): Boolean = act(keys.up(keyCode))

    private fun act(action: DataKeyAction): Boolean = when (action) {
        DataKeyAction.Pass -> false
        DataKeyAction.Hold -> true
        DataKeyAction.D -> true.also { pressD() }
        is DataKeyAction.Send -> true.also { send(DataPage.key(action.code, action.down)) }
    }

    /** WebView を捨てる (受信をやめる・画面が裏に回った・観るものが替わった) */
    internal fun teardown() {
        port?.let { runCatching { it.close() } }
        port = null
        offered.forEach { runCatching { it.close() } }
        offered.clear()
        view = null
        ready = false
        loaded = false
        open = false
        keyRow = false
        requested = false
        place = VideoPlace.Full
        note = ""
        keys.clear()
        sentService = null
        sentReceive = null
        sentCaptions = null
    }

    /** もう出さない。開くのを頼まれていた・開いていたなら、そう知らせる */
    internal fun fail(why: String) {
        if (broken) return
        Log.w(TAG, "データ放送を出しません: $why")
        if (requested || open) hooks.flash(BROKEN_MESSAGE)
        broken = true
        teardown()
    }

    private fun handle(message: PageMessage?) {
        when (message) {
            null -> Unit
            PageMessage.Ready -> {
                // 観るものが無くなった (`available` が下りるのと行き違った)。init を送れないので捨てる
                val init = hooks.init() ?: return teardown()
                ready = true
                send(init)
                requested = false
            }
            is PageMessage.Failed -> fail("ページがエンジンを読み込めません (${message.reason})")
            is PageMessage.Open -> {
                if (message.on && !open) hooks.flash(OPEN_HINT)
                open = message.on
                // 文書が自分で閉じた (d を受けない文書・Hybridcast のアプリの終わり)。列も消す
                if (!message.on) keyRow = false
            }
            is PageMessage.Video -> place = message.place
            is PageMessage.Tune -> hooks.onTune(message.service)
            is PageMessage.Audio -> hooks.onAudio(message.index)
            is PageMessage.Captions -> hooks.onCaptions(message.on)
            is PageMessage.Note -> note = message.text
            is PageMessage.Toast -> hooks.flash(message.text)
        }
    }

    /**
     * 透明な WebView を作る。作れない (WebView の入っていない端末) なら空の View を返して、もう出さない
     */
    @SuppressLint("SetJavaScriptEnabled")
    internal fun makeView(context: Context): View {
        val url = pageUrl ?: return View(context).also { main.post { fail("ページの URL を組めません") } }
        val view = try {
            WebView(context)
        } catch (e: Exception) {
            return View(context).also { main.post { fail("WebView を作れません: $e") } }
        }
        view.setBackgroundColor(AndroidColor.TRANSPARENT)
        // フォーカスは映像に置いたまま (キーは映像が受けてページへ渡す)
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 放送の文書と Hybridcast のアプリは、押さずに音を鳴らす
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
        }
        view.webViewClient = object : WebViewClient() {
            /**
             * メインフレームが denpa のオリジンの外へ移る読み込みは断る (ポートを持つページを入れ替えさせない)。
             * iframe (Hybridcast のアプリ) は止めない (ページが sandbox の iframe に入れている)
             */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && !WebOrigin.same(base, request.url.toString())

            override fun onPageFinished(view: WebView, finished: String?) {
                if (port != null || offered.isNotEmpty() || finished == null || !WebOrigin.same(base, finished)) return
                offer(view, OFFER_TRIES)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) fail("ページを読めません (HTTP ${response.statusCode})")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail("ページを読めません (${error.errorCode})")
            }

            // WebView の描く側が落ちた。アプリごと落とさず、データ放送だけやめる
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fail("WebView が止まりました")
                return true
            }
        }
        this.view = view
        view.loadUrl(url)
        return view
    }

    /**
     * ページを読み終えた。ポートの片方を、相手のオリジンを denpa にしてメインフレームへ渡す。
     * **`ready` が来るまで `OFFER_EVERY_MS` ごとに渡し直す** — ページはポートの受け口を立ち上がってから置く (読み終えた合図
     * `onPageFinished` より後になることがある) ので、先に渡したものは届かずに消えうる。ページは最初に受けた1本だけを使い、
     * こちらは `ready` が来たポートを使って、ほかは閉じる
     */
    private fun offer(view: WebView, tries: Int) {
        if (port != null || this.view !== view || tries <= 0) return
        val ports = try {
            view.createWebMessageChannel()
        } catch (e: Exception) {
            return fail("ポートを作れません: $e")
        }
        val mine = ports[0]
        mine.setWebMessageCallback(
            object : WebMessagePort.WebMessageCallback() {
                override fun onMessage(from: WebMessagePort, message: WebMessage?) {
                    val read = DataPage.parse(message?.data)
                    if (port == null && read == PageMessage.Ready && from in offered) {
                        port = from
                        offered.filter { it !== from }.forEach { runCatching { it.close() } }
                        offered.clear()
                    }
                    if (from === port) handle(read)
                }
            },
            main,
        )
        offered += mine
        view.postWebMessage(WebMessage(DataPage.GREETING, arrayOf(ports[1])), Uri.parse(WebOrigin.of(base)))
        main.postDelayed({ offer(view, tries - 1) }, OFFER_EVERY_MS)
    }

    internal companion object {
        const val TAG = "DataBroadcast"

        /** 送り終えるのを待つ上限 (秒)。メインスレッドが止まっていても、読み込みのスレッドを止めたままにしない */
        const val SEND_WAIT_S = 10L

        const val BROKEN_MESSAGE = "データ放送を出せません (このテレビの WebView では動きません)"

        const val OPEN_HINT = "データ放送を開きました。決定を長押しすると、色のボタンと「閉じる」が出ます"

        /** ポートを渡し直す間 (ミリ秒) と回数 (`offer`)。合わせて `READY_TIMEOUT_MS` より短く */
        const val OFFER_EVERY_MS = 500L
        const val OFFER_TRIES = 30
    }
}

/**
 * **データ放送の受け持ち** (`DataBroadcast`)。WebView を作る・捨てる、Cookie、ページへの `init`・`channel`・`receive`・`captions`・`time`、
 * ライブのデータのストリーム (`GET api/services/<id>/data`) を受け持つ。描くのは `DataBroadcastLayer` (PlayerFrame の中)。
 *
 * @param target 観ているもの。null なら出さない (追っかけ・まだ映していない)
 * @param receiving 開く前から受信するか (自動で受信する端末で、入口に自動起動の指定がある局・録画)
 * @param subtitles 字幕を出しているか (Hybridcast のアプリに渡す)
 * @param time いまの再生の時刻。`timeKey` が変わったらすぐ、ほかは `TIME_EVERY_MS` ごとに送る
 * @param feed ライブのデータのストリーム (denpa のルートからの相対、`codec` などを付けたもの)。録画は null (ページが自分で取得する)
 * @param feedKey 変わったらストリームをリクエストし直す (映像をリクエストし直した)
 * @param canFeed ストリームをリクエストしてよいか (映像が流れはじめた。denpa は映像を流していなければ 404 を返す)
 */
/** データ放送の状態を作る。字幕のストリーム (`autoStart`) と組むので、`rememberDataBroadcast` より先に作っておく */
@Composable
fun rememberDataState(repo: Repository): DataBroadcast = remember { DataBroadcast(repo.base) }

@Composable
fun rememberDataBroadcast(
    state: DataBroadcast,
    repo: Repository,
    target: DataTarget?,
    receiving: Boolean,
    subtitles: Boolean,
    time: () -> DataTime,
    timeKey: Any?,
    flash: (CharSequence) -> Unit,
    onTune: (Long) -> Unit = {},
    onAudio: (Int?) -> Unit = {},
    onCaptions: (Boolean) -> Unit = {},
    onUnauthorized: () -> Unit,
    feed: String? = null,
    feedKey: Any? = null,
    canFeed: () -> Boolean = { true },
): DataBroadcast {
    val present by produceState(false) {
        value = try {
            repo.dataPage()
        } catch (_: Unauthorized) {
            onUnauthorized()
            false
        }
    }
    // 裏に回っている間は出さない (WebView も捨てる。戻ったら、自動で受信する局ならまた作る)
    val owner = LocalLifecycleOwner.current
    var started by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> started = false
                Lifecycle.Event.ON_START -> started = true
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val available = target != null && present && started && !state.broken
    SideEffect {
        state.available = available
        state.hooks = DataBroadcast.Hooks(
            init = {
                state.sentService = (target as? DataTarget.Live)?.service
                state.sentReceive = receiving
                state.sentCaptions = subtitles
                when (target) {
                    is DataTarget.Live -> DataPage.initLive(target.service, state.requested, receiving, subtitles)
                    is DataTarget.Recording -> DataPage.initRecording(target.id, state.requested, receiving, subtitles)
                    null -> null
                }
            },
            onTune = onTune,
            onAudio = onAudio,
            onCaptions = onCaptions,
            flash = flash,
        )
    }
    LaunchedEffect(available) {
        if (!available) state.teardown()
    }
    // 観るものの種類が替わった (録画からライブへは無いが、別の録画・ライブの局の作り直し) ら作り直す
    val kind = when (target) {
        is DataTarget.Live -> "live"
        is DataTarget.Recording -> "recording ${target.id}"
        null -> null
    }
    LaunchedEffect(kind) {
        if (state.loaded) state.teardown()
    }

    /** WebView を持っておくか */
    val keep = available && (state.requested || state.open || receiving)
    LaunchedEffect(keep) {
        if (!keep) {
            if (!state.loaded) return@LaunchedEffect
            delay(CLOSE_GRACE_MS)
            state.teardown()
            return@LaunchedEffect
        }
        if (state.loaded) return@LaunchedEffect
        // アクセストークンがあれば Cookie を取り直して入れる (信頼するネットワークの中で持たないなら要らない。ページも接続元で通る)
        if (repo.token != null) {
            val cookie = try {
                repo.api.appCookie(repo.base)
            } catch (_: Unauthorized) {
                return@LaunchedEffect onUnauthorized()
            }
            if (cookie == null) {
                if (state.requested) flash(COOKIE_MESSAGE)
                state.requested = false
                return@LaunchedEffect
            }
            if (!setCookie(repo.base, cookie)) return@LaunchedEffect state.fail("Cookie を入れられません")
        }
        state.loaded = true
    }
    // 動き出さないページ (古い WebView で読めない・denpa に届かない) は、待ち続けない
    LaunchedEffect(state.loaded, state.ready) {
        if (!state.loaded || state.ready) return@LaunchedEffect
        delay(READY_TIMEOUT_MS)
        state.fail("ページが ${READY_TIMEOUT_MS / 1000} 秒たっても動き出しません")
    }
    // 局を替えた。ページは作り直さず、エンジンだけ作り直す
    val service = (target as? DataTarget.Live)?.service
    LaunchedEffect(state.ready, service) {
        if (!state.ready || service == null || service == state.sentService) return@LaunchedEffect
        state.sentService = service
        state.send(DataPage.channel(service))
    }
    LaunchedEffect(state.ready, receiving) {
        if (!state.ready || receiving == state.sentReceive) return@LaunchedEffect
        state.sentReceive = receiving
        state.send(DataPage.receive(receiving))
    }
    LaunchedEffect(state.ready, subtitles) {
        if (!state.ready || subtitles == state.sentCaptions) return@LaunchedEffect
        state.sentCaptions = subtitles
        state.send(DataPage.captions(subtitles))
    }
    val now by rememberUpdatedState(time)
    LaunchedEffect(state.ready, timeKey) {
        if (!state.ready) return@LaunchedEffect
        while (true) {
            val t = now()
            state.send(DataPage.time(t.air, t.pos, t.rate, t.paused))
            delay(TIME_EVERY_MS)
        }
    }
    val connectable by rememberUpdatedState(canFeed)
    LaunchedEffect(state.ready, feed, feedKey) {
        if (!state.ready || feed == null) return@LaunchedEffect
        /** 続けて断られた回数 (映像がまだ開いていない・繋ぎ直しの間)。続くなら間を空ける */
        var refused = 0
        while (true) {
            delay(FEED_WAIT_MS)
            while (!connectable()) delay(FEED_WAIT_MS)
            val url = repo.url(feed)?.let { URI(it) } ?: return@LaunchedEffect
            try {
                followData(url, repo.token, state)
                refused = 0
            } catch (_: Unauthorized) {
                return@LaunchedEffect onUnauthorized()
            } catch (e: CancellationException) {
                throw e
            } catch (_: CaptionFeed.Refused) {
                if (++refused >= FEED_MAX_REFUSED) delay(FEED_SLOW_RETRY_MS)
            } catch (_: Exception) {
                // 読みの途切れ。少し待ってリクエストし直す
                refused = 0
            }
            delay(FEED_RETRY_MS)
        }
    }
    return state
}

/** Cookie を入れて書き出す。WebView の入っていない端末では入れられない (false) */
private suspend fun setCookie(base: URI, cookie: AppCookie): Boolean = try {
    val manager = CookieManager.getInstance()
    val set = suspendCancellableCoroutine { done -> manager.setCookie(base.toString(), cookie.header(base)) { done.resume(it) } }
    manager.flush()
    set
} catch (e: Exception) {
    Log.w(DataBroadcast.TAG, "Cookie を入れられません: $e")
    false
}

/**
 * ライブのデータのストリームを読み、届いたものをページへそのまま渡す。きれいに閉じたら戻る。
 * 繋いでいる間が受信のリクエスト (denpa はその間だけデータ放送をデコードする)
 */
private suspend fun followData(url: URI, token: String?, state: DataBroadcast) = coroutineScope {
    val connection = withContext(Dispatchers.IO) { CaptionFeed.connect(url, token) }
    // 読むのは止まったままになる (ブロックする) ので、取り消されたら繋がりごと切って抜けさせる
    val watcher = launch {
        try {
            awaitCancellation()
        } finally {
            connection.disconnect()
        }
    }
    try {
        withContext(Dispatchers.IO) {
            DataInputStream(connection.inputStream.buffered()).use { input ->
                while (true) {
                    ensureActive()
                    val message = when (val frame = DataFeed.read(input) ?: break) {
                        is DataFrame.Data -> DataPage.data(frame.json)
                        is DataFrame.Notice -> DataPage.notice(frame.json)
                        DataFrame.Other -> continue
                    }
                    // 送り終えるまで次を読まない (読まずに待てば denpa の側に溜まる。64 MB を超えたら denpa が閉じる)
                    if (!state.sendAndWait(message)) break
                }
            }
        }
    } finally {
        watcher.cancel()
        connection.disconnect()
    }
}

/**
 * データ放送の WebView。映像の枠 (`PlayerFrame` の映像の箱) いっぱいに重ねる。WebView を作っていなければ何も置かない
 */
@Composable
fun DataBroadcastLayer(state: DataBroadcast) {
    if (!state.loaded) return
    val context = LocalContext.current
    AndroidView(
        factory = { state.makeView(context) },
        modifier = Modifier.fillMaxSize(),
        onRelease = { view ->
            (view as? WebView)?.apply {
                stopLoading()
                destroy()
            }
        },
    )
}

/** 映像を、データ放送が言う場所 (`VideoPlace.Rect`) に縮めて置く。ほかはそのまま (枠いっぱい) */
fun Modifier.videoPlace(place: VideoPlace): Modifier = if (place !is VideoPlace.Rect) this else layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val w = (width * place.width).roundToInt().coerceAtLeast(1)
    val h = (height * place.height).roundToInt().coerceAtLeast(1)
    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
    layout(width, height) { placeable.place((width * place.left).roundToInt(), (height * place.top).roundToInt()) }
}

/** メニュー・操作の列の「データ放送」。出せないなら null (ボタンを出さない) */
fun DataBroadcast.control(onClick: () -> Unit): Control? =
    if (!available) null else Control("データ放送", on = open, icon = R.drawable.ic_data) {
        onClick()
        toggle()
    }

/**
 * **色のボタンの列** (データ放送を開いている間に決定の長押しで出す。`DataBroadcast.keyRow`)。青・赤・緑・黄と「閉じる」を
 * 操作バーと同じボタンで下の端に並べ、開いたら青にフォーカスする。左右で選び、決定で押す (`pressRow`)。戻るで消すのは呼ぶ側の戻るの受け口。
 * 長押しの続きの決定と離したのは、操作バー (`ControlBar`) が押し直すまで受けない。しばらく触らなくても消さない (押すか戻るまで出したまま。
 * 呼び出してから、文書の案内を読んでどの色を押すか決める間に消えないように)
 */
@Composable
fun BoxScope.DataKeyRow(state: DataBroadcast) {
    fun control(button: DataRowButton) =
        Control(button.label, mark = DATA_ROW_COLORS[button], description = button.description) { state.pressRow(button) }
    ControlBar(
        "データ放送",
        listOf(
            "" to DataRowButton.entries.filter { it.code != null }.map(::control),
            "" to DataRowButton.entries.filter { it.code == null }.map(::control),
        ),
    )
}

/** 色のボタンの丸の色 (リモコンの色のボタンの色) */
private val DATA_ROW_COLORS = mapOf(
    DataRowButton.BLUE to Color(0xFF2F6FEB),
    DataRowButton.RED to Color(0xFFE5392F),
    DataRowButton.GREEN to Color(0xFF2EA043),
    DataRowButton.YELLOW to Color(0xFFF5C518),
)

/**
 * 自動で受信する設定。選んでいなければ、メモリの少ない端末はオフ、ほかはオン (`lowRam`。数十 MB のモジュールを受け取り、
 * WebView を裏で動かし続けるため)
 */
@Composable
fun rememberDataAutoReceive(repo: Repository): Boolean {
    val context = LocalContext.current
    val saved by repo.app.settings.dataAutoReceive.collectAsState(initial = null)
    val fallback = remember { !lowRam(context) }
    return saved ?: fallback
}

/** 開く・受信するのをやめてから WebView を捨てるまで (ミリ秒)。局を替えて次の局の `autoStart` が届くまでに作り直さない長さ */
private const val CLOSE_GRACE_MS = 10_000L

/** ページが `ready` を返すのを待つ上限 (ミリ秒)。読み込みとエンジンの立ち上がり (遅い端末で数秒) より十分長く */
private const val READY_TIMEOUT_MS = 20_000L

/** 時刻を送り直す間 (ミリ秒)。ずれを直すため (ページは受けた組から自分で進める) */
private const val TIME_EVERY_MS = 2_000L

/** 映像が流れはじめるのを待つ刻み (ミリ秒) */
private const val FEED_WAIT_MS = 200L

/** リクエストし直すまでの間 (ミリ秒) */
private const val FEED_RETRY_MS = 3_000L

/** 続けて断られたら間を空ける回数 (字幕と同じ) */
private const val FEED_MAX_REFUSED = 5

/** 断られ続けたときの再リクエストの間 (ミリ秒) */
private const val FEED_SLOW_RETRY_MS = 30_000L

/** Cookie を取れなかったときの通知 */
private const val COOKIE_MESSAGE = "データ放送を開けません (denpa に接続できません)"

/**
 * **文書が替えた主音声を、元の TS の音声から選ぶ** (ページの `audio`)。`index` は PMT の音声の ES の何本目か (0 から)。
 * Media3 の TsExtractor は PMT の順に音声のトラックを作るので、音声のトラックの組の何番目かと同じ。null はふだんの音声に戻す
 * (選んだものを外して、Media3 の選び方に任せる)。そこまでのトラックが無ければ何もしない
 */
fun ExoPlayer.selectBroadcastAudio(index: Int?) {
    val parameters = trackSelectionParameters.buildUpon()
    if (index == null) {
        trackSelectionParameters = parameters.clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
        return
    }
    val group = currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.getOrNull(index) ?: return
    trackSelectionParameters = parameters.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
}

/** 変換したライブで、文書が替えた主音声をリクエストする `audio` (`<N>:es`。denpa の docs/api.md「ライブ」)。null なら付けない */
fun broadcastAudioQuery(index: Int?): String? = index?.let { "&audio=$it%3Aes" }
