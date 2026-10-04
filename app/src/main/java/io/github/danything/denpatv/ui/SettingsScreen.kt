package io.github.danything.denpatv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import io.github.danything.denpatv.DenpaApp
import io.github.danything.denpatv.data.LiveQuality
import kotlinx.coroutines.launch

/** 設定。ライブの画質・CM を飛ばすか・繋ぐ先 */
@Composable
fun SettingsScreen(app: DenpaApp, server: String, onChangeServer: () -> Unit) {
    val scope = rememberCoroutineScope()
    val saved by app.settings.liveQuality.collectAsState(initial = null)
    val skipCm by app.settings.skipCm.collectAsState(initial = true)
    val choices = LiveQuality.available(app.decoders)
    val current = LiveQuality.choose(saved, app.decoders)

    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("設定", style = MaterialTheme.typography.headlineMedium)

        Text("ライブの画質", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            choices.forEach { quality ->
                // 選んでいるものは塗った Button、ほかは枠だけ (FilterChip は tv-material でまだ実験扱い)
                val choose = { scope.launch { app.settings.setLiveQuality(quality) }; Unit }
                if (quality == current) Button(onClick = choose) { Text("✓ ${quality.label}") }
                else OutlinedButton(onClick = choose) { Text(quality.label) }
            }
        }
        Text(
            when {
                LiveQuality.Raw in choices ->
                    "低遅延は放送そのまま (MPEG-2) を流します。いちばん早く映りますが、字幕は出ません"
                else -> "この端末は MPEG-2 をハードで解けないので、低遅延は選べません"
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("録画の CM を飛ばす", style = MaterialTheme.typography.titleMedium)
            Switch(checked = skipCm, onCheckedChange = { scope.launch { app.settings.setSkipCm(it) } })
        }
        Text(
            "denpa が CM の区切りを書いて焼いた録画で効きます。上下キーでチャプターも送れます",
            style = MaterialTheme.typography.bodyMedium,
        )

        Text("繋ぐ先: $server", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onChangeServer) { Text("繋ぐ先を変える") }
    }
}
