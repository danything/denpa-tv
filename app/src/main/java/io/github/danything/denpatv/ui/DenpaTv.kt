package io.github.danything.denpatv.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.danything.denpatv.DenpaApp
import io.github.danything.denpatv.data.BaseUrl
import io.github.danything.denpatv.data.chasing
import io.github.danything.denpatv.data.Connection
import io.github.danything.denpatv.data.DeepLink
import io.github.danything.denpatv.data.Unauthorized
import io.github.danything.denpatv.data.findService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * 画面の行き先。Navigation 3 は戻る履歴をただのリストとして持つ。
 * `link` はリンク (`denpa://…`) で開くたびに変える — 同じ行き先でも画面を作り直す (別の局を映す・録画の一覧に戻す)
 */
@Serializable data class Main(val link: Long = 0) : NavKey
@Serializable data class Live(val link: Long = 0) : NavKey
/** 録画を観る。`fromStart` なら続きではなく頭から (詳しくの「最初から」) */
@Serializable data class Watch(val recordingId: Long, val fromStart: Boolean = false) : NavKey

@Composable
fun DenpaTv(app: DenpaApp, link: MutableState<DeepLink?>) {
    DenpaTheme(app.denpaFont.fonts) {
        val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        Surface(modifier = Modifier.fillMaxSize().backKeyGoesBack(back)) {
            // 未設定 (null) と読み込み中を分ける。読み込み中は何も出さない
            val connection by app.settings.connection.collectAsState(initial = LOADING)
            val saved = connection
            val base = saved?.let { BaseUrl.normalize(it.server) }
            when {
                saved === LOADING -> Box(Modifier.fillMaxSize())
                saved == null || base == null -> {
                    // まだ繋いでいない。リンクは捨てて、繋ぐ画面のまま
                    val context = LocalContext.current
                    LaunchedEffect(link.value) {
                        if (link.value == null) return@LaunchedEffect
                        link.value = null
                        notice(context, "先に denpa に繋いでください")
                    }
                    SetupScreen(app)
                }
                // 繋ぐ先が変わったら画面の履歴ごと作り直す
                else -> key(saved) { Navigation(app, base, saved.token, link) }
            }
        }
    }
}

@Composable
private fun Navigation(app: DenpaApp, base: java.net.URI, token: String?, link: MutableState<DeepLink?>) {
    val backStack = rememberNavBackStack(Main())
    val scope = rememberCoroutineScope()
    val repo = remember(base, token) { Repository(app, base, token) }
    /** トークンが効かなくなった (外された・期限切れ)、または家の外と見なされた。忘れて繋ぐ画面へ */
    val unauthorized: () -> Unit = { scope.launch { app.settings.disconnect() } }
    // denpa の知らせ (SSE) は、アプリが前に出ている間だけ1本繋ぐ。ここはどの画面より外なので Activity の生き死にに沿う
    LifecycleStartEffect(repo) {
        val job = scope.launch { repo.listen(unauthorized) }
        onStopOrDispose { job.cancel() }
    }
    // リンクで来たら開く。続けて来たら前のは捨てる (局や録画を探している途中でも)
    val context = LocalContext.current
    var opening by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(link.value) {
        val next = link.value ?: return@LaunchedEffect
        link.value = null
        opening?.cancel()
        opening = scope.launch { open(next, repo, backStack, unauthorized) { notice(context, it) } }
    }
    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        entryProvider = entryProvider {
            entry<Main> {
                MainScreen(
                    repo = repo,
                    onLive = { backStack.add(Live()) },
                    onWatch = { recording, fromStart -> backStack.add(Watch(recording.id, fromStart)) },
                    onUnauthorized = unauthorized,
                )
            }
            entry<Live> { LivePlayerScreen(repo, onLeave = { backStack.removeAt(backStack.lastIndex) }, onUnauthorized = unauthorized) }
            entry<Watch> { key ->
                val leave = { backStack.removeAt(backStack.lastIndex); Unit }
                // 録画中は追っかけ (伸びている生TSを denpa に流してもらう)、録り終えたものはファイルで
                // 「最初から」は続きの位置を持たないものとして開く (頭から流し、観た位置はいつもどおり預ける)
                val recording = remember(key) {
                    repo.recordings.firstOrNull { it.id == key.recordingId }?.let { if (key.fromStart) it.copy(resumeMs = null) else it }
                }
                when {
                    recording == null -> Centered("録画が見つかりません")
                    recording.chasing -> ChasePlayerScreen(repo, recording, leave, unauthorized)
                    else -> RecordingPlayerScreen(repo, recording, leave, unauthorized)
                }
            }
        },
    )
}

/**
 * リンクの行き先を開く。**いまの再生の画面は置き換え、積み増さない。** 戻るで行く先は、ライブならメニュー、録画なら録画の一覧。
 * 局・録画が見つからなければ、ライブ・一覧をふつうに開いて1行知らせる
 */
private suspend fun open(
    link: DeepLink,
    repo: Repository,
    backStack: NavBackStack<NavKey>,
    unauthorized: () -> Unit,
    notice: (String) -> Unit,
) {
    val stamp = System.nanoTime()
    try {
        when (link) {
            is DeepLink.Live -> {
                val channel = link.channel
                if (channel != null) {
                    val fetched = if (repo.services.isEmpty() || repo.servicesStale) refreshQuietly { repo.refreshServices() } else true
                    // ライブは最後に観ていた局から映す。そこを替えて開く
                    val service = findService(repo.services, channel)
                    when {
                        service != null -> repo.app.settings.setLastService(service.id)
                        // 取れなかったのに「見つからない」とは言わない
                        repo.services.isEmpty() && !fetched -> notice("局の一覧を取れませんでした")
                        else -> notice("${channel.take(NOTICE_MAX)} という局が見つかりませんでした")
                    }
                }
                backStack.subList(1, backStack.size).clear()
                backStack.add(Live(stamp))
            }
            DeepLink.Recordings -> backStack.replaceAll(Main(stamp))
            is DeepLink.Watch -> {
                val id = link.id.toLongOrNull()
                val fetched = if (id != null && repo.recordings.none { it.id == id }) refreshQuietly { repo.refreshRecordings() } else true
                if (id != null && repo.recordings.any { it.id == id }) backStack.replaceAll(Main(stamp), Watch(id))
                else {
                    notice(if (fetched) "録画 ${link.id.take(NOTICE_MAX)} が見つかりませんでした" else "録画の一覧を取れませんでした")
                    backStack.replaceAll(Main(stamp))
                }
            }
        }
    } catch (_: Unauthorized) {
        unauthorized()
    }
}

/** 取り直す。取れなくても続ける (手元の一覧で探す) — 取れたかを返す。401 と取り消し (次のリンクが来た) は上へ */
private suspend fun refreshQuietly(block: suspend () -> Unit): Boolean =
    try {
        block()
        true
    } catch (e: Unauthorized) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

/** 知らせに入れるリンクの文字の長さ (ほかのアプリから長いものを投げられても1行に収める) */
private const val NOTICE_MAX = 40

private fun NavBackStack<NavKey>.replaceAll(vararg keys: NavKey) {
    // 先に足してから消す (空になる瞬間を作らない)
    val old = size
    addAll(keys)
    subList(0, old).clear()
}

/** 1行の知らせ (リンクの局が見つからないなど)。画面をまたいでも出るよう Toast で */
private fun notice(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

@Composable
fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * **戻るキーは、合いを動かすのに使わせず、そのまま「戻る」にする** (`BackHandler` が受ける)。
 *
 * Android 12 以前 (予測型の戻るが無い) では、戻るキーはまず画面の部品に届き、Compose は合いのあるところから
 * **合いを外へ出す** (FocusDirection.Exit) のに使ってしまう。そうなると、ライブのメニューや操作の帯を開いて戻るを押しても
 * 帯は閉じず、合いだけが帯の外へ抜けて (どこにも合わずに) リモコンが効かなくなったり、帯を飛ばして画面ごと戻ったりした。
 * Android 13 以降の予測型の戻るでは、もともと戻るキーは部品に届かない。どちらでも同じ動きにする。
 * 押し続けた繰り返しは捨て、離したときに1回だけ戻る
 */
private fun Modifier.backKeyGoesBack(dispatcher: OnBackPressedDispatcher?): Modifier = onPreviewKeyEvent { event ->
    if (event.key != Key.Back || dispatcher == null) return@onPreviewKeyEvent false
    if (event.type == KeyEventType.KeyUp && !event.nativeKeyEvent.isCanceled) dispatcher.onBackPressed()
    true
}

/** DataStore を読み終えるまでの印 */
private val LOADING = Connection("\u0000loading", null)
