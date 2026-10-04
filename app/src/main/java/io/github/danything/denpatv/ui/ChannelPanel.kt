package io.github.danything.denpatv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.danything.denpatv.data.SERVICE_TYPES
import io.github.danything.denpatv.data.Service
import io.github.danything.denpatv.data.number

/**
 * ライブの中の局の一覧。**denpa の画面のライブと同じく、種別 (地上波 / BS / CS) で切り替え、
 * 番号・ロゴ・局名・いま放送中の番組を並べ、いま映している局に印を付ける。**
 *
 * 開いたときは、いま映している局の種別で、その局に合わせる (局が 100 を超えても探させない)
 */
@Composable
fun ChannelPanel(
    repo: Repository,
    services: List<Service>,
    current: Service?,
    onSelect: (Service) -> Unit,
) {
    val types = SERVICE_TYPES.filter { (type, _) -> services.any { it.type == type } }
    var shown by remember { mutableStateOf(current?.type ?: types.firstOrNull()?.first ?: "GR") }
    val listed = services.filter { it.type == shown }
    val list = rememberLazyListState()
    val currentRow = remember { FocusRequester() }
    val firstRow = remember { FocusRequester() }

    LaunchedEffect(shown) {
        val index = listed.indexOfFirst { it.id == current?.id }
        if (index >= 0) list.scrollToItem(index)
        withFrameNanos { }
        runCatching { if (index >= 0) currentRow.requestFocus() else firstRow.requestFocus() }
    }

    // 左右で種別を切り替える (一覧の行から上の札まで戻らなくてよいように)
    fun shift(step: Int) {
        val at = types.indexOfFirst { it.first == shown }
        types.getOrNull(at + step)?.let { shown = it.first }
    }
    Box(
        Modifier
            .fillMaxHeight()
            .width(560.dp)
            .background(Color(0xF0101418))
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.DirectionRight -> { shift(1); true }
                    Key.DirectionLeft -> { shift(-1); true }
                    else -> false
                }
            },
    ) {
        Column(Modifier.padding(start = 32.dp, end = 24.dp, top = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // 種別の札。左右キーで切り替わる (札そのものには合わせない)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("◀", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
                types.forEach { (type, label) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (type == shown) Color.White else Color.Gray,
                        modifier = if (type == shown) {
                            Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), androidx.compose.foundation.shape.RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 6.dp)
                        } else {
                            Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        },
                    )
                }
                Text("▶", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
            }
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(listed, key = { _, it -> it.id }) { index, service ->
                    val tuned = service.id == current?.id
                    ListItem(
                        selected = tuned,
                        onClick = { onSelect(service) },
                        modifier = when {
                            tuned -> Modifier.focusRequester(currentRow)
                            index == 0 -> Modifier.focusRequester(firstRow)
                            else -> Modifier
                        },
                        leadingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(service.number?.toString() ?: "", modifier = Modifier.width(40.dp), style = MaterialTheme.typography.labelLarge)
                                val logo = repo.url(service.logo)
                                if (logo != null) {
                                    RemoteImage(logo, ContentScale.Fit, Modifier.width(64.dp).height(36.dp), repo.token)
                                }
                            }
                        },
                        supportingContent = service.now?.title?.takeIf { it.isNotBlank() }?.let { title ->
                            { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        },
                        trailingContent = if (tuned) { { Text("視聴中", style = MaterialTheme.typography.labelMedium) } } else null,
                        headlineContent = { Text(service.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth()) },
                    )
                }
            }
        }
    }
}
