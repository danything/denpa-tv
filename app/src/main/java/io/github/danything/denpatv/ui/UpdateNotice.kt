package io.github.danything.denpatv.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.danything.denpatv.Updater
import io.github.danything.denpatv.notice

/**
 * 新しい版の1行。ふだんは裏で取ってきて照らし終えてから「アップデート (v0.4.0)」と出し、**押すとすぐ入れる。**
 * 裏で取れなかったときも同じ「アップデート (v0.4.0)」を出し、押すと取ってきて入れる (その進み「ダウンロード中 42%」と失敗はこの1行に出す)。
 * 知らせることが無ければ (裏で取ってきている間も) 何も出さない
 */
@Composable
fun UpdateNotice(updater: Updater, modifier: Modifier = Modifier) {
    val state by updater.state.collectAsState()
    val text = state.notice() ?: return
    Button(
        onClick = { updater.act() },
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
}
