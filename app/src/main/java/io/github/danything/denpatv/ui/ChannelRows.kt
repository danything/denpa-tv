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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.danything.denpatv.data.JikkyoRanking
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
 * **実況のコメントを流せる** (`ranking` がある: 世帯の設定と端末の設定の両方がオンで、denpa が新しい) **なら、最後に「実況」の列** (ブラウザの「実況」タブ)。
 * 実況のある局を勢いの順に並べ (`data/JikkyoRanking.kt`)、番組名の頭に「勢い 412」を添える。ほかの列には添えない。
 * 開いたときはこの列が上に来て、いま映している局のカード (無ければ列の頭) にフォーカスする (ブラウザも「実況」タブで開く)。
 * 並べ直しは、この列にフォーカスがある間は待ち、離れて 1 秒たってから (押そうとしたカードが動いて別の局を選ばないように)。
 * 勢いを読めないときは番号順で、列の名前の横に「いまは勢いを読めません」
 *
 * 開いたときは、いま映している局の列 (実況の列があればそれ) がいちばん上に見えていて、下の列の名前とカードの頭が覗く (操作バーの高さを取りすぎない)。
 * 覗いたカードは下の端で溶かす (録画の一覧の格子の下の端と同じ)。カードの縁だけが細く残ると、テレビの縁で切れたように見える
 * 操作の列から下キーで、その列のいま映している局 (`currentCard`) にフォーカスが移る。列から列へは、真上・真下のカードへ (横の送り具合は列ごとに覚える)。
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
    /** 開いたときにフォーカスする列のカード (いま映している局、実況の列で無ければ頭)。操作の列の下キーの行き先 */
    currentCard: FocusRequester,
    /** 局の勢い。実況のコメントを流せないなら null (実況の列を出さない) */
    ranking: JikkyoRankingState? = null,
    onSelect: (Service) -> Unit,
) {
    val onAir = airing(services).map { it.id }.toSet()
    val listed = services.filter { it.id in onAir || it.id == current.id }
    val typeRows = SERVICE_TYPES.mapNotNull { (type, label) ->
        listed.filter { it.type == type }.takeIf { it.isNotEmpty() }?.let { ChannelRow(type, label, it) }
    }
    val rankingRow = ranking?.let { state ->
        JikkyoRanking.candidates(listed).takeIf { it.isNotEmpty() }?.let { ChannelRow(RANKING_KEY, JikkyoRanking.LABEL, ranked(it, state.order), state) }
    }
    val rows = typeRows + listOfNotNull(rankingRow)
    val start = if (rankingRow != null) rows.lastIndex else rows.indexOfFirst { it.key == current.type }.coerceAtLeast(0)
    val column = rememberLazyListState(initialFirstVisibleItemIndex = start)
    /*
     * **高さは種別の名前の行の高さから組み立てる** (だいたいの数にしない)。テレビの文字の拡大で名前の行が高くなっても、
     * 1列がまるごと入り、次の列の名前とカードの頭が同じだけ覗く
     */
    val labelLine = with(LocalDensity.current) { MaterialTheme.typography.labelMedium.lineHeight.toDp() }
    val rowHeight = labelLine + LABEL_GAP + ROW_PADDING * 2 + CARD_HEIGHT
    val rowsHeight = rowHeight + ROW_GAP + labelLine + LABEL_GAP + ROW_PADDING + CARD_PEEK
    /*
     * **フォーカスした列を、いつも枠のいちばん上に揃える。** フォーカスしたカードを見せるだけの送り方だと、上の列のカードの裾が
     * 中途半端に覗いて読みにくい
     */
    var focusedRow by remember { mutableIntStateOf(start) }
    LaunchedEffect(focusedRow) { column.animateScrollToItem(focusedRow) }
    /** 実況の列にフォーカスがあるか (並べ直したあと、フォーカスの無いときだけ送り直す) */
    var rankingFocused by remember { mutableStateOf(false) }
    /*
     * メニューを閉じた・実況の列が消えた (実況をオフにした・並べる局が無くなった) ら、待っていた並びを入れ、フォーカスの印も消す
     * (フォーカスのあるまま列ごと消えると、離れた合図が来ないので並べ直しを待ち続ける)。次に開いたときは新しい並び
     */
    DisposableEffect(ranking, rankingRow != null) {
        onDispose {
            rankingFocused = false
            ranking?.release()
        }
    }
    /** 実況の列の、前に見た並び。並びが変わったときだけ送り直す (縦の枠から外れて合成し直しただけでは、横の送り具合を巻き戻さない) */
    val shownRanking = remember { mutableStateOf<List<Long>?>(null) }
    /*
     * **列ごとの横の送り具合は、列の外で覚える。** 列の中で覚えると、縦に送って枠の外に出た列 (CS に下りたときの地上波) は
     * 捨てられ、戻ると送り具合も最後にフォーカスしたカードも初めからになる
     */
    val states = remember { mutableMapOf<String, LazyListState>() }
    val scope = rememberCoroutineScope()
    /*
     * **縦は自分で送る (上の `focusedRow`) ので、カードにフォーカスしたときの送りは縦には効かせない。** テレビでは Compose の既定が
     * 「フォーカスしたものを枠の 3 割の高さへ」なので、効かせると左右に送るたびに
     * 上に揃えた列が上下に揺れる
     */
    CompositionLocalProvider(LocalBringIntoViewSpec provides StayPut) {
        LazyColumn(
            state = column,
            /*
             * 局の列から操作の列へ上がったら、開いたときの列に戻しておく (下キーで戻る先と揃える)。その列を横に送って
             * 行き先のカードが枠の外に出ていたら、カードが見えるところまで戻す — カードが無いと、操作の列で下を押しても下りられない
             */
            modifier = Modifier.fillMaxWidth().height(rowsHeight).fadeBottom(focusedRow < rows.lastIndex).onFocusChanged {
                if (it.hasFocus) return@onFocusChanged
                focusedRow = start
                val startRow = rows.getOrNull(start) ?: return@onFocusChanged
                val at = startRow.focusIndex(current.id)
                val state = states[startRow.key]
                if (at >= 0 && state != null && state.layoutInfo.visibleItemsInfo.none { item -> item.index == at }) {
                    scope.launch { state.scrollToItem((at - 1).coerceAtLeast(0)) }
                }
            },
            // 最後の列も上に揃えられるよう、下に1列ぶんに足りない分の余白
            contentPadding = PaddingValues(bottom = rowsHeight - rowHeight),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
        ) {
            itemsIndexed(rows, key = { _, it -> it.key }) { index, row ->
                val at = row.focusIndex(current.id)
                // 見えていないカードにはフォーカスできないので、行き先のカードが見えるところから並べる
                val state = states.getOrPut(row.key) { LazyListState(firstVisibleItemIndex = (at - 1).coerceAtLeast(0)) }
                if (row.ranking != null) {
                    /*
                     * 並べ直したら、フォーカスが無ければ行き先のカードが見えるところまで送り直す。送り具合は前に頭にあったカードに付いて行くので、
                     * そのままだと行き先のカードが枠の外に出て、操作の列から下りられなくなる
                     */
                    val ids = row.services.map { it.id }
                    LaunchedEffect(ids) {
                        val before = shownRanking.value
                        shownRanking.value = ids
                        if (before != null && before != ids && !rankingFocused && at >= 0) state.scrollToItem((at - 1).coerceAtLeast(0))
                    }
                }
                Column(
                    Modifier.onFocusChanged {
                        if (it.hasFocus) focusedRow = index
                        if (row.ranking != null && rankingFocused != it.hasFocus) {
                            rankingFocused = it.hasFocus
                            row.ranking.hold(it.hasFocus)
                        }
                    },
                    verticalArrangement = Arrangement.spacedBy(LABEL_GAP),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.label, style = MaterialTheme.typography.labelMedium, color = Palette.OnScrimMuted)
                        if (row.ranking?.failed == true) {
                            Text(JikkyoRanking.FAILED, style = MaterialTheme.typography.labelMedium, color = Palette.OnScrimMuted)
                        }
                    }
                    /*
                     * **横は、フォーカスしたカードの左の端をいつも同じ所 (`KEYLINE`。左に1枚ぶん) に置く。** 送るたびにカードが同じ所に来るので、
                     * どの列でも目の置き場が変わらない。端のカードで送れないときは、送れるところまで
                     */
                    CompositionLocalProvider(LocalBringIntoViewSpec provides Keyline) {
                        LazyRow(
                            state = state,
                            // 枠は幅いっぱい (局の少ない列でも、フォーカスで膨らんだカードの縁と光が枠で切れないように)。送れる側の端は溶かす
                            modifier = Modifier.fillMaxWidth().fadeEdges(state),
                            /*
                             * 上下で列を移ったときは、**真上・真下のカードへ** (どの列もフォーカスしたカードが `KEYLINE` にあるので、目を動かさずに済む)。
                             * 最後にフォーカスしていたカードへ戻す (`focusRestorer`) のはやめた。枠の外に出て捨てられた列では覚えが消え、
                             * 見えていないカードへ飛んだり、離れたカードにフォーカスが移ったりした
                             */
                            // 上下は、フォーカスしたカードの膨らみと光が端を溶かす層 (`fadeEdges`) で切れないだけ空ける
                            contentPadding = PaddingValues(vertical = ROW_PADDING, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
                        ) {
                            itemsIndexed(row.services, key = { _, it -> it.id }) { position, service ->
                                val tuned = service.id == current.id
                                // 行き先のカード (開いたときの列の、いま映している局か頭) だけに付ける。同じ局が実況の列にもあるので、1枚に絞る
                                val target = index == start && position == at
                                val force = row.ranking?.forces?.get(service.id)
                                ChannelCard(repo, service, tuned, force, if (target) Modifier.focusRequester(currentCard) else Modifier) { onSelect(service) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 局の列1本。`ranking` は実況の列 (勢いを添えて、フォーカスの間は並べ直しを待つ) だけ */
private class ChannelRow(val key: String, val label: String, val services: List<Service>, val ranking: JikkyoRankingState? = null) {
    /** 開いたときのフォーカスの行き先: いま映している局のカード。実況の列でその局が無ければ頭。無ければ -1 */
    fun focusIndex(currentId: Long): Int =
        services.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: if (ranking != null && services.isNotEmpty()) 0 else -1
}

/** 実況の列の key (種別の列の key と重ならない) */
private const val RANKING_KEY = "jikkyo"

/** 勢いの並び (`order`) に並べ替える。並びに無い局 (取ったあとに増えた局) は末尾に番号順で。並びがまだ無ければ番号順のまま */
private fun ranked(services: List<Service>, order: List<Long>?): List<Service> {
    if (order == null) return services
    val at = order.withIndex().associate { (index, id) -> id to index }
    return services.sortedBy { at[it.id] ?: Int.MAX_VALUE }
}

/** 縦の列の枠は、カードにフォーカスしても送らない (送るのは `focusedRow` だけ) */
private val StayPut = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** 横の列で、フォーカスしたカードの左の端を `KEYLINE` に置く。送れる分は列が決める (端では止まる) */
private val Keyline = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // カードの幅が枠より広いことは無いが、念のためカードの頭を見せる
        if (size >= containerSize) return offset
        val keyline = KEYLINE_FRACTION * containerSize
        return offset - keyline.coerceAtMost(containerSize - size)
    }
}

/**
 * 局のカード1枚。番号・ロゴ・局名、いま放送中の番組、印 (視聴中・録画中・予約済み)。
 * ロゴはブラウザの denpa と同じく番号の右に (ロゴがまだ無い局は、そこに種別のラベル)。
 * いま映している局は azure の縁と淡い azure の地 (ブラウザの視聴中の行と同じ)。フォーカスすると膨らみ、明るい azure の縁と光 (`Focus`)
 * 実況の列のカードは、番組名の頭に勢い (`force`。1 分あたりのコメント数) を「勢い 412・番組名」と添える (ブラウザの「実況」タブの行と同じ)
 */
@Composable
private fun ChannelCard(repo: Repository, service: Service, tuned: Boolean, force: Int?, modifier: Modifier, onClick: () -> Unit) {
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
                    now?.reserved == true -> Mark(RESERVED, Palette.Reserved)
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
            Text(service.name, style = MaterialTheme.typography.titleSmall.broadcast(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val title = service.now?.title?.takeIf { it.isNotBlank() }
            if (title != null || force != null) {
                val line = buildAnnotatedString {
                    if (force != null) append("勢い $force")
                    if (force != null && title != null) append("・")
                    title?.let { appendBroadcast(it) }
                }.withBroadcastFont()
                Text(line, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * 局ロゴ (denpa が放送波から取得したもの。`api/services/<id>/logo`)。放送のロゴは 64x36 ほどの小さな画像なので、そのくらいの大きさで。
 * **まだ取得できていない局は、ブラウザの denpa と同じく種別 (GR・BS・CS) のラベル**を同じ大きさで置く (局名の頭を揃える)
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

/** 色の点と短いラベル (録画中・予約済み) */
@Composable
private fun Mark(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** カードの中のロゴの大きさ (放送のロゴと同じ 16:9 ほど) */
private val LOGO_WIDTH = 48.dp
private val LOGO_HEIGHT = 27.dp

private val CARD_WIDTH = 220.dp
private val CARD_HEIGHT = 92.dp
private val CARD_GAP = 12.dp

/** フォーカスしたカードの左の端を置く所 (横の枠の幅に対する割合)。左に1枚ぶんのカードが見える */
private const val KEYLINE_FRACTION = 0.25f

/** 列の間と、種別の名前とカードの列の間 */
private val ROW_GAP = 4.dp
private val LABEL_GAP = 2.dp

/** カードの列の上下の余白 (フォーカスしたカードの膨らみと光が、端を溶かす層で切れないだけ) */
private val ROW_PADDING = 12.dp

/**
 * 次の列のカードを覗かせる高さ。番号とロゴの行が半分ほど見えて、カードの列だと分かるだけ (下に続きがあると分かる)。
 * 下の端の溶かし (`BOTTOM_FADE`) より短くし、溶かしが次の列の名前に掛からないようにする
 */
private val CARD_PEEK = 28.dp

/** 下の端を溶かす高さ (録画の一覧の格子の下の端と同じ) */
private val BOTTOM_FADE = 40.dp

/**
 * **下に続きの列があるとき、下の端を透かして消す** (覗いた次の列のカードが、テレビの縁で切れたように見えないように)。
 * 最後の列では溶かさない。透かし方は横の `fadeEdges` と同じ (別の層に描いてから端だけ抜く)
 */
private fun Modifier.fadeBottom(enabled: Boolean): Modifier =
    if (!enabled) this
    else graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = (BOTTOM_FADE.toPx() / size.height).coerceAtMost(1f)
            drawRect(Brush.verticalGradient(0f to Color.Black, 1f - fade to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }

/**
 * **横に送れる側の端を透かして消す** (端のカードが字の途中でぶつりと切れて見えないように。続きがあるのも分かる)。
 * 送れない側 (列の頭・終わり) は透かさない。透かしは別の層に描いてから端だけ抜く (`DstIn`)。層の外は切れるので、
 * 列の上下はカードの光のぶん空けてある
 */
private fun Modifier.fadeEdges(state: LazyListState): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = (EDGE_FADE.toPx() / size.width).coerceAtMost(0.5f)
            val start = if (state.canScrollBackward) Color.Transparent else Color.Black
            val end = if (state.canScrollForward) Color.Transparent else Color.Black
            drawRect(Brush.horizontalGradient(0f to start, fade to Color.Black, 1f - fade to Color.Black, 1f to end), blendMode = BlendMode.DstIn)
        }

/** 端を透かす幅 */
private val EDGE_FADE = 72.dp
