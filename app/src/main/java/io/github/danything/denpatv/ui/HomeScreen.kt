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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.Service
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
) {
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(repo.services.isNotEmpty()) }
    var retry by remember { mutableIntStateOf(0) }

    LaunchedEffect(retry) {
        try {
            repo.refresh()
            error = null
        } catch (e: Exception) {
            error = "denpa から一覧を取れません: ${e.message}"
        }
        loaded = true
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
                items(repo.services, key = { it.id }) { service -> ServiceCard(repo, service) { onLive(service) } }
            }
        }
        item { Section("録画") }
        item {
            if (repo.recordings.isEmpty()) {
                Text("観られる録画はまだありません", modifier = Modifier.padding(horizontal = 48.dp))
            } else {
                LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(repo.recordings, key = { it.id }) { recording -> RecordingCard(repo, recording) { onWatch(recording) } }
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 48.dp)) {
                Button(onClick = onSettings) { Text("設定") }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 48.dp))
}

@Composable
private fun ServiceCard(repo: Repository, service: Service, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.width(200.dp)) {
        Box(
            Modifier.fillMaxWidth().height(80.dp).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val logo = repo.url(service.logo)
            if (logo != null) {
                AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            Text(
                listOfNotNull(service.remoteControlKey?.toString(), service.name).joinToString(" "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // いま放送中の番組 (古い denpa は返さないので出さない)
            service.now?.let { now ->
                val at = System.currentTimeMillis()
                Text(now.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(now.progress(at)).height(3.dp).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

@Composable
private fun RecordingCard(repo: Repository, recording: Recording, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.width(280.dp)) {
        AsyncImage(
            model = repo.url(recording.poster),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )
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
