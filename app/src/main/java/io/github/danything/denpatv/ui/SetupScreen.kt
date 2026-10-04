package io.github.danything.denpatv.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.danything.denpatv.DenpaApp
import io.github.danything.denpatv.data.ConnectStep
import io.github.danything.denpatv.data.DenpaApi
import io.github.danything.denpatv.data.PairingOutcome
import io.github.danything.denpatv.data.SetupServer
import io.github.danything.denpatv.data.approvePage
import io.github.danything.denpatv.data.connect
import io.github.danything.denpatv.data.donePage
import io.github.danything.denpatv.data.formPage
import io.github.danything.denpatv.data.pollForToken
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 繋ぐ画面のいま */
private sealed interface SetupState {
    data object Waiting : SetupState
    data object Checking : SetupState
    /** denpa にログインして、テレビを登録してもらうのを待っている */
    data class Login(val verificationUrl: String) : SetupState
}

/**
 * 繋ぐ denpa を決める。**スマホで QR を読んで URL を入れる**のが基本で、テレビのリモコンで打つこともできる。
 *
 * 1. スマホがテレビの中のページ (`SetupServer`) を開き、denpa の URL を送る
 * 2. テレビが確かめる。家の LAN から入れればそれで終わり
 * 3. 家の外の denpa なら、テレビを denpa に登録する。スマホは denpa の画面に移ってログインし、
 *    済めばテレビが受け取ったトークンを覚えて終わる (RFC 8628 の device authorization)
 */
@Composable
fun SetupScreen(app: DenpaApp) {
    var state by remember { mutableStateOf<SetupState>(SetupState.Waiting) }
    var message by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val api = remember { DenpaApi() }
    val busy = remember { Mutex() }
    var polling by remember { mutableStateOf<Job?>(null) }
    val deviceName = remember { "denpa TV (${Build.MODEL})" }

    val connectButton = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        text = app.settings.lastServer.first() ?: "http://"
        // 最初は「繋ぐ」に合わせる (入れる欄に合わせるとキーボードが開いて QR が隠れる)
        runCatching { connectButton.requestFocus() }
    }

    /** URL を確かめて先へ進める。スマホに返す HTML を返す */
    suspend fun submit(input: String): String = busy.withLock {
        polling?.cancel()
        state = SetupState.Checking
        message = "確かめています…"
        when (val step = connect(api, input, deviceName)) {
            is ConnectStep.Open -> {
                app.settings.connect(step.base.toString(), null)
                donePage()
            }
            is ConnectStep.NeedsLogin -> {
                state = SetupState.Login(step.verificationUrl)
                message = null
                polling = scope.launch {
                    val outcome = pollForToken(step.code, { api.deviceToken(step.base, step.code.deviceCode) })
                    when (outcome) {
                        is PairingOutcome.Paired -> app.settings.connect(step.base.toString(), outcome.token)
                        PairingOutcome.Denied -> message = "denpa で断られました"
                        PairingOutcome.Expired -> message = "時間切れです (10 分)。もう一度やり直してください"
                        is PairingOutcome.Failed -> message = "登録できませんでした (${outcome.reason})"
                    }
                    if (outcome !is PairingOutcome.Paired) state = SetupState.Waiting
                }
                approvePage(step.verificationUrl)
            }
            is ConnectStep.Failed -> {
                state = SetupState.Waiting
                message = step.message
                formPage(step.message)
            }
        }
    }

    // スマホから入れてもらうためのサーバ。この画面を開いている間だけ待ち受ける
    val server = remember { runCatching { SetupServer { submit(it) } }.getOrNull() }
    DisposableEffect(server) { onDispose { server?.close() } }
    val serverUrl = remember(server) { server?.url() }

    Row(
        Modifier.fillMaxSize().padding(48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (val current = state) {
            is SetupState.Login -> {
                QrCodeView(current.verificationUrl, 280.dp)
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("スマホで denpa にログインしてください", style = MaterialTheme.typography.headlineSmall)
                    Text("スマホが denpa の画面に移ります。ログインが済むと、テレビは自動で次に進みます。", style = MaterialTheme.typography.bodyLarge)
                    Text("スマホが移らないときは、左の QR か次の URL を開いてください: ${current.verificationUrl}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { polling?.cancel(); state = SetupState.Waiting }) { Text("やめる") }
                }
            }
            else -> {
                if (serverUrl != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        QrCodeView(serverUrl, 280.dp)
                        Text(serverUrl, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("denpa に繋ぐ", style = MaterialTheme.typography.headlineMedium)
                    if (serverUrl != null) {
                        Text("1. スマホをテレビと同じ Wi-Fi に繋ぎ、左の QR を読む", style = MaterialTheme.typography.bodyLarge)
                        Text("2. 開いたページに、ブラウザで denpa を開いている URL を入れる", style = MaterialTheme.typography.bodyLarge)
                        Text("3. 家の外の denpa なら、スマホが denpa の画面に移るのでログインする", style = MaterialTheme.typography.bodyLarge)
                        Text("4. テレビが自動で次に進む", style = MaterialTheme.typography.bodyLarge)
                    } else {
                        Text("ネットワークに繋がっていないため、QR を出せません", style = MaterialTheme.typography.bodyLarge)
                    }
                    Text("リモコンで入れるときはここに", style = MaterialTheme.typography.titleSmall)
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { scope.launch { submit(text) } }),
                        modifier = Modifier
                            .width(560.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(14.dp),
                    )
                    Button(
                        onClick = { scope.launch { submit(text) } },
                        enabled = state != SetupState.Checking,
                        modifier = Modifier.focusRequester(connectButton),
                    ) { Text("繋ぐ") }
                    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}
