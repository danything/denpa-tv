package io.github.danything.denpatv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
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
import androidx.tv.material3.Text
import io.github.danything.denpatv.DenpaApp
import io.github.danything.denpatv.data.BaseUrl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 繋ぐ denpa を決める。**繋がるかを確かめてから覚える** (`api/health`)。
 * 打ち間違えたまま覚えると、次に開いたときに何も出ない画面から抜けられない
 */
@Composable
fun SetupScreen(app: DenpaApp, onDone: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        text = app.settings.server.first() ?: "http://"
        focus.requestFocus()
    }

    fun submit() {
        val base = BaseUrl.normalize(text)
        if (base == null) {
            message = "URL を読めません (例: http://192.168.1.10:3000)"
            return
        }
        checking = true
        message = "確かめています…"
        scope.launch {
            if (app.api.health(base)) {
                app.settings.setServer(base.toString())
                onDone()
            } else {
                message = "$base に繋がりません。URL と、denpa の TRUSTED_NETWORKS にこのテレビの LAN が入っているかを確かめてください"
            }
            checking = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("denpa の URL", style = MaterialTheme.typography.headlineMedium)
        Text("ブラウザで denpa を開いている URL を入れてください", style = MaterialTheme.typography.bodyLarge)
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier
                .width(640.dp)
                .focusRequester(focus)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(16.dp),
        )
        Button(onClick = { submit() }, enabled = !checking) { Text("繋ぐ") }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
