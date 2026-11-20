package io.github.danything.denpatv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.danything.denpatv.data.LicenseManifest
import io.github.danything.denpatv.data.Licenses
import io.github.danything.denpatv.data.Licenses.licenseName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 設定の「ライセンス」。いちばん上にこのアプリ (AGPL とソースコードの置き場)、その下に APK に入っているものを
 * 設定と同じ幅いっぱいのボタンで並べ、押すと全文 (`LicenseTextScreen`) を開く。中身は assets の `licenses/` (`data/Licenses.kt`)
 */
@Composable
fun LicensesScreen(onOpen: (Int) -> Unit) {
    val manifest = rememberLicenseManifest()
    // 開いたら頭に、全文から戻ったら開いていたものにフォーカスする (どこにもフォーカスが無いとリモコンが空振りする)
    var last by rememberSaveable { mutableIntStateOf(0) }
    val focus = remember(manifest) { List(manifest?.components?.size ?: 0) { FocusRequester() } }
    LaunchedEffect(focus) {
        if (focus.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        runCatching { focus[last.coerceIn(focus.indices)].requestFocus() }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("ライセンス", style = MaterialTheme.typography.headlineMedium)
        Column(Modifier.widthIn(max = PANEL_WIDTH), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            manifest?.components?.forEachIndexed { index, component ->
                WideButton(
                    component.name,
                    listOfNotNull(manifest.licenseName(component), component.source?.let { "ソースコード: $it" }).joinToString("  ・  "),
                    modifier = Modifier.focusRequester(focus[index]),
                    onClick = {
                        last = index
                        onOpen(index)
                    },
                )
            }
        }
    }
}

/**
 * ライセンスの全文。**文の面にフォーカスを置き、上下キーで 1 画面の 8 割ずつ送る** (文は押せるものではないので、
 * 段落ごとにフォーカスを渡すより、面ごと送るほうがリモコンで速く読める)。戻るで一覧へ
 */
@Composable
fun LicenseTextScreen(index: Int) {
    val context = LocalContext.current
    val manifest = rememberLicenseManifest()
    val component = manifest?.components?.getOrNull(index)
    val text by produceState<String?>(null, component) {
        if (component != null) value = withContext(Dispatchers.IO) { runCatching { Licenses.text(context.assets, component) }.getOrNull() }
    }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val panel = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(text) {
        if (text == null) return@LaunchedEffect
        withFrameNanos { }
        runCatching { panel.requestFocus() }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (component == null) return@Column
        Text(component.name, style = MaterialTheme.typography.headlineMedium)
        Text(
            listOfNotNull(manifest.licenseName(component), component.source?.let { "ソースコード: $it" }).joinToString("  ・  "),
            style = MaterialTheme.typography.bodyLarge,
            color = Palette.TextMuted,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Palette.Surface, CardShape)
                .border(2.dp, if (focused) Palette.AccentBright else Color.Transparent, CardShape)
                .focusRequester(panel)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = scroll.viewportSize * PAGE
                    when (it.key) {
                        Key.DirectionDown -> scope.launch { scroll.animateScrollBy(step) }
                        Key.DirectionUp -> scope.launch { scroll.animateScrollBy(-step) }
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                }
                .focusable()
                .verticalScroll(scroll),
        ) {
            Text(
                text ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Text,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }
}

/** assets の一覧を読む (小さいので画面ごとに読み直す)。読み終えるまで null */
@Composable
private fun rememberLicenseManifest(): LicenseManifest? {
    val context = LocalContext.current
    val manifest by produceState<LicenseManifest?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { Licenses.load(context.assets) }.getOrNull() }
    }
    return manifest
}

/** 上下キー1回で送る量 (見えている高さに対して)。前の画面の終わりが少し残るように */
private const val PAGE = 0.8f
