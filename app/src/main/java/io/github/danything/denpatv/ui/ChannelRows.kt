package io.github.danything.denpatv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.danything.denpatv.data.SERVICE_TYPES
import io.github.danything.denpatv.data.Service
import io.github.danything.denpatv.data.airing
import io.github.danything.denpatv.data.number
import kotlinx.coroutines.launch

/**
 * ライブのメニューの、操作の列の下に並べる局。**ブラウザの denpa のライブの局の一覧と同じく、種別 (地上波 / BS / CS) ごとに分け、
 * 番号・ロゴ・局名・いま放送中の番組を出し、いま映している局に印を付ける。** ブラウザのタブにあたるものを、
 * 十字キーで辿れるよう縦に積んだ列にした (地上波の列 → BS の列 → CS の列)。左右で局を選び、決定で替える。
 *
 * 開いたときは、いま映している局の列がいちばん上に見えていて、下の列が少し覗く (帯の高さを取りすぎない)。
 * 操作の列から下キーで、いま映している局に合う (`current`)。列から列へは、真上・真下の札へ (横の送り具合は列ごとに覚える)。
 *
 * ブラウザのライブと同じく、**放送している局だけ並べる** (本放送と同じものを流しているサブチャンネルは出さない。
 * 別の番組を流せば名前が付くので出てくる)。いま映している局は放送していなくても残す
 */
@OptIn(ExperimentalFoundationApi::class)
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
    /*
     * **列ごとの横の送り具合は、列の外で覚える。** 列の中で覚えると、縦に送って枠の外に出た列 (CS に下りたときの地上波) は
     * 捨てられ、戻ると送り具合も最後に合わせた札も初めからになる
     */
    val states = remember { mutableMapOf<String, LazyListState>() }
    val scope = rememberCoroutineScope()
    /*
     * **縦は自分で送る (上の `focusedRow`) ので、札に合わせたときの送りは縦には効かせない。** テレビでは Compose の既定が
     * 「合わせたものを枠の 3 割の高さへ」なので、150dp の枠では BS に下りたあと左右に送るたびに、上に揃えた列を
     * 札の頭が 3 割に来るまで下げ、また上に揃え直して、列ががたがた上下していた
     */
    CompositionLocalProvider(LocalBringIntoViewSpec provides StayPut) {
        LazyColumn(
            state = column,
            /*
             * 局の列から操作の列へ上がったら、いま映している局の列に戻しておく (下キーで戻る先と揃える)。その列を横に送って
             * いま映している局の札が枠の外に出ていたら、札が見えるところまで戻す — 札が無いと、操作の列で下を押しても下りられない
             */
            modifier = Modifier.fillMaxWidth().height(ROWS_HEIGHT).onFocusChanged {
                if (it.hasFocus) return@onFocusChanged
                focusedRow = start
                val tunedRow = rows.getOrNull(start) ?: return@onFocusChanged
                val at = tunedRow.third.indexOfFirst { service -> service.id == current.id }
                val state = states[tunedRow.first]
                if (at >= 0 && state != null && state.layoutInfo.visibleItemsInfo.none { item -> item.index == at }) {
                    scope.launch { state.scrollToItem((at - 1).coerceAtLeast(0)) }
                }
            },
            // 最後の列 (CS) も上に揃えられるよう、下に1列ぶんに足りない分の余白
            contentPadding = PaddingValues(bottom = ROWS_HEIGHT - ROW_HEIGHT),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(rows, key = { _, it -> it.first }) { index, (type, label, row) ->
                val at = row.indexOfFirst { it.id == current.id }
                // 見えていない札には合わせられないので、いま映している局が見えるところから並べる
                val state = states.getOrPut(type) { LazyListState(firstVisibleItemIndex = (at - 1).coerceAtLeast(0)) }
                Column(Modifier.onFocusChanged { if (it.hasFocus) focusedRow = index }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFFD0D0D0))
                    /*
                     * **横は、合わせた札の左の端をいつも同じ所 (`KEYLINE`。左に1枚ぶん) に置く。** 送るたびに札が同じ所に来るので、
                     * どの列でも目の置き場が変わらない。端の札で送れないときは、送れるところまで
                     */
                    CompositionLocalProvider(LocalBringIntoViewSpec provides Keyline) {
                        LazyRow(
                            state = state,
                            // 枠は幅いっぱい (局の少ない列でも、合わせて膨らんだ札の縁と光が枠で切れないように)
                            modifier = Modifier.fillMaxWidth(),
                            /*
                             * 上下で列を移ったときは、**真上・真下の札へ** (どの列も合わせた札が `KEYLINE` に居るので、目を動かさずに済む)。
                             * 最後に合わせていた札へ戻す (`focusRestorer`) のはやめた。枠の外に出て捨てられた列では覚えが消え、
                             * 見えていない札へ跳んだり、離れた札に合ったりした
                             */
                            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
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
    }
}

/** 縦の列の枠は、札に合わせても送らない (送るのは `focusedRow` だけ) */
private val StayPut = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** 横の列で、合わせた札の左の端を `KEYLINE` に置く。送れる分は列が決める (端では止まる) */
private val Keyline = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // 札の幅が枠より広いことは無いが、念のため札の頭を見せる
        if (size >= containerSize) return offset
        val keyline = KEYLINE_FRACTION * containerSize
        return offset - keyline.coerceAtMost(containerSize - size)
    }
}

/**
 * 局の札1枚。番号・ロゴ・局名、いま放送中の番組、印 (視聴中・録画中・予約済み)。
 * ロゴはブラウザの denpa と同じく番号の右に (ロゴがまだ無い局は、そこに種別の札)。
 * いま映している局は azure の縁と淡い azure の地 (ブラウザの視聴中の行と同じ)。合わせると膨らみ、明るい azure の縁と光 (`Focus`)
 */
@Composable
private fun ChannelCard(repo: Repository, service: Service, tuned: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = CardShape
    Surface(
        onClick = onClick,
        modifier = modifier.width(CARD_WIDTH).height(CARD_HEIGHT),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (tuned) Palette.Accent.copy(alpha = 0.35f) else Palette.Surface.copy(alpha = 0.72f),
            contentColor = Palette.Text,
            focusedContainerColor = Palette.SurfaceRaised,
            focusedContentColor = Color.White,
            pressedContainerColor = Palette.SurfaceRaised,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (tuned) Border(BorderStroke(1.5.dp, Palette.Accent), shape = shape) else Border.None,
            focusedBorder = Focus.border(shape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Focus.glow),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Focus.CARD_SCALE),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(service.number?.toString() ?: "", style = MaterialTheme.typography.labelLarge)
                ChannelLogo(repo, service, Modifier.width(LOGO_WIDTH).height(LOGO_HEIGHT))
                Spacer(Modifier.weight(1f))
                val now = service.now
                when {
                    now?.recording == true -> Mark("録画中", Palette.Recording)
                    now?.reserved == true -> Mark("予約済み", Palette.Reserved)
                }
                if (tuned) {
                    Text(
                        "視聴中",
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.OnAccent,
                        modifier = Modifier.background(Palette.Accent, TagShape).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(service.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            service.now?.title?.takeIf { it.isNotBlank() }?.let { title ->
                Text(title, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * 局ロゴ (denpa が放送波から拾ったもの。`api/services/<id>/logo`)。放送のロゴは 64x36 ほどの小さな絵なので、そのくらいの大きさで。
 * **まだ拾えていない局は、ブラウザの denpa と同じく種別 (GR・BS・CS) の札**を同じ大きさで置く (局名の頭を揃える)
 */
@Composable
fun ChannelLogo(repo: Repository, service: Service, modifier: Modifier) {
    val logo = repo.url(service.logo)
    if (logo != null) {
        RemoteImage(logo, ContentScale.Fit, modifier, repo.token, placeholder = Color.Transparent)
    } else {
        Box(modifier.background(Color(0x26FFFFFF), TagShape), contentAlignment = Alignment.Center) {
            Text(service.type, style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted, maxLines = 1)
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

/** 札の中のロゴの大きさ (放送のロゴと同じ 16:9 ほど) */
private val LOGO_WIDTH = 48.dp
private val LOGO_HEIGHT = 27.dp

private val CARD_WIDTH = 220.dp
private val CARD_HEIGHT = 84.dp
private val CARD_GAP = 12.dp

/** 合わせた札の左の端を置く所 (横の枠の幅に対する割合)。左に1枚ぶんの札が見える */
private const val KEYLINE_FRACTION = 0.25f

/** 局の列を出す高さ。1列がまるごと見えて、次の列の名前と頭が少し覗く */
private val ROWS_HEIGHT = 150.dp

/** 1列の高さ (種別の名前と札。札の上下の余白も)。だいたいでよい */
private val ROW_HEIGHT = 112.dp
