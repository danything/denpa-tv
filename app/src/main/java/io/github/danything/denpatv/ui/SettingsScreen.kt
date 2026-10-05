package io.github.danything.denpatv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.danything.denpatv.UpdateState
import kotlinx.coroutines.launch

/**
 * 設定。**繋ぐ先と、アプリの版だけ。** ライブの画質・録画の速さ・CM 飛ばしは、ブラウザの denpa と同じく
 * 再生の画面の操作の帯 (メニュー) で変える (観ながら変えて、端末ごとに覚えるもの)
 */
@Composable
fun SettingsScreen(repo: Repository) {
    val app = repo.app
    val scope = rememberCoroutineScope()
    // 開いたら (メニューで選んだとき・ライブから戻ったとき) ボタンに合わせる。どこにも合っていないとリモコンが空振りする
    val button = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { button.requestFocus() }
    }

    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("設定", style = MaterialTheme.typography.headlineMedium)

        Text(
            "繋ぐ先: ${repo.base}" + if (repo.token != null) " (このテレビを登録済み)" else "",
            style = MaterialTheme.typography.titleMedium,
        )
        // 登録してあれば denpa からも外す (トークンを無効にする)。家の LAN の繋ぎ方なら忘れるだけ
        Button(modifier = Modifier.focusRequester(button), onClick = {
            scope.launch {
                if (repo.token != null) repo.api.logout(repo.base)
                app.settings.disconnect()
            }
        }) { Text(if (repo.token != null) "サーバーから外す" else "繋ぐ先を変える") }

        // アプリの版。開いたときにも (12 時間に1回) 確かめているが、ここでは今すぐ確かめる
        val updater = app.updater
        val state by updater.state.collectAsState()
        Text(
            "この版: v${updater.current}" + if (updater.dev) " (手元で焼いた版)" else "",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 20.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { updater.checkNow() }) { Text("アップデートを確かめる") }
            when (val s = state) {
                UpdateState.Checking -> Text("確かめています…")
                is UpdateState.UpToDate -> Text(s.latest?.let { "最新です ($it)" } ?: "リリースがまだありません")
                is UpdateState.DevBuild -> Text("手元で焼いた版なので上げません" + (s.latest?.let { " (最新は $it)" } ?: ""))
                is UpdateState.CheckFailed -> Text(s.message)
                // 新しい版がある・取ってきている・入れている・失敗した: 録画の一覧の頭と同じ1行 (押すと入れる)
                else -> UpdateNotice(updater)
            }
        }
    }
}
