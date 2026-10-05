package io.github.danything.denpatv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.RecordingDetail
import io.github.danything.denpatv.data.codecLabels
import io.github.danything.denpatv.data.durationLabel
import io.github.danything.denpatv.data.Unauthorized
import java.util.Date

/**
 * 録画の詳しいところ (カードの長押しで開く)。局・日時・長さ・形・続きの位置と、番組の説明。
 *
 * - **再生 (続きから) に合わせて開く** — 決定の押し間違いで消えないように
 * - 削除はブラウザの denpa と同じ2回押し
 * - 説明は別の口 (`api/recordings/<id>/detail`) から開いたときに取る。古い denpa には無いので、そのときは出さない。
 *   説明の段落は1つずつ合わせられるようにしてあり、下キーで読み進められる (長い出演者の欄など)
 */
@Composable
fun RecordingDetailDialog(
    repo: Repository,
    recording: Recording,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onUnauthorized: () -> Unit,
) {
    val play = remember { FocusRequester() }
    val delete = rememberTwoPress()
    val detail by produceState<RecordingDetail?>(null, recording.id) {
        value = try {
            repo.api.recordingDetail(repo.base, recording.id)
        } catch (_: Unauthorized) {
            onUnauthorized()
            null
        }
    }
    LaunchedEffect(Unit) { runCatching { play.requestFocus() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // 長押しで開いたので、離すまでのキーがこの窓に来る。「再生」が押されないよう捨てる
        Row(
            Modifier
                .ignoreHeldCenter()
                .width(1200.dp)
                .fillMaxHeight(0.86f)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(32.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Column(Modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RemoteImage(repo.url(recording.poster), ContentScale.Crop, Modifier.width(400.dp).aspectRatio(16f / 9f), repo.token)
                val resume = recording.resumeMs?.takeIf { it > 0 }
                Button(onClick = onPlay, modifier = Modifier.focusRequester(play)) {
                    Text(if (resume != null) "続きから再生 (${position(resume)})" else "再生")
                }
                OutlinedButton(onClick = { if (delete.press()) onDelete() }) {
                    // 押すと「もう一度押すと削除」と長くなる。1行のまま伸ばす (切れないように)
                    Text(deleteLabel(delete.armed), maxLines = 1, softWrap = false)
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(recording.title, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            listOfNotNull(
                                recording.serviceName,
                                WHEN.format(Date(recording.startAt)),
                                recording.durationMs?.let(::durationLabel),
                            ).joinToString(" ・ "),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            if (recording.recording) "● 録画中 (追っかけ再生で観ます)" else recording.codecLabels.joinToString(" / "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                val info = detail
                if (info != null) {
                    if (info.description.isNotBlank()) {
                        item { Paragraph(null, info.description) }
                    }
                    info.extended.forEach { (heading, body) -> item { Paragraph(heading, body) } }
                }
            }
        }
    }
}

/** 説明の1段落。合わせると (下キーで) 読み進められるよう focusable にしてある */
@Composable
private fun Paragraph(heading: String?, body: String) {
    Column(Modifier.focusable().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        heading?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        Text(body, style = MaterialTheme.typography.bodyLarge)
    }
}

/** 再生位置 (1:02:03) */
fun position(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}
