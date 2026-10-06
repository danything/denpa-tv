package io.github.danything.denpatv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.danything.denpatv.data.SERVICE_TYPES
import io.github.danything.denpatv.data.Service
import io.github.danything.denpatv.data.airing
import io.github.danything.denpatv.data.number

/**
 * ライブのメニューの、操作の列の下に並べる局。**ブラウザの denpa のライブの局の一覧と同じく、種別 (地上波 / BS / CS) ごとに分け、
 * 番号・ロゴ・局名・いま放送中の番組を出し、いま映している局に印を付ける。** ブラウザのタブにあたるものを、
 * 十字キーで辿れるよう縦に積んだ列にした (地上波の列 → BS の列 → CS の列)。左右で局を選び、決定で替える。
 *
 * 開いたときは、いま映している局の列がいちばん上に見えていて、下の列が少し覗く (帯の高さを取りすぎない)。
 * 操作の列から下キーで、いま映している局に合う (`current`)。ほかの列に入ったときは、その列で最後に合わせていた局へ。
 *
 * ブラウザのライブと同じく、**放送している局だけ並べる** (本放送と同じものを流しているサブチャンネルは出さない。
 * 別の番組を流せば名前が付くので出てくる)。いま映している局は放送していなくても残す
 */
@Composable
fun ChannelRows(
    repo: Repository,
    services: List<Service>,
    current: Service,
    /** いま映している局の札。操作の列の下キーの行き先 */
    currentCard: FocusRequester,
    onSelect: (Service) -> Unit,
) {
    val onAir = airing(services).map { it.id }.toSet()
    val listed = services.filter { it.id in onAir || it.id == current.id }
    val rows = SERVICE_TYPES.mapNotNull { (type, label) ->
        listed.filter { it.type == type }.takeIf { it.isNotEmpty() }?.let { Triple(type, label, it) }
    }
    val start = rows.indexOfFirst { it.first == current.type }.coerceAtLeast(0)
    val column = rememberLazyListState(initialFirstVisibleItemIndex = start)
    /*
     * **合わせた列を、いつも枠のいちばん上に揃える。** 合わせた札を見せるだけの送り方だと、上の列の札の裾が
     * 中途半端に覗いて読みにくい
     */
    var focusedRow by remember { mutableIntStateOf(start) }
    LaunchedEffect(focusedRow) { column.animateScrollToItem(focusedRow) }
    LazyColumn(
        state = column,
        // 局の列から操作の列へ上がったら、いま映している局の列に戻しておく (下キーで戻る先と揃える)
        modifier = Modifier.fillMaxWidth().height(ROWS_HEIGHT).onFocusChanged { if (!it.hasFocus) focusedRow = start },
        // 最後の列 (CS) も上に揃えられるよう、下に1列ぶんに足りない分の余白
        contentPadding = PaddingValues(bottom = ROWS_HEIGHT - ROW_HEIGHT),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(rows, key = { _, it -> it.first }) { index, (type, label, row) ->
            val at = row.indexOfFirst { it.id == current.id }
            // 見えていない札には合わせられないので、いま映している局が見えるところから並べる
            val state = rememberLazyListState(initialFirstVisibleItemIndex = (at - 1).coerceAtLeast(0))
            Column(Modifier.onFocusChanged { if (it.hasFocus) focusedRow = index }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFFD0D0D0))
                LazyRow(
                    state = state,
                    // 列に入るときは、最後に合わせていた局へ (初めてなら、いま映している局か近いもの)
                    modifier = Modifier.focusRestorer(if (type == current.type && at >= 0) currentCard else FocusRequester.Default),
                    contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(row, key = { it.id }) { service ->
                        val tuned = service.id == current.id
                        ChannelCard(repo, service, tuned, if (tuned) Modifier.focusRequester(currentCard) else Modifier) { onSelect(service) }
                    }
                }
            }
        }
    }
}

/** 局の札1枚。番号・ロゴ・局名、いま放送中の番組、印 (視聴中・録画中・予約済み) */
@Composable
private fun ChannelCard(repo: Repository, service: Service, tuned: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(CARD_WIDTH).height(CARD_HEIGHT),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (tuned) Color(0x66FFFFFF) else Color(0x33FFFFFF),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(service.number?.toString() ?: "", style = MaterialTheme.typography.labelLarge)
                repo.url(service.logo)?.let { logo ->
                    RemoteImage(logo, ContentScale.Fit, Modifier.width(40.dp).height(22.dp), repo.token)
                }
                Spacer(Modifier.weight(1f))
                val now = service.now
                when {
                    now?.recording == true -> Mark("録画中", Color(0xFFE53935))
                    now?.reserved == true -> Mark("予約済み", Color(0xFFFFB300))
                }
                if (tuned) Text("視聴中", style = MaterialTheme.typography.labelSmall)
            }
            Text(service.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            service.now?.title?.takeIf { it.isNotBlank() }?.let { title ->
                Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 色の点と短い札 (録画中・予約済み) */
@Composable
private fun Mark(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private val CARD_WIDTH = 220.dp
private val CARD_HEIGHT = 84.dp

/** 局の列を出す高さ。1列がまるごと見えて、次の列の名前と頭が少し覗く */
private val ROWS_HEIGHT = 150.dp

/** 1列の高さ (種別の名前と札。札の上下の余白も)。だいたいでよい */
private val ROW_HEIGHT = 112.dp
