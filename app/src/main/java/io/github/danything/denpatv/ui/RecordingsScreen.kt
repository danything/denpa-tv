package io.github.danything.denpatv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.codecLabels
import io.github.danything.denpatv.data.durationLabel
import io.github.danything.denpatv.data.watched
import io.github.danything.denpatv.data.Unauthorized
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 録画の一覧。**数が多いので格子にして、放送日ごとに見出しを挟む** (新しい順)。
 * 開くと一番下 (いちばん古い録画) に合わせる。長押しで詳しく (説明・再生・削除)。
 * 観て戻ってきたら、開いた録画に合わせ直す
 */
@Composable
fun RecordingsScreen(
    repo: Repository,
    onWatch: (Recording) -> Unit,
    onUnauthorized: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var recordings by remember { mutableStateOf(repo.recordings) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(recordings.isNotEmpty()) }
    var retry by remember { mutableIntStateOf(0) }
    /** 詳しいところを開いている録画 (長押し) */
    var opened by remember { mutableStateOf<Recording?>(null) }
    /** 最後に合わせていた録画。観て戻ってきたらここに合わせ直す (画面を作り直しても残る) */
    var lastFocused by rememberSaveable { mutableStateOf<Long?>(null) }
    val requesters = remember { mutableMapOf<Long, FocusRequester>() }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()

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
        // 戻ってきたときは読み直さない (並びが変わると合わせ直す先がずれる)
        if (repo.recordings.isEmpty() || retry > 0) guarded { repo.refreshRecordings() }
    }
    LaunchedEffect(loaded) {
        // 再生の画面で消して戻ってきたら、その隣に合わせる
        repo.focusOnReturn?.let { lastFocused = it; repo.focusOnReturn = null }
        // 観て戻ったら開いた録画に。初めては一番下 (いちばん古い録画): 古いものから片付けられるように (ブラウザの denpa と同じ)
        val id = lastFocused?.takeIf { id -> recordings.any { it.id == id } } ?: recordings.lastOrNull()?.id ?: return@LaunchedEffect
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
            Button(onClick = { retry++ }) { Text("やり直す") }
        }
        return
    }
    if (recordings.isEmpty()) return Centered("観られる録画はまだありません")

    val groups = recordings.groupBy { DAY.format(Date(it.startAt)) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        state = grid,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 32.dp, end = 48.dp, top = 32.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("録画", style = MaterialTheme.typography.headlineMedium)
                Text("長押しで詳しく (説明・削除)", style = MaterialTheme.typography.bodyMedium)
            }
        }
        groups.forEach { (day, items) ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "day:$day", contentType = "day") {
                Text(day, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
            items.forEach { recording ->
                item(key = recording.id, contentType = "recording") {
                    val requester = remember(recording.id) { requesters.getOrPut(recording.id) { FocusRequester() } }
                    RecordingCard(
                        repo,
                        recording,
                        modifier = Modifier.focusRequester(requester).onFocusChanged {
                            if (it.isFocused) lastFocused = recording.id
                        },
                        onClick = { onWatch(recording) },
                        onLongClick = { opened = recording },
                    )
                }
            }
        }
    }

    opened?.let { recording ->
        RecordingDetailDialog(
            repo,
            recording,
            onPlay = { opened = null; onWatch(recording) },
            onDelete = {
                opened = null
                scope.launch {
                    guarded {
                        if (repo.api.deleteRecording(repo.base, recording.id)) {
                            // 消したものの隣に合わせ直す。読み直さず手元から抜く (続きまで読んだぶんを残す)
                            lastFocused = repo.forgetRecording(recording.id)
                        }
                    }
                    withFrameNanos { }
                    lastFocused?.let { id ->
                        if (grid.layoutInfo.visibleItemsInfo.none { it.key == id }) gridIndex(recordings, id)?.let { grid.scrollToItem(it) }
                        withFrameNanos { }
                        runCatching { requesters[id]?.requestFocus() }
                    }
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
 * 録画のカード。**ブラウザの denpa の録画の行と同じものを出す**: 番組名・局・放送日時・長さ・観た割合・形の札。
 * テレビは離れて観るので、文字は小さくしすぎない
 */
@Composable
private fun RecordingCard(
    repo: Repository,
    recording: Recording,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(onClick = onClick, onLongClick = onLongClick, modifier = modifier.height(256.dp)) {
        Box {
            RemoteImage(repo.url(recording.poster), ContentScale.Crop, Modifier.fillMaxWidth().aspectRatio(16f / 9f), repo.token)
            // 観た割合 (続きの位置があるときだけ)。ポスターの下の縁に
            recording.watched?.let { part ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color(0x99000000))) {
                    Box(Modifier.fillMaxWidth(part).height(5.dp).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(recording.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            // 4 列だと 1 行に収まらないので、局と日時・長さを分ける
            recording.serviceName?.let {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                listOfNotNull(WHEN.format(Date(recording.startAt)), recording.durationMs?.let(::durationLabel)).joinToString(" ・ "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            // メニューが開いて狭くなっても札を潰さない (はみ出すぶんは切れる)
            Row(Modifier.horizontalScroll(rememberScrollState(), enabled = false), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                recording.codecLabels.forEach { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** 格子の中の位置 (頭の見出し・日の見出しも数える。下の LazyVerticalGrid の並びと同じ) */
private fun gridIndex(recordings: List<Recording>, id: Long): Int? {
    var index = 1
    recordings.groupBy { DAY.format(Date(it.startAt)) }.values.forEach { items ->
        index++
        val at = items.indexOfFirst { it.id == id }
        if (at >= 0) return index + at
        index += items.size
    }
    return null
}

private val DAY = SimpleDateFormat("M月d日(E)", Locale.JAPAN)
private val WHEN = SimpleDateFormat("M/d(E) HH:mm", Locale.JAPAN)
