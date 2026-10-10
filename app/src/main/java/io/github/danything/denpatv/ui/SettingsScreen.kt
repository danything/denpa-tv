package io.github.danything.denpatv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.danything.denpatv.UpdateState
import io.github.danything.denpatv.notice
import kotlinx.coroutines.launch

/**
 * 設定。**繋ぐ先と、アプリの版だけ** (下に繋いだ denpa の版)。 ライブの画質・録画の速さ・CM 飛ばしは、ブラウザの denpa と同じく
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

        // Android TV の設定と同じく、幅いっぱいの札を縦に並べる。押すと右の様子どおりのことをする
        Column(Modifier.widthIn(max = PANEL_WIDTH), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 登録してあれば denpa からも外す (トークンを無効にする)。家の LAN の繋ぎ方なら忘れるだけ
            WideButton(
                "繋ぐ先: ${repo.base}" + if (repo.token != null) " (このテレビを登録済み)" else "",
                if (repo.token != null) "押すとサーバーから外す" else "押すと繋ぐ先を変える",
                modifier = Modifier.focusRequester(button),
                onClick = {
                    scope.launch {
                        if (repo.token != null) repo.api.logout(repo.base)
                        app.settings.disconnect()
                    }
                },
            )
            // アプリの版。開いたときにも (12 時間に1回) 確かめているが、ここでは今すぐ確かめる。
            // 新しい版がある・入れられる・取ってきている・入れている・失敗した: 録画の一覧の頭と同じ1行で、押すと入れる
            val updater = app.updater
            val state by updater.state.collectAsState()
            val notice = state.notice()
            WideButton(
                "バージョン v${updater.current}" + if (updater.dev) " (開発版)" else "",
                when (val s = state) {
                    UpdateState.Checking -> "確認中…"
                    is UpdateState.UpToDate -> s.latest?.let { "最新です ($it)" } ?: "リリースがまだありません"
                    is UpdateState.DevBuild -> "開発版はアップデートしません" + (s.latest?.let { " (最新 $it)" } ?: "")
                    is UpdateState.CheckFailed -> s.message
                    // 裏で取ってきている (録画の一覧の頭には出さない)
                    is UpdateState.Preparing -> "${s.update.label} をダウンロード中 ${s.percent}%"
                    else -> notice ?: "押すとアップデートを確認"
                },
                onClick = { if (notice != null) updater.act() else updater.checkNow() },
            )
            // 繋いだ denpa の版。古すぎれば要る版を言う (止めはしない。docs/pairing.md の「要る denpa の版」)
            val warning = repo.denpaWarning
            (warning ?: repo.denpaVersion?.let { "denpa $it" })?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (warning != null) Palette.Reserved else Palette.TextMuted,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

private val PANEL_WIDTH = 760.dp
