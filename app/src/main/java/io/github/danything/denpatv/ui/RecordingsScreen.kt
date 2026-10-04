package io.github.danything.denpatv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.Unauthorized
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 録画の一覧。**数が多いので格子にして、放送日ごとに見出しを挟む** (新しい順)。
 * 終わりに近づいたら続きを読む。長押しで消す (確かめてから)。
 * 観て戻ってきたら、開いた録画に合わせ直す
 */
@Composable
fun RecordingsScreen(
    repo: Repository,
    onWatch: (Recording) -> Unit,
    onUnauthorized: () -> Unit,
    modifier: Modifier = Modifier,
    /** 開いたときに一覧へ合わせるか (ライブから戻ったときは、横のメニューに合わせるので false) */
    takeFocus: Boolean = true,
) {
    var recordings by remember { mutableStateOf(repo.recordings) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(recordings.isNotEmpty()) }
    var retry by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<Recording?>(null) }
    /** 続きを読めなかった (一覧は残し、終わりに小さく出してやり直せるようにする) */
    var moreError by remember { mutableStateOf<String?>(null) }
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

    suspend fun loadMore() {
        try {
            repo.loadMoreRecordings()
            moreError = null
        } catch (_: Unauthorized) {
            onUnauthorized()
        } catch (e: Exception) {
            moreError = "続きを読めませんでした: ${e.message}"
        }
        recordings = repo.recordings
    }

    LaunchedEffect(retry) {
        // 戻ってきたときは読み直さない (並びが変わると合わせ直す先がずれる)
        if (repo.recordings.isEmpty() || retry > 0) guarded { repo.refreshRecordings() }
    }
    LaunchedEffect(loaded) {
        if (!takeFocus) return@LaunchedEffect
        val id = lastFocused ?: recordings.firstOrNull()?.id ?: return@LaunchedEffect
        withFrameNanos { }
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
                Text("長押しで消せます", style = MaterialTheme.typography.bodySmall)
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
                            if (!it.isFocused) return@onFocusChanged
                            lastFocused = recording.id
                            // 終わりに近づいたら続きを読む
                            val index = recordings.indexOfFirst { r -> r.id == recording.id }
                            if (repo.hasMoreRecordings && moreError == null && index >= recordings.size - 12) {
                                scope.launch { loadMore() }
                            }
                        },
                        onClick = { onWatch(recording) },
                        onLongClick = { deleting = recording },
                    )
                }
            }
        }
        moreError?.let { message ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "more-error") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { moreError = null; scope.launch { loadMore() } }) { Text("やり直す") }
                }
            }
        }
    }

    deleting?.let { recording ->
        ConfirmDelete(
            title = recording.title,
            onCancel = { deleting = null },
            onConfirm = {
                deleting = null
                scope.launch {
                    guarded {
                        if (repo.api.deleteRecording(repo.base, recording.id)) {
                            // 消したものの隣に合わせ直す。読み直さず手元から抜く (続きまで読んだぶんを残す)
                            val index = recordings.indexOfFirst { it.id == recording.id }
                            lastFocused = (recordings.getOrNull(index + 1) ?: recordings.getOrNull(index - 1))?.id
                            repo.forgetRecording(recording.id)
                        }
                    }
                    withFrameNanos { }
                    lastFocused?.let { runCatching { requesters[it]?.requestFocus() } }
                }
            },
        )
    }
}

@Composable
private fun RecordingCard(
    repo: Repository,
    recording: Recording,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(onClick = onClick, onLongClick = onLongClick, modifier = modifier.height(216.dp)) {
        RemoteImage(repo.url(recording.poster), ContentScale.Crop, Modifier.fillMaxWidth().aspectRatio(16f / 9f), repo.token)
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(recording.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(recording.serviceName, TIME.format(Date(recording.startAt))).joinToString(" ・ "),
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * 消す前に聞く。**最初はキャンセルに合わせておく** — 決定の押し間違いで消えないように
 */
@Composable
private fun ConfirmDelete(title: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancel.requestFocus() }
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("録画を消しますか", style = MaterialTheme.typography.headlineSmall)
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("ファイルも消えます。元に戻せません", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onCancel, modifier = Modifier.focusRequester(cancel)) { Text("キャンセル") }
                OutlinedButton(onClick = onConfirm) { Text("消す") }
            }
        }
    }
}

private val DAY = SimpleDateFormat("M月d日(E)", Locale.JAPAN)
private val TIME = SimpleDateFormat("HH:mm", Locale.JAPAN)
