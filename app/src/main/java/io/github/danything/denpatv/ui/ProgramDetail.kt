package io.github.danything.denpatv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.danything.denpatv.data.splitExtended
import kotlinx.coroutines.launch

/** 詳しくに出す中身。録画 (一覧・再生中) とライブで同じ形 (`ProgramDetailDialog`) */
data class DetailFacts(
    val title: String,
    /** 「局 ・ 10/6(火) 21:00〜21:54 (54分)」(`programMeta`) */
    val meta: String,
    /** 札 (ジャンル・映像・音声・形) */
    val chips: List<String> = emptyList(),
    /** 札の頭に赤で出す印 (「● 録画中」「録画予約済み」) */
    val badge: String? = null,
    /** 進み (位置, 長さ。ミリ秒) と、その横の1言 (「あと 32 分」「0:14:22 まで観た」)。null なら出さない */
    val progress: Pair<Long, Long>? = null,
    val progressLabel: String? = null,
    val description: String = "",
    /** 放送の詳細 (見出し → 本文) */
    val extended: List<Pair<String, String>> = emptyList(),
)

/** 詳しくの札。並べた順に左から。**先頭が主な操作で、開いたときにそこに合う** */
class DetailAction(val label: String, val onClick: () -> Unit)

/**
 * 番組の詳しく。**録画の一覧 (カードの長押し)・録画と追っかけの再生中・ライブ (決定の長押し・情報キー) で同じもの**。
 * ブラウザの denpa の詳細 (観る画面の右の欄、`ProgramFacts`) と同じ並びで、テレビの詳しくの画面 (Android TV の
 * 詳細ページ) の作りに合わせる:
 *
 * - 左に寄せた1列。**番組名を大きく、その下に1行の「局 ・ 日時 (長さ)」**、ライブなら番組の進みと残り、札の列
 *   (録画中の印・ジャンル・映像・音声)
 * - **その下に操作の札を横1列**。先頭が主な操作で、開いたときにそこに合う (録画は「続きから再生」、ライブは「録画」、
 *   再生中は「閉じる」— 映像に戻るだけ)
 * - 下の残りに説明と放送の詳細。**読みもの (説明・番組内容) は左、名前の並び (出演者・原作・脚本・音楽など) は右**
 *   (1080p の幅を使い、1行を長くしすぎない)。収まらなければ札の列から下キーで本文に入り、**上下で読み進める**
 *   (いちばん上でもう一度上を押すと札に戻る)
 * - 絵 (ポスター) は出さない (文字を読むための画面。映像は一覧のカードと再生の画面で見える)
 *
 * 長押しで開くので、離すまでの決定はこの窓では受けない (`ignoreHeldCenter`。先頭の札が押されないように)。戻るで閉じる
 */
@Composable
fun ProgramDetailDialog(
    facts: DetailFacts,
    actions: List<DetailAction>,
    onDismiss: () -> Unit,
    /** 札の下に出す1行 (ライブの「録画を始めます」など) */
    note: String? = null,
    /** 映像の上に開く (再生中・ライブ)。右へ行くほど少し透かして映像を覗かせる。一覧の上では透かさない */
    overVideo: Boolean = false,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val surface = MaterialTheme.colorScheme.surface
        Box(
            Modifier
                .ignoreHeldCenter()
                .fillMaxSize()
                .background(
                    // 映像の上では、右へ行くほど少し薄く (文字のある左は濃く)。一覧の上では透かさない (カードの字が透けて読みにくい)
                    if (overVideo) {
                        Brush.horizontalGradient(0f to surface.copy(alpha = 0.96f), 0.55f to surface.copy(alpha = 0.92f), 1f to surface.copy(alpha = 0.8f))
                    } else {
                        SolidColor(surface)
                    },
                ),
        ) {
            Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 40.dp, bottom = 24.dp)) {
                Header(facts)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    actions.forEachIndexed { index, action ->
                        val modifier = if (index == 0) Modifier.focusRequester(first) else Modifier
                        // 札の文字は1行のまま伸ばす (「もう一度押すと削除」になっても切れないように)
                        if (index == 0) {
                            Button(onClick = action.onClick, modifier = modifier) { Text(action.label, maxLines = 1, softWrap = false) }
                        } else {
                            OutlinedButton(onClick = action.onClick, modifier = modifier) { Text(action.label, maxLines = 1, softWrap = false) }
                        }
                    }
                }
                note?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(20.dp))
                Body(facts, up = first)
            }
        }
    }
}

@Composable
private fun Header(facts: DetailFacts) {
    Text(
        facts.title,
        style = MaterialTheme.typography.headlineMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 760.dp),
    )
    Spacer(Modifier.height(6.dp))
    Text(facts.meta, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    facts.progress?.let { (at, length) ->
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.width(360.dp)) { ProgressLine(at, length) }
            facts.progressLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    val chips = listOfNotNull(facts.badge) + facts.chips
    if (chips.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { label ->
                val badge = label == facts.badge
                // 高さを揃える (英数字だけの札 (「1080i MPEG-2」) は字の高さが低い)
                Box(
                    Modifier
                        .height(CHIP_HEIGHT)
                        .background(if (badge) BADGE_COLOR else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (badge) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 説明と放送の詳細。読みものを左、名前の並びを右に。**収まらなければ合わせられて、上下で読み進める**
 * (合っている間は縁を出す。続きがあれば下の端に「▼」)。いちばん上で上を押すと、合いが先頭の札へ戻る
 */
@Composable
private fun ColumnScope.Body(facts: DetailFacts, up: FocusRequester) {
    val (prose, credits) = remember(facts.extended) { splitExtended(facts.extended) }
    val left = buildList {
        if (facts.description.isNotBlank()) add(null to facts.description)
        prose.forEach { add(it.first to it.second) }
    }
    if (left.isEmpty() && credits.isEmpty()) return
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    var viewport by remember { mutableIntStateOf(0) }
    val scrollable = scroll.maxValue > 0
    val frame = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else Color.Transparent
    // 本文に合っている間に中身が縮んで収まった (ライブで番組が替わった) ら、先頭の札へ戻す (合いの行き場が無くならないように)
    LaunchedEffect(scrollable) {
        if (!scrollable && focused) runCatching { up.requestFocus() }
    }
    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            // 縁の内側の余白のぶん左へ出して、本文の字を番組名と揃える
            .offset(x = -BODY_PADDING)
            .onSizeChanged { viewport = it.height }
            .border(2.dp, frame, RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusProperties {
                // 収まっていれば合わせない (下キーで止まるだけになる)
                canFocus = scrollable
                // 上で抜けるときは先頭の札へ (近いもの = 真上の札に合うと、右の端の「閉じる」に飛ぶ)
                this.up = up
            }
            .then(
                if (!scrollable) {
                    Modifier
                } else {
                    Modifier
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            val step = viewport * 0.6f
                            when (event.key) {
                                // 下: 読み進める。いちばん下でも受ける (下に合わせる先は無い)
                                Key.DirectionDown -> {
                                    if (scroll.value < scroll.maxValue) scope.launch { scroll.animateScrollBy(step) }
                                    true
                                }
                                // 上: 戻る。いちばん上なら受けない (上の札へ合いが移る)
                                Key.DirectionUp -> if (scroll.value > 0) {
                                    scope.launch { scroll.animateScrollBy(-step) }
                                    true
                                } else {
                                    false
                                }
                                else -> false
                            }
                        }
                },
            )
            .focusable(),
    ) {
        Row(
            Modifier.verticalScroll(scroll, enabled = false).padding(horizontal = BODY_PADDING, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            if (left.isNotEmpty()) {
                Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    left.forEach { (heading, body) -> Section(heading, body, MaterialTheme.colorScheme.onSurface) }
                }
            }
            if (credits.isNotEmpty()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    credits.forEach { (heading, body) -> Section(heading, body, MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (scroll.value < scroll.maxValue) {
            val surface = MaterialTheme.colorScheme.surface
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, surface.copy(alpha = 0.95f)))),
            )
            // 本文の上に重ねるので、下地を敷いて字が重ならないように
            Text(
                if (focused) "▼" else "▼ 下キーで続き",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 4.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Section(heading: String?, body: String, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        heading?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
        Text(body, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

/** 本文の縁の内側の余白 */
private val BODY_PADDING = 16.dp

/** 札の高さ */
private val CHIP_HEIGHT = 30.dp

/** 録画中の印の赤 (一覧のカードの「● 録画中」と同じ) */
private val BADGE_COLOR = Color(0xFFC62828)
