package io.github.danything.denpatv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text

/** 操作の帯の押すもの1つ。`on` は入っているか (塗って出す) */
data class Control(val label: String, val on: Boolean = false, val onClick: () -> Unit)

/**
 * 映像の下に出す操作の帯。**ブラウザの denpa の再生の操作列にあたるもの** (画質・速さ・CM 飛ばし)。
 * 設定の画面に置かないのは、ブラウザと同じく「観ながら変えて、その端末で覚える」ものだから。
 * 開いたら最初の (入っている) ものに合わせる。戻るで閉じる (呼ぶ側)
 */
@Composable
fun BoxScope.ControlBar(title: String, groups: List<Pair<String, List<Control>>>) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    var focused = false
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .background(Color(0xF0101418))
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
            groups.forEach { (name, controls) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.labelLarge, color = Color.LightGray)
                    controls.forEach { control ->
                        // 入っているものの1つ目に合わせる (無ければいちばん最初)
                        val take = !focused && (control.on || controls.none { it.on })
                        val modifier = if (take) Modifier.focusRequester(first) else Modifier
                        if (take) focused = true
                        if (control.on) Button(onClick = control.onClick, modifier = modifier) { Text(control.label) }
                        else OutlinedButton(onClick = control.onClick, modifier = modifier) { Text(control.label) }
                    }
                }
            }
        }
    }
}
