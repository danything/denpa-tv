package io.github.danything.denpatv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** 画面の行き先。Navigation 3 は戻る履歴をただのリストとして持つ */
@Serializable data object Home : NavKey
@Serializable data object Setup : NavKey
@Serializable data object Prefs : NavKey
@Serializable data class Live(val serviceId: Long) : NavKey
@Serializable data class Watch(val recordingId: Long) : NavKey

@Composable
fun DenpaTv(app: DenpaApp) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            // 未設定 (null) と読み込み中を分ける。読み込み中は何も出さない
            val server by app.settings.server.collectAsState(initial = LOADING)
            when (val saved = server) {
                LOADING -> Box(Modifier.fillMaxSize())
                null -> SetupScreen(app, onDone = {})
                else -> {
                    val base = BaseUrl.normalize(saved)
                    if (base == null) {
                        SetupScreen(app, onDone = {})
                    } else {
                        Navigation(app, base)
                    }
                }
            }
        }
    }
}

@Composable
private fun Navigation(app: DenpaApp, base: java.net.URI) {
    val backStack = rememberNavBackStack(Home)
    val scope = rememberCoroutineScope()
    val repo = remember(base) { Repository(app, base) }
    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        entryProvider = entryProvider {
            entry<Home> {
                HomeScreen(
                    repo = repo,
                    onLive = { backStack.add(Live(it.id)) },
                    onWatch = { backStack.add(Watch(it.id)) },
                    onSettings = { backStack.add(Prefs) },
                )
            }
            entry<Prefs> { SettingsScreen(app, base.toString(), onChangeServer = { backStack.add(Setup) }) }
            entry<Setup> {
                SetupScreen(app, onDone = { scope.launch { backStack.removeAt(backStack.lastIndex) } })
            }
            entry<Live> { key -> LivePlayerScreen(repo, key.serviceId) }
            entry<Watch> { key -> RecordingPlayerScreen(repo, key.recordingId) }
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
private const val LOADING = "\u0000loading"
