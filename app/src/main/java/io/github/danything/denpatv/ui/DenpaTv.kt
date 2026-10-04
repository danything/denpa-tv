package io.github.danything.denpatv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import io.github.danything.denpatv.DenpaApp
import io.github.danything.denpatv.data.BaseUrl
import io.github.danything.denpatv.data.chasing
import io.github.danything.denpatv.data.Connection
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** 画面の行き先。Navigation 3 は戻る履歴をただのリストとして持つ */
@Serializable data object Main : NavKey
@Serializable data object Live : NavKey
@Serializable data class Watch(val recordingId: Long) : NavKey

@Composable
fun DenpaTv(app: DenpaApp) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            // 未設定 (null) と読み込み中を分ける。読み込み中は何も出さない
            val connection by app.settings.connection.collectAsState(initial = LOADING)
            val saved = connection
            val base = saved?.let { BaseUrl.normalize(it.server) }
            when {
                saved === LOADING -> Box(Modifier.fillMaxSize())
                saved == null || base == null -> SetupScreen(app)
                // 繋ぐ先が変わったら画面の履歴ごと作り直す
                else -> key(saved) { Navigation(app, base, saved.token) }
            }
        }
    }
}

@Composable
private fun Navigation(app: DenpaApp, base: java.net.URI, token: String?) {
    val backStack = rememberNavBackStack(Main)
    val scope = rememberCoroutineScope()
    val repo = remember(base, token) { Repository(app, base, token) }
    /** トークンが効かなくなった (外された・期限切れ)、または家の外と見なされた。忘れて繋ぐ画面へ */
    val unauthorized: () -> Unit = { scope.launch { app.settings.disconnect() } }
    // denpa の知らせ (SSE) は、アプリが前に出ている間だけ1本繋ぐ。ここはどの画面より外なので Activity の生き死にに沿う
    LifecycleStartEffect(repo) {
        val job = scope.launch { repo.listen(unauthorized) }
        onStopOrDispose { job.cancel() }
    }
    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        entryProvider = entryProvider {
            entry<Main> {
                MainScreen(
                    repo = repo,
                    onLive = { backStack.add(Live) },
                    onWatch = { backStack.add(Watch(it.id)) },
                    onUnauthorized = unauthorized,
                )
            }
            entry<Live> { LivePlayerScreen(repo, onLeave = { backStack.removeAt(backStack.lastIndex) }, onUnauthorized = unauthorized) }
            entry<Watch> { key ->
                val leave = { backStack.removeAt(backStack.lastIndex); Unit }
                // 録画中は追っかけ (伸びている生TSを denpa に流してもらう)、録り終えたものはファイルで
                val recording = remember(key) { repo.recordings.firstOrNull { it.id == key.recordingId } }
                if (recording?.chasing == true) ChasePlayerScreen(repo, recording, leave, unauthorized)
                else RecordingPlayerScreen(repo, key.recordingId, onLeave = leave, onUnauthorized = unauthorized)
            }
        },
    )
}

@Composable
fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}

/** DataStore を読み終えるまでの印 */
private val LOADING = Connection("\u0000loading", null)
