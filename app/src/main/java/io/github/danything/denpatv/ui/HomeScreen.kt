package io.github.danything.denpatv.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.Service
import io.github.danything.denpatv.data.Unauthorized
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** ホーム。ライブの局と、新しい録画を横の列で並べる */
@Composable
fun HomeScreen(
    repo: Repository,
    onLive: (Service) -> Unit,
    onWatch: (Recording) -> Unit,
    onSettings: () -> Unit,
    onUnauthorized: () -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    var services by remember { mutableStateOf(repo.services) }
    var recordings by remember { mutableStateOf(repo.recordings) }
    var loaded by remember { mutableStateOf(services.isNotEmpty()) }
    var retry by remember { mutableIntStateOf(0) }
    /** 消すかどうか聞いている録画 */
    var deleting by remember { mutableStateOf<Recording?>(null) }
    val scope = rememberCoroutineScope()
    val firstCard = remember { FocusRequester() }

    LaunchedEffect(retry) {
        try {
            repo.refresh()
            services = repo.services
            recordings = repo.recordings
            error = null
        } catch (_: Unauthorized) {
            // トークンが外された・期限切れ、または家の外と見なされた。繋ぐ画面へ
            onUnauthorized()
            return@LaunchedEffect
        } catch (e: Exception) {
            error = "denpa から一覧を取れません: ${e.message}"
        }
        loaded = true
    }
    // 開いたら最初の局に合わせる (どこにも合っていないと、リモコンの最初の1押しが空振りする)
    LaunchedEffect(loaded, error) {
        if (loaded && error == null && services.isNotEmpty()) runCatching { firstCard.requestFocus() }
    }

    if (!loaded) {
        Centered("読み込んでいます…")
        return
    }
    error?.let { message ->
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(message, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { retry++ }) { Text("やり直す") }
                Button(onClick = onSettings) { Text("繋ぐ先を変える") }
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item { Section("ライブ") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(services, key = { _, it -> it.id }) { index, service ->
                    ServiceCard(repo, service, if (index == 0) Modifier.focusRequester(firstCard) else Modifier) { onLive(service) }
                }
            }
        }
        item { Section("録画") }
        item {
            if (recordings.isEmpty()) {
                Text("観られる録画はまだありません", modifier = Modifier.padding(horizontal = 48.dp))
            } else {
                LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(recordings, key = { it.id }) { recording ->
                        RecordingCard(repo, recording, onClick = { onWatch(recording) }, onLongClick = { deleting = recording })
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Button(onClick = onSettings) { Text("設定") }
                Text("録画は長押しで消せます", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterVertically))
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
                    val deleted = try {
                        repo.api.deleteRecording(repo.base, recording.id)
                    } catch (_: Unauthorized) {
                        onUnauthorized()
                        return@launch
                    }
                    if (deleted) {
                        runCatching { repo.refresh() }
                        services = repo.services
                        recordings = repo.recordings
                    }
                }
            },
        )
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

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 48.dp))
}

@Composable
private fun ServiceCard(repo: Repository, service: Service, modifier: Modifier, onClick: () -> Unit) {
    // 高さをそろえる (いま放送中の番組が無い局で列ががたつかないように)
    Card(onClick = onClick, modifier = modifier.width(200.dp).height(150.dp)) {
        Box(
            Modifier.fillMaxWidth().height(80.dp).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val logo = repo.url(service.logo)
            if (logo != null) RemoteImage(logo, ContentScale.Fit, Modifier.fillMaxSize(), repo.token)
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            Text(
                listOfNotNull(service.remoteControlKey?.toString(), service.name).joinToString(" "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // いま放送中の番組 (古い denpa は返さないので出さない)
            // サブチャンネルは番組名が空で来る (メインと同じ番組)。空なら名前は出さず、進み具合だけ
            service.now?.let { now ->
                val at = System.currentTimeMillis()
                if (now.title.isNotBlank()) {
                    Text(now.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(now.progress(at)).height(3.dp).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

@Composable
private fun RecordingCard(repo: Repository, recording: Recording, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.width(280.dp).height(250.dp)) {
        RemoteImage(repo.url(recording.poster), ContentScale.Crop, Modifier.fillMaxWidth().aspectRatio(16f / 9f), repo.token)
        Column(Modifier.padding(12.dp)) {
            Text(recording.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(recording.serviceName, DATE.format(Date(recording.startAt))).joinToString(" ・ "),
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private val DATE = SimpleDateFormat("M/d(E) HH:mm", Locale.JAPAN)
