package io.github.danything.denpatv.ui

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.danything.denpatv.data.DenpaEvent
import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.Unauthorized
import io.github.danything.denpatv.data.codecLabels
import io.github.danything.denpatv.data.durationLabel
import io.github.danything.denpatv.data.watched
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * 録画の一覧。**数が多いので格子にして、放送日ごとに見出しを挟む** (新しい順)。
 * 開くと一番下 (いちばん古い録画) に合わせる。長押しで詳しく (説明・再生・削除)。
 * 観て戻ってきたら、開いた録画に合わせ直す
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingsScreen(
    repo: Repository,
    /** 観る。true なら続きではなく頭から (詳しくの「最初から」) */
    onWatch: (Recording, Boolean) -> Unit,
    onUnauthorized: () -> Unit,
    modifier: Modifier = Modifier,
    /** 開いたときにカードに合わせるか (ライブから戻ったときは左のメニューが合いを取るので false) */
    takeFocus: Boolean = true,
) {
    var recordings by remember { mutableStateOf(repo.recordings) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(recordings.isNotEmpty()) }
    var retry by remember { mutableIntStateOf(0) }
    /** 下に数秒出す知らせ (消せなかったときなど) */
    val (notice, notify) = rememberFlash()
    /** 詳しいところを開いている録画 (長押し) */
    var opened by remember { mutableStateOf<Recording?>(null) }
    /** 最後に合わせていた録画。観て戻ってきたらここに合わせ直す (画面を作り直しても残る) */
    var lastFocused by rememberSaveable { mutableStateOf<Long?>(null) }
    /** いまカードに合いがあれば、その録画 (メニューや詳しくに合いがあるときは null) */
    var focusedCard by remember { mutableStateOf<Long?>(null) }
    val requesters = remember { mutableMapOf<Long, FocusRequester>() }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    /** その録画のカードに合わせる (見えていなければそこまで送ってから) */
    suspend fun focus(id: Long) {
        withFrameNanos { }
        if (grid.layoutInfo.visibleItemsInfo.none { it.key == id }) gridIndex(recordings, id)?.let { grid.scrollToItem(it) }
        withFrameNanos { }
        runCatching { requesters[id]?.requestFocus() }
    }

    suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
            error = null
        } catch (_: Unauthorized) {
            onUnauthorized()
        } catch (e: Exception) {
            error = "denpa から録画を取れません: ${e.message}"
        }
        recordings = repo.recordings
        loaded = true
    }

    LaunchedEffect(retry) {
        // 戻ってきたときは読み直さない (並びが変わると合わせ直す先がずれる)。追っかけで観て戻ったときだけ読み直す
        // (録り終えた・焼き上がったかもしれない)。合わせ直す先は id で探すので、並びが変わっても戻れる
        if (repo.recordings.isEmpty() || retry > 0 || repo.recordingsStale) {
            repo.recordingsStale = false
            guarded { repo.refreshRecordings() }
        }
    }
    // denpa の知らせ (録画が増えた・焼き上がった・消えた、繋ぎ直した) で読み直す。続けて来たらまとめて1回 (1 秒待つ)。
    // 読めなくてもいまの一覧を出したまま (次の知らせか、戻ったときに読み直す)
    LaunchedEffect(Unit) {
        repo.events.filter { it == DenpaEvent.Opened || it == DenpaEvent.Changed("recordings") }.collectLatest {
            delay(1_000)
            if (!loaded) return@collectLatest
            val before = recordings
            // 詳しくを開いていれば、その録画に合っているものと見なす
            val had = focusedCard ?: opened?.id
            repo.recordingsStale = false
            try {
                repo.refreshRecordings()
            } catch (_: Unauthorized) {
                return@collectLatest onUnauthorized()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                repo.recordingsStale = true
                return@collectLatest
            }
            recordings = repo.recordings
            error = null
            // 詳しくを開いていれば新しい中身に替える (録り終えた・焼き上がったなど)
            opened = opened?.let { old -> recordings.firstOrNull { it.id == old.id } ?: old }
            // 合わせていた録画が消えたら (ブラウザで消したなど)、詳しくを閉じて残っている隣へ
            val alive = recordings.map { it.id }.toSet()
            if (had != null && had !in alive) {
                if (opened?.id == had) opened = null
                val at = before.indexOfFirst { it.id == had }
                val next = (before.drop(at + 1) + before.take(at).reversed()).firstOrNull { it.id in alive } ?: return@collectLatest
                lastFocused = next.id
                focus(next.id)
            }
        }
    }
    LaunchedEffect(loaded) {
        // 再生の画面で消して戻ってきたら、その隣に合わせる
        repo.focusOnReturn?.let { lastFocused = it; repo.focusOnReturn = null }
        // 観て戻ったら開いた録画に。初めては一番下 (いちばん古い録画): 古いものから片付けられるように (ブラウザの denpa と同じ)
        val id = lastFocused?.takeIf { id -> recordings.any { it.id == id } } ?: recordings.lastOrNull()?.id ?: return@LaunchedEffect
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        // 見えていなければ (一番下・消した隣)、そこまで送ってから合わせる
        if (requesters[id] == null || grid.layoutInfo.visibleItemsInfo.none { it.key == id }) {
            gridIndex(recordings, id)?.let { grid.scrollToItem(it) }
            withFrameNanos { }
        }
        runCatching { requesters[id]?.requestFocus() }
    }

    if (!loaded) return Centered("読み込んでいます…")
    error?.let { message ->
        Column(
            modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(message, style = MaterialTheme.typography.titleMedium)
            DenpaButton(onClick = { retry++ }) { Text("やり直す") }
        }
        return
    }
    if (recordings.isEmpty()) return Centered("観られる録画はまだありません")

    // 上の大きな絵と見出しは、合わせている録画 (メニューや詳しくに合いがあるときは、最後に合わせていた録画)
    val shown = recordings.firstOrNull { it.id == (focusedCard ?: lastFocused) } ?: recordings.last()
    /*
     * **背景の絵と説明は、合いが止まってから替える** (`HERO_SETTLE_MS`)。続けて送っている間に、通り過ぎる録画の絵を読んで
     * 敷き直したり説明を取りに行ったりしない (力の弱いテレビで送りが重くならないように)。番組名などの文字はすぐ替える
     */
    var settled by remember { mutableLongStateOf(shown.id) }
    LaunchedEffect(shown.id) {
        if (settled != shown.id) delay(HERO_SETTLE_MS)
        settled = shown.id
    }
    val backdrop = recordings.firstOrNull { it.id == settled }
    /** 説明の頭の1行 (録画ごとに1回だけ取る。古い denpa・取れなければ空) */
    val descriptions = remember { mutableStateMapOf<Long, String>() }
    LaunchedEffect(settled) {
        val id = settled
        if (id in descriptions) return@LaunchedEffect
        descriptions[id] = try {
            repo.api.recordingDetail(repo.base, id)?.description?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        } catch (_: Unauthorized) {
            return@LaunchedEffect onUnauthorized()
        }
    }

    val groups = recordings.groupBy { DAY.format(Date(it.startAt)) }
    Box(modifier.fillMaxSize().background(Palette.Background)) {
        Backdrop(repo.url(backdrop?.poster), repo.token, Modifier.align(Alignment.TopEnd).fillMaxWidth(BACKDROP_WIDTH).height(BACKDROP_HEIGHT))
        Column(Modifier.fillMaxSize()) {
            Hero(repo, shown, descriptions[shown.id].orEmpty())
            // 合わせている録画の放送日 (合わせた段を上の端に揃えるので、その段の日の見出しは上に隠れる。代わりにここに出す)
            Text(
                DAY.format(Date(shown.startAt)),
                style = MaterialTheme.typography.titleMedium,
                color = Palette.TextMuted,
                modifier = Modifier.padding(start = EDGE, top = 4.dp),
            )
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                // 1段 (カードと番組名) の高さ。いちばん下の段も上の端まで送れるよう、下にその残りの余白を足す
                val cardWidth = (maxWidth - EDGE - GRID_END - GRID_GAP * 3) / 4
                val rowHeight = cardWidth * 9f / 16f + CARD_TITLE_HEIGHT
                val density = LocalDensity.current
                val rowTop = remember(density) { RowTop(with(density) { GRID_TOP.toPx() }) }
                CompositionLocalProvider(LocalBringIntoViewSpec provides rowTop) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        state = grid,
                        modifier = Modifier.fillMaxSize(),
                        // 合わせたカードは膨らんで光るので、上と横に切れないぶん空けておく
                        contentPadding = PaddingValues(start = EDGE, end = GRID_END, top = GRID_TOP, bottom = (maxHeight - rowHeight - GRID_TOP).coerceAtLeast(48.dp)),
                        horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        groups.forEach { (day, items) ->
                            item(span = { GridItemSpan(maxLineSpan) }, key = "day:$day", contentType = "day") {
                                Text(day, style = MaterialTheme.typography.titleMedium, color = Palette.TextMuted, modifier = Modifier.padding(top = 4.dp))
                            }
                            items.forEach { recording ->
                                item(key = recording.id, contentType = "recording") {
                                    val requester = remember(recording.id) { requesters.getOrPut(recording.id) { FocusRequester() } }
                                    RecordingCard(
                                        repo,
                                        recording,
                                        modifier = Modifier.focusRequester(requester).onFocusChanged {
                                            if (it.isFocused) {
                                                lastFocused = recording.id
                                                focusedCard = recording.id
                                            } else if (focusedCard == recording.id) {
                                                focusedCard = null
                                            }
                                        },
                                        onClick = { onWatch(recording, false) },
                                        onLongClick = { opened = recording },
                                    )
                                }
                            }
                        }
                    }
                }
                // 送っている途中に上の端で切れるカードを、地の色へ溶かす
                Box(Modifier.fillMaxWidth().height(GRID_TOP).background(GRID_FADE))
            }
        }
        notice?.let { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Text,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .background(Palette.SurfaceRaised, RoundedCornerShape(8.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }

    opened?.let { recording ->
        RecordingDetailDialog(
            repo,
            recording,
            onPlay = { fromStart -> opened = null; onWatch(recording, fromStart) },
            onDelete = {
                opened = null
                scope.launch {
                    guarded {
                        if (repo.api.deleteRecording(repo.base, recording.id)) {
                            // 消したものの隣に合わせ直す。読み直さず手元から抜く (続きまで読んだぶんを残す)
                            lastFocused = repo.forgetRecording(recording.id)
                        } else {
                            // 再生の画面と同じ知らせ
                            notify(NOT_DELETED)
                        }
                    }
                    lastFocused?.let { focus(it) }
                }
            },
            onDismiss = {
                opened = null
                runCatching { requesters[recording.id]?.requestFocus() }
            },
            onUnauthorized = onUnauthorized,
        )
    }
}

/**
 * 一覧の上の見出し (Google TV の「没入型の一覧」の上の段)。**合わせている録画を大きく**: 番組名・局と放送日時と長さ・
 * 札 (録画中・エンコード中・観た位置・形)・説明の頭の1行。
 * いちばん上の1行は画面の名前と手引き、新しい版の知らせ (一覧の頭の段から上キーで合う)。
 * 高さは決めておく (録画ごとに行の数が違っても、下の一覧が上下に動かないように)
 */
@Composable
private fun Hero(repo: Repository, recording: Recording, description: String) {
    Column(
        Modifier.fillMaxWidth().height(HERO_HEIGHT).padding(start = EDGE, end = 48.dp, top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("録画", style = MaterialTheme.typography.titleMedium, color = Palette.AccentBright)
            Text("長押しで詳しく (説明・削除)", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
            // 新しい版があれば (上キーで合う)
            UpdateNotice(repo.app.updater)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            recording.title,
            style = MaterialTheme.typography.headlineMedium,
            color = Palette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = HERO_TEXT_WIDTH),
        )
        Text(
            listOfNotNull(recording.serviceName, WHEN.format(Date(recording.startAt)), recording.durationMs?.let(::durationLabel)).joinToString("  ・  "),
            style = MaterialTheme.typography.titleSmall,
            color = Palette.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (recording.recording) Badge(RECORDING_BADGE, Palette.Recording, Color.White)
            repo.encoding[recording.id]?.let { Badge("エンコード中 ${(it * 100).toInt()}%", Palette.Accent, Palette.OnAccent) }
            recording.watched?.let { part ->
                Box(Modifier.width(120.dp).height(4.dp).background(Color(0x55FFFFFF), RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(part).height(4.dp).background(Palette.AccentBright, RoundedCornerShape(2.dp)))
                }
                Text("${(part * 100).toInt()}% 観た", style = MaterialTheme.typography.labelMedium, color = Palette.Text)
            }
            if (!recording.recording) recording.codecLabels.forEach { Badge(it, Palette.SurfaceRaised.copy(alpha = 0.85f), Palette.Text) }
        }
        if (description.isNotEmpty()) {
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = HERO_TEXT_WIDTH),
            )
        }
    }
}

/**
 * 上の段の後ろに敷く、合わせている録画の絵。**ぼかして暗くし、左と下を地の色へ溶かす** (上の文字が読めるように)。
 * ぼかすのは Android 12 から (RenderEffect)。それより前はぼかさず、そのぶん暗くする。
 * 絵は小さく読んで (`BACKDROP_DECODE`) 引き伸ばす (ぼかすので見分けが付かず、覚えておく量も少ない)
 */
@Composable
private fun Backdrop(url: String?, token: String?, modifier: Modifier) {
    val blur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    /*
     * **1枚の層に描いて覚えさせる** (Offscreen)。下の一覧を送るたびに画面は描き直されるが、絵と2枚の覆いは替わらないので、
     * 層を1枚貼るだけで済む (力の弱いテレビで、送りのたびに大きな絵と覆いを重ね描きしない)
     */
    Box(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        Crossfade(url, animationSpec = tween(BACKDROP_FADE_MS), label = "backdrop") { shown ->
            if (shown != null) {
                RemoteImage(
                    shown,
                    ContentScale.Crop,
                    Modifier.fillMaxSize().then(if (blur) Modifier.blur(BACKDROP_BLUR) else Modifier),
                    token,
                    decode = BACKDROP_DECODE,
                    placeholder = Color.Transparent,
                )
            }
        }
        Box(Modifier.matchParentSize().background(if (blur) BACKDROP_LEFT else BACKDROP_LEFT_STRONG))
        Box(Modifier.matchParentSize().background(BACKDROP_BOTTOM))
    }
}

/** 角の丸い小さな札 (録画中・エンコード中・形) */
@Composable
private fun Badge(label: String, background: Color, color: Color) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        modifier = Modifier.background(background, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * 録画のカード。**絵を大きく** (16:9。Google TV の横長のカード) し、下に番組名だけ。局・日時・長さ・形は上の段に出す。
 * 絵の上に、録っている最中なら「● 録画中」、焼いている最中なら進み、下の縁に観た割合の帯。
 * 合わせると膨らみ、azure の縁と光 (`Focus`)。番組名も明るくする
 */
@Composable
private fun RecordingCard(
    repo: Repository,
    recording: Recording,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 決定の長押しは繰り返しが来なくても押した長さで決める (`centerPresses`)。カードの onClick・onLongClick は手で触れたとき・読み上げ用
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.onFocusChanged { focused = it.isFocused }.centerPresses(onClick, onLongClick).fillMaxWidth().aspectRatio(16f / 9f),
            shape = CardDefaults.shape(shape),
            colors = CardDefaults.colors(containerColor = Palette.Surface, focusedContainerColor = Palette.Surface),
            scale = CardDefaults.scale(focusedScale = Focus.CARD_SCALE),
            border = CardDefaults.border(focusedBorder = Focus.border(shape)),
            glow = CardDefaults.glow(focusedGlow = Focus.glow),
        ) {
            Box(Modifier.fillMaxSize()) {
                RemoteImage(repo.url(recording.poster), ContentScale.Crop, Modifier.fillMaxSize(), repo.token)
                Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (recording.recording) Badge(RECORDING_BADGE, Palette.Recording, Color.White)
                    // 焼いている間は進み (denpa の知らせで動く)
                    repo.encoding[recording.id]?.let { Badge("エンコード中 ${(it * 100).toInt()}%", Palette.Background.copy(alpha = 0.85f), Palette.Text) }
                }
                // 観た割合 (続きの位置があるときだけ)。絵の下の縁に
                recording.watched?.let { part ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color(0x99000000))) {
                        Box(Modifier.fillMaxWidth(part).height(5.dp).background(Palette.AccentBright))
                    }
                }
            }
        }
        Text(
            recording.title,
            style = MaterialTheme.typography.titleSmall,
            color = if (focused) Palette.Text else Palette.TextMuted,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 格子の中の位置 (日の見出しも数える。下の LazyVerticalGrid の並びと同じ) */
private fun gridIndex(recordings: List<Recording>, id: Long): Int? {
    var index = 0
    recordings.groupBy { DAY.format(Date(it.startAt)) }.values.forEach { items ->
        index++
        val at = items.indexOfFirst { it.id == id }
        if (at >= 0) return index + at
        index += items.size
    }
    return null
}

private const val RECORDING_BADGE = "● 録画中"

private val DAY = SimpleDateFormat("M月d日(E)", Locale.JAPAN)
/** カードと詳しくの放送日時 */
internal val WHEN = SimpleDateFormat("M/d(E) HH:mm", Locale.JAPAN)

/** 一覧の左の端 (上の段の文字と揃える) */
private val EDGE = 40.dp

/** 上の段の高さと、文字の幅 (1行を長くしすぎない) */
private val HERO_HEIGHT = 196.dp
private val HERO_TEXT_WIDTH = 640.dp

/** 背景の絵を替えるまで合いが止まっている間 (ミリ秒) と、入れ替えの長さ */
private const val HERO_SETTLE_MS = 450L
private const val BACKDROP_FADE_MS = 250

/** 背景の絵の大きさ (画面の幅・高さに対して)、読む大きさ、ぼかし */
private const val BACKDROP_WIDTH = 0.72f
private val BACKDROP_HEIGHT = HERO_HEIGHT + 32.dp
private val BACKDROP_DECODE = IntSize(320, 180)
private val BACKDROP_BLUR = 6.dp

/** 背景の絵を、左は地の色へ溶かし、右も暗くする。ぼかせない (Android 11 まで) ときは強めに */
private val BACKDROP_LEFT = backdropScrim(0.4f)
private val BACKDROP_LEFT_STRONG = backdropScrim(0.55f)

private fun backdropScrim(dim: Float) = Brush.horizontalGradient(
    0f to Palette.Background,
    0.4f to Palette.Background.copy(alpha = 0.6f + dim * 0.4f),
    1f to Palette.Background.copy(alpha = dim),
)
private val BACKDROP_BOTTOM = Brush.verticalGradient(0.35f to Color.Transparent, 0.85f to Palette.Background)

/** 格子の上の余白 (合わせた段のカードが膨らんで光るぶん)、右の余白、カードの間 */
private val GRID_TOP = 14.dp
private val GRID_END = 48.dp
private val GRID_GAP = 24.dp

/** 格子の上の端の溶かし。上半分は塗りつぶす (上に隠れた日の見出しの字の裾が覗かないように) */
private val GRID_FADE = Brush.verticalGradient(0f to Palette.Background, 0.5f to Palette.Background, 1f to Color.Transparent)

/** カードの絵の下 (間と番組名2行) の高さ。だいたいでよい */
private val CARD_TITLE_HEIGHT = 56.dp

/**
 * **合わせたカードの段を、格子の上の端に揃える** (Google TV の一覧と同じく、目の置き場を変えない)。
 * テレビの既定 (合わせたものを枠の 3 割の高さへ) では、上の段の番組名だけが絵から切れて覗いていた
 */
@OptIn(ExperimentalFoundationApi::class)
private class RowTop(private val top: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - top
}
