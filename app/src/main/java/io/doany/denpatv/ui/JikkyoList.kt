package io.doany.denpatv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.doany.denpatv.data.JikkyoComment
import io.doany.denpatv.data.JikkyoList
import io.doany.denpatv.data.commentKey
import kotlinx.coroutines.delay

/**
 * **右の列の「コメントの一覧」と勢い** (ブラウザの denpa の `JikkyoList.svelte` にあたる。決まりは `data/JikkyoList.kt`)。
 *
 * - 見出しの横に勢い (W までの 1 分に書かれた数)。新しいものが下で、いつも「いま」を出す (**フォーカスは入れない**。巻いて読み返すのはリモコンに向かない)
 * - 0.25 秒おきに、流れるほうと同じ放送時刻 W で控えを読み直す。同じ並びなら描き直さない
 * - 映像は左に寄せて縮め (`PlayerFrame`)、コメントと字幕はその縮めた映像の中に流す
 */
@Composable
fun JikkyoListColumn(state: JikkyoState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val most = remember { if (lowRam(context)) JikkyoList.MAX_LOW_RAM else JikkyoList.MAX }
    var rows by remember { mutableStateOf(emptyList<JikkyoComment>()) }
    var force by remember { mutableIntStateOf(0) }
    val current by rememberUpdatedState(state)
    LaunchedEffect(Unit) {
        while (true) {
            current.air.now()?.let { w ->
                val next = current.feed.recent(w, JikkyoList.SPAN_MS, most)
                if (!JikkyoList.sameRows(rows, next)) rows = next
                force = current.feed.force(w, JikkyoList.FORCE_SPAN_MS)
            }
            delay(JikkyoList.EVERY_MS)
        }
    }
    Column(
        modifier
            .fillMaxHeight()
            .background(Palette.Background)
            .focusProperties { canFocus = false }
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // 見出しは「コメント」だけ (操作の列のボタン「コメントの一覧」と同じ字にしない)
            Text("コメント", style = MaterialTheme.typography.titleSmall, color = Palette.Text)
            Text("勢い $force", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
        }
        // 新しいものが下。下から並べるので、増えても「いま」(いちばん下) に付いたまま
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            reverseLayout = true,
            userScrollEnabled = false,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(rows.asReversed(), key = ::commentKey) { comment ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(JikkyoList.airClock(comment.at), style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted, maxLines = 1)
                    Text(comment.text, style = MaterialTheme.typography.bodySmall, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
