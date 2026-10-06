package io.github.danything.denpatv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
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
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("設定", style = MaterialTheme.typography.headlineMedium)

        Panel("denpa") {
            Text(
                "繋ぐ先: ${repo.base}" + if (repo.token != null) " (このテレビを登録済み)" else "",
                style = MaterialTheme.typography.titleMedium,
            )
            // 登録してあれば denpa からも外す (トークンを無効にする)。家の LAN の繋ぎ方なら忘れるだけ
            DenpaButton(modifier = Modifier.focusRequester(button), onClick = {
                scope.launch {
                    if (repo.token != null) repo.api.logout(repo.base)
                    app.settings.disconnect()
                }
            }) { Text(if (repo.token != null) "サーバーから外す" else "繋ぐ先を変える") }
        }

        // アプリの版。開いたときにも (12 時間に1回) 確かめているが、ここでは今すぐ確かめる
        Panel("アプリ") {
            val updater = app.updater
            val state by updater.state.collectAsState()
            Text("バージョン v${updater.current}" + if (updater.dev) " (開発版)" else "", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                DenpaButton(onClick = { updater.checkNow() }, primary = false) { Text("アップデートを確認") }
                when (val s = state) {
                    UpdateState.Checking -> Text("確認中…")
                    is UpdateState.UpToDate -> Text(s.latest?.let { "最新です ($it)" } ?: "リリースがまだありません")
                    is UpdateState.DevBuild -> Text("開発版はアップデートしません" + (s.latest?.let { " (最新 $it)" } ?: ""))
                    is UpdateState.CheckFailed -> Text(s.message)
                    // 裏で取ってきている (録画の一覧の頭には出さない)
                    is UpdateState.Preparing -> Text("${s.update.label} をダウンロード中 ${s.percent}%")
                    // 新しい版がある・入れられる・取ってきている・入れている・失敗した: 録画の一覧の頭と同じ1行 (押すと入れる)
                    else -> UpdateNotice(updater)
                }
            }
        }
    }
}

/** 設定の1まとまり。面の色の板に、azure の小さな見出し */
@Composable
private fun Panel(heading: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .widthIn(max = PANEL_WIDTH)
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(heading, style = MaterialTheme.typography.labelLarge, color = Palette.AccentBright)
        content()
    }
}

private val PANEL_WIDTH = 760.dp
