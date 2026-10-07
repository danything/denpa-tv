package io.github.danything.denpatv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.danything.denpatv.data.DenpaEvent
import io.github.danything.denpatv.data.Images
import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.Unauthorized
import io.github.danything.denpatv.data.codecLabels
import io.github.danything.denpatv.data.durationLabel
import io.github.danything.denpatv.data.shortServiceName
import io.github.danything.denpatv.data.unwatched
import io.github.danything.denpatv.data.watched
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** その録画のカードに合わせる (見えていなければ (一番下・消した隣) そこまで送ってから) */
    suspend fun focus(id: Long) {
        withFrameNanos { }
        if (requesters[id] == null || grid.layoutInfo.visibleItemsInfo.none { it.key == id }) gridIndex(recordings, id)?.let { grid.scrollToItem(it) }
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
        // 戻ってきたときは読み直さない (並びが変わると合わせ直す先がずれる)。観て戻ったとき (`positionSaved`)・追っかけで観て戻ったときだけ読み直す
        // (観た位置・未視聴の点が変わった、録り終えた・焼き上がったかもしれない)。合わせ直す先は id で探すので、並びが変わっても戻れる
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
        if (takeFocus) focus(id)
    }

    if (!loaded) return Centered("読み込んでいます…")
    error?.let { message ->
        Column(
            modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(message, style = MaterialTheme.typography.titleMedium)
            // 古すぎる denpa なら、取れないのはたぶんそのせい
            repo.denpaWarning?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Reserved) }
            DenpaButton(onClick = { retry++ }) { Text("やり直す") }
        }
        return
    }
    if (recordings.isEmpty()) return Centered(listOfNotNull("観られる録画はまだありません", repo.denpaWarning).joinToString("\n"))

    /** カード1枚の大きさ (px)。隣の録画のポスターを先に読むときの大きさ。組むたびに書くだけで、画面は読まない */
    val cardSize = remember { IntArray(2) }
    /** カードの番組名の幅 (px)。どのカードも同じ幅なので、組んだものから分け合う (`EpisodeTitle`) */
    val titleWidth = remember { IntArray(1) }

    val groups = recordings.groupBy { DAY.format(Date(it.startAt)) }
    Box(modifier.fillMaxSize().background(Palette.Background)) {
        /*
         * 上の段 (背景の絵と見出し) だけが、合わせている録画を読む (`{ focusedCard ?: lastFocused }` を渡して中で読む)。
         * 合いが動いても組み直すのは上の段だけで、一覧 (この関数と格子) は組み直さない
         */
        HeroArea(repo, recordings, { focusedCard ?: lastFocused }, cardSize, onUnauthorized)
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(HERO_HEIGHT))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val density = LocalDensity.current
                val rowTop = remember(density) { RowTop(with(density) { GRID_TOP.toPx() }) }
                CompositionLocalProvider(LocalBringIntoViewSpec provides rowTop) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(GRID_COLUMNS),
                        state = grid,
                        modifier = Modifier.fillMaxSize(),
                        // 合わせたカードは膨らんで光るので、上と横に切れないぶん空けておく
                        contentPadding = PaddingValues(start = EDGE, end = GRID_END, top = GRID_TOP, bottom = GRID_BOTTOM),
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
                                        cardSize = cardSize,
                                        titleWidth = titleWidth,
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
                Box(Modifier.fillMaxWidth().height(GRID_FADE_HEIGHT).background(GRID_FADE))
                // 下の端で切れる次の段 (ポスターと番組名の頭) も溶かす。一覧の終わりの余白 (`GRID_BOTTOM`) より短く、最後の段には掛けない
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(GRID_BOTTOM_FADE_HEIGHT).background(GRID_BOTTOM_FADE))
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
 * 上の段: 背景の絵と見出し。`shownId` (合わせている録画、無ければ最後に合わせていた録画) を**ここだけで**読む。
 *
 * - **背景の絵と説明は、合いが止まってから替える** (`HERO_SETTLE_MS`)。続けて送っている間に、通り過ぎる録画の絵を読んで
 *   敷き直したり説明を取りに行ったりしない。番組名などの文字はすぐ替える
 * - 止まったら、前後の録画 (`PREFETCH` 件) のポスターを先に読んでおく (送った先のカードがすぐ絵になる)
 */
@Composable
private fun HeroArea(repo: Repository, recordings: List<Recording>, shownId: () -> Long?, cardSize: IntArray, onUnauthorized: () -> Unit) {
    val shown = recordings.firstOrNull { it.id == shownId() } ?: recordings.last()
    var settled by remember { mutableLongStateOf(shown.id) }
    LaunchedEffect(shown.id) {
        if (settled != shown.id) delay(HERO_SETTLE_MS)
        settled = shown.id
    }
    /** 説明の頭の1行 (録画ごとに、取れるまで。説明の無い録画は空) */
    val descriptions = remember { mutableStateMapOf<Long, String>() }
    LaunchedEffect(settled) {
        val id = settled
        // 先読みは説明と並べて (先読みを待たずに説明を取りに行く)。止まる先が替わったら一緒にやめる
        launch {
            // 開いたばかりでカードがまだ測られていなければ、測られるまで待つ (少しだけ)
            repeat(CARD_SIZE_WAITS) { if (cardSize[0] <= 0) delay(CARD_SIZE_WAIT_MS) }
            prefetch(repo, recordings, id, cardSize[0], cardSize[1])
        }
        if (id in descriptions) return@LaunchedEffect
        val detail = try {
            repo.api.recordingDetail(repo.base, id)
        } catch (_: Unauthorized) {
            return@LaunchedEffect onUnauthorized()
        }
        // 取れなかったら覚えない (次にこの録画に止まったときに取り直す)
        detail?.let { descriptions[id] = it.description.lineSequence().firstOrNull { line -> line.isNotBlank() }?.trim().orEmpty() }
    }
    Box(Modifier.fillMaxWidth()) {
        val backdrop = recordings.firstOrNull { it.id == settled }
        Backdrop(repo.url(backdrop?.poster), repo.token, Modifier.align(Alignment.TopEnd).fillMaxWidth(BACKDROP_WIDTH).height(BACKDROP_HEIGHT))
        Hero(repo, shown, descriptions[shown.id].orEmpty())
    }
}

/**
 * 止まった録画の前後 `PREFETCH` 件のポスターを、カードの大きさで先に読む (覚えているものは読まない)。IO の上で。
 * 近いものから (止まってすぐ隣へ送られても間に合うように)
 */
private suspend fun prefetch(repo: Repository, recordings: List<Recording>, id: Long, width: Int, height: Int) {
    if (width <= 0 || height <= 0) return
    val at = recordings.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
    val near = nearFirst(at, PREFETCH, recordings.size).map { recordings[it] }
    withContext(Dispatchers.IO) {
        near.mapNotNull { repo.url(it.poster) }.forEach { url ->
            if (Images.cached(url, width, height, opaque = true) == null) Images.load(url, width, height, repo.token, opaque = true)
        }
    }
}

/**
 * 一覧の上の見出し (Google TV の「没入型の一覧」の上の段)。**合わせている録画を大きく**: 番組名・局と放送日時と長さ・
 * 札 (録画中・エンコード中・観た位置・形)・説明の頭の1行。
 * いちばん上の1行は画面の名前と手引き、denpa が古すぎるときの1行、新しい版の知らせ (一覧の頭の段から上キーで合う)。
 * 高さは決めておく (録画ごとに行の数が違っても、下の一覧が上下に動かないように)
 */
@Composable
private fun Hero(repo: Repository, recording: Recording, description: String) {
    /** 番組名の幅 (合いを動かして題が替わっても、前の幅で切っておく) */
    val titleWidth = remember { IntArray(1) }
    Column(
        Modifier.fillMaxWidth().height(HERO_HEIGHT).padding(start = EDGE, end = GRID_END, top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("録画", style = MaterialTheme.typography.titleMedium, color = Palette.AccentBright)
            Text("長押しで詳しく (説明・削除)", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
            // 繋いだ denpa が古すぎる (Repository.checkVersion)。新しい版の知らせを押し出さないよう、余りの幅で切る
            repo.denpaWarning?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Reserved,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            // 新しい版があれば (上キーで合う)
            UpdateNotice(repo.app.updater)
        }
        Spacer(Modifier.height(4.dp))
        // 1行に収まらなければ話数を残して途中を切る (カードと同じ)
        EpisodeTitle(
            recording.title,
            MaterialTheme.typography.headlineMedium.merge(TITLE_TEXT),
            Palette.Text,
            maxLines = 1,
            width = titleWidth,
            modifier = Modifier.widthIn(max = HERO_TEXT_WIDTH),
            unwatched = recording.unwatched,
        )
        Text(
            listOfNotNull(recording.serviceName?.let(::shortServiceName), WHEN.format(Date(recording.startAt)), recording.durationMs?.let(::durationLabel)).joinToString("  ・  "),
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
            encodingOf(repo, recording.id)?.let { Badge("エンコード中 ${(it * 100).toInt()}%", Palette.Accent, Palette.OnAccent) }
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
 * ぼかしは読むときに済ませておく (`BlurredImage`。ごく小さく読んで引き伸ばす)。描くたびにぼかさないので軽く、どの版でも同じ見た目。
 * 替えるときは2枚を透かし具合だけで入れ替える (Crossfade)
 */
@Composable
private fun Backdrop(url: String?, token: String?, modifier: Modifier) {
    Box(modifier) {
        Crossfade(url, animationSpec = tween(BACKDROP_FADE_MS), label = "backdrop") { shown ->
            if (shown != null) BlurredImage(shown, token, Modifier.fillMaxSize())
        }
        Box(Modifier.matchParentSize().background(BACKDROP_LEFT))
        Box(Modifier.matchParentSize().background(BACKDROP_BOTTOM))
    }
}

/**
 * その録画の焼いている進み。**その録画の値が変わったときだけ組み直す** (`derivedStateOf`)。進みの表 (`Repository.encoding`)
 * をそのまま読むと、どれか1件の知らせ (数秒ごと) で、見えているカードが全部組み直しになる
 */
@Composable
private fun encodingOf(repo: Repository, id: Long): Float? {
    val encoding by remember(repo, id) { derivedStateOf { repo.encoding[id] } }
    return encoding
}

/** 角の丸い小さな札 (録画中・エンコード中・形) */
@Composable
private fun Badge(label: String, background: Color, color: Color) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        modifier = Modifier.background(background, TagShape).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * 録画のカード。**絵を大きく** (16:9。Google TV の横長のカード。角 12dp) し、**番組名を絵の下の縁に重ねて2行まで、大きく太く**
 * (暗い帯の上に白い字)。絵の下には局と放送の時刻を1行。長さ・形・説明は上の段に出す。
 *
 * 絵は放送から切り出したもの (暗転・CM・字幕の無い場面のこともある) で、絵だけでは何の番組か分からないことがあるので、
 * **合わせていないカードも題で見分けられるように**する。題を絵の下に置くと1段が高くなり、下の段は絵だけ見えて題が切れる
 * (いちばん見たいものが見えない) ので、絵に重ねて段の高さを前と同じにしている。収まらない題は話数を残して途中を切る (`EpisodeTitle`)。
 *
 * 絵の上に、録っている最中なら「● 録画中」、焼いている最中なら進み、下の縁に観た割合の帯。まだ観ていないものは番組名の頭に点 (`unwatched`)。
 * 合わせると膨らみ、azure の縁と光 (`Focus`)
 */
@Composable
private fun RecordingCard(
    repo: Repository,
    recording: Recording,
    /** 絵の大きさ (px) を書いておく所 (先読みを、絵と同じ大きさ = 同じ覚えの鍵で読むため) */
    cardSize: IntArray,
    /** 番組名の幅を分け合う所 (`EpisodeTitle`) */
    titleWidth: IntArray,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = CardShape
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 決定の長押しは繰り返しが来なくても押した長さで決める (`centerPresses`)。カードの onClick・onLongClick は手で触れたとき・読み上げ用
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.centerPresses(onClick, onLongClick).fillMaxWidth().aspectRatio(16f / 9f),
            shape = CardDefaults.shape(shape),
            colors = CardDefaults.colors(containerColor = Palette.Surface, focusedContainerColor = Palette.Surface),
            scale = CardDefaults.scale(focusedScale = Focus.CARD_SCALE),
            border = CardDefaults.border(focusedBorder = Focus.border(shape)),
            glow = CardDefaults.glow(focusedGlow = Focus.glow),
        ) {
            Box(
                Modifier.fillMaxSize().onSizeChanged {
                    cardSize[0] = it.width
                    cardSize[1] = it.height
                },
            ) {
                RemoteImage(repo.url(recording.poster), ContentScale.Crop, Modifier.fillMaxSize(), repo.token, opaque = true)
                Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (recording.recording) Badge(RECORDING_BADGE, Palette.Recording, Color.White)
                    // 焼いている間は進み (denpa の知らせで動く)
                    encodingOf(repo, recording.id)?.let { Badge("エンコード中 ${(it * 100).toInt()}%", Palette.Background.copy(alpha = 0.85f), Palette.Text) }
                }
                // 番組名は下の縁に。上は暗い帯へ溶かす (明るい絵・字の入った絵でも読めるように)
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(TITLE_SCRIM)
                        .padding(start = CARD_TITLE_PADDING, end = CARD_TITLE_PADDING, top = 24.dp, bottom = CARD_TITLE_PADDING),
                ) {
                    EpisodeTitle(recording.title, CARD_TITLE, Color.White, maxLines = 2, width = titleWidth, unwatched = recording.unwatched)
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
            cardMeta(recording),
            style = MaterialTheme.typography.labelLarge,
            color = Palette.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 番組名の折り方: 文節で折り (日本語として組む。端末の言語が英語でも)、行頭に「っ」「ー」などを置かない。
 * `WordBreak.Phrase` は Android 13 から (それより前は字ごとに折る)
 */
private val TITLE_TEXT = TextStyle(
    lineBreak = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Strict, LineBreak.WordBreak.Phrase),
    localeList = LocaleList("ja-JP"),
)

/**
 * 格子の列の数。**3 列にしてカードを大きく** (幅 260dp ほど)。4 列 (180dp ほど) では番組名が2行に 9 字ほどしか入らず、
 * 長い題はほとんど切れていた。左のメニューを細くした幅もカードに回す
 */
private const val GRID_COLUMNS = 3

/** カードの番組名 (カードの幅に合わせた大きさ。2行に 12 字ほど入り、離れても読める大きさと太さ) と、帯の中の余白 */
private val CARD_TITLE = TITLE_TEXT.copy(
    fontSize = 20.sp,
    lineHeight = 27.sp,
    fontWeight = FontWeight.SemiBold,
)
private val CARD_TITLE_PADDING = 12.dp

/** 番組名の下に敷く帯 (上は透かし、字のあたりは濃く) */
private val TITLE_SCRIM = Brush.verticalGradient(0f to Color.Transparent, 0.3f to Color(0xC0000000), 1f to Color(0xF0000000))

/** カードの2行目: 局と放送の時刻 (日は見出しにある) */
private fun cardMeta(recording: Recording): String =
    listOfNotNull(recording.serviceName?.let(::shortServiceName), TIME.format(Date(recording.startAt))).joinToString("  ・  ")

private val TIME = SimpleDateFormat("HH:mm", Locale.JAPAN)

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
/** 上の段の放送日時 */
private val WHEN = SimpleDateFormat("M/d(E) HH:mm", Locale.JAPAN)

/** 一覧の左の端 (上の段の文字と揃える) */
private val EDGE = 32.dp

/** 上の段の高さと、文字の幅 (1行を長くしすぎない) */
private val HERO_HEIGHT = 196.dp
private val HERO_TEXT_WIDTH = 640.dp

/** 背景の絵を替えるまで合いが止まっている間 (ミリ秒) と、入れ替えの長さ */
private const val HERO_SETTLE_MS = 450L
private const val BACKDROP_FADE_MS = 250

/** 止まった録画の前後で、先にポスターを読む件数 (前後2段ぶん) */
private const val PREFETCH = GRID_COLUMNS * 2

/** 先読みの前に、カードが測られるのを待つ回数と間 (ミリ秒) */
private const val CARD_SIZE_WAITS = 20
private const val CARD_SIZE_WAIT_MS = 50L

/** `at` の前後 `count` 件の位置を、近い順に (`at`, `at+1`, `at-1`, `at+2`, …)。端は飛ばす */
internal fun nearFirst(at: Int, count: Int, size: Int): List<Int> = buildList {
    if (at in 0 until size) add(at)
    for (d in 1..count) {
        if (at + d < size) add(at + d)
        if (at - d >= 0) add(at - d)
    }
}

/** 背景の絵の大きさ (画面の幅・高さに対して) */
private const val BACKDROP_WIDTH = 0.72f
private val BACKDROP_HEIGHT = HERO_HEIGHT + 32.dp

/** 背景の絵を、左は地の色へ溶かし、右も暗くする */
private val BACKDROP_LEFT = Brush.horizontalGradient(
    0f to Palette.Background,
    0.4f to Palette.Background.copy(alpha = 0.78f),
    1f to Palette.Background.copy(alpha = 0.45f),
)
private val BACKDROP_BOTTOM = Brush.verticalGradient(0.35f to Color.Transparent, 0.85f to Palette.Background)

/** 格子の上の余白 (合わせた段のカードが膨らんで光るぶん)、右の余白、カードの間 */
private val GRID_TOP = 14.dp
private val GRID_END = 40.dp
private val GRID_GAP = 24.dp

/**
 * 格子の上の端の溶かし。上半分は塗りつぶす (上に隠れた日の見出しの字の裾が覗かないように)。
 * 上の余白 (`GRID_TOP`) より短くし、上の端に揃えた段の膨らんだカードの縁には掛けない
 */
private val GRID_FADE_HEIGHT = 9.dp

private val GRID_FADE = Brush.verticalGradient(0f to Palette.Background, 0.5f to Palette.Background, 1f to Color.Transparent)

/** 一覧の終わりの余白と、下の端の溶かし (3 列ではカードが大きく、次の段は番組名の途中で切れるので) */
private val GRID_BOTTOM = 48.dp
private val GRID_BOTTOM_FADE_HEIGHT = 40.dp
private val GRID_BOTTOM_FADE = Brush.verticalGradient(0f to Color.Transparent, 1f to Palette.Background)

/**
 * **合わせたカードの段を、格子の上の端に揃える** (Google TV の一覧と同じく、目の置き場を変えない)。
 * テレビの既定 (合わせたものを枠の 3 割の高さへ) では、上の段の番組名だけが絵から切れて覗いていた。
 * 一覧の終わりはそれ以上送らない (下に余白を足さない) ので、一番下 (開いたときに合う、いちばん古い録画) では
 * その上の段も見えている
 */
@OptIn(ExperimentalFoundationApi::class)
private class RowTop(private val top: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - top
}
