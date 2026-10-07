package io.github.danything.denpatv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import io.github.danything.denpatv.data.AudioSide
import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.RecordingDetail
import io.github.danything.denpatv.data.Unauthorized
import io.github.danything.denpatv.data.chasing
import io.github.danything.denpatv.data.codecLabels
import io.github.danything.denpatv.data.dualMonoLabels
import io.github.danything.denpatv.data.programMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 録画の詳しく (カードの長押しで開く)。中身の並びは `ProgramDetailDialog` (ライブ・再生中と同じもの)。
 *
 * - 札は **「続きから再生 (0:14:22)」(続きがあれば) → 「最初から」→「削除」→「閉じる」**。開いたときは先頭に合う
 *   (決定の押し間違いで消えないように)。続きが無ければ「再生」
 * - 削除はブラウザの denpa と同じ2回押し
 * - 説明は別の口 (`api/recordings/<id>/detail`) から開いたときに取る。古い denpa には無いので、そのときは出さない
 * - **録画は番組表の口 (`api/programs/<id>`) を引かない。** 番組表の行は終わると消え・入れ替わり、番組 ID も使い回されるので、
 *   録り始めに写した録画自身の中身 (`detail`) だけを出す (番組表を引くのはライブの詳しくだけ)
 */
@Composable
fun RecordingDetailDialog(
    repo: Repository,
    recording: Recording,
    /** 観る。true なら続きではなく頭から */
    onPlay: (fromStart: Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onUnauthorized: () -> Unit,
) {
    val delete = rememberTwoPress()
    val resume = recording.resumeMs?.takeIf { it > 0 }
    val play = if (recording.chasing) "追っかけ再生" else "再生"
    val actions = buildList {
        if (resume != null) {
            add(DetailAction("続きから$play (${position(resume)})") { onPlay(false) })
            add(DetailAction("最初から") { onPlay(true) })
        } else {
            add(DetailAction(play) { onPlay(false) })
        }
        add(DetailAction(deleteLabel(delete.armed)) { if (delete.press()) onDelete() })
        add(DetailAction("閉じる", onDismiss))
    }
    // 後ろに録画の絵をうすく敷く (一覧の上で開くとき。映像の上で開くときは映像が見えるので敷かない)
    val facts = recordingFacts(recording, rememberRecordingDetail(repo, recording, onUnauthorized))
    ProgramDetailDialog(facts, actions, onDismiss, backdrop = repo.url(recording.poster), token = repo.token)
}

/**
 * 再生の画面 (録画・追っかけ) の決定の長押しで開く詳しく。一覧のものと同じ中身で、札は **「閉じる」(映像に戻るだけ) が先頭**、
 * 次に「削除」。映像は止めも動かしもしない。戻る・「閉じる」で閉じて映像に戻る (合いは PlayerFrame が取り戻す)
 */
@Composable
fun PlayerDetailDialog(repo: Repository, recording: Recording, onDelete: () -> Unit, onClose: () -> Unit, onUnauthorized: () -> Unit) {
    val delete = rememberTwoPress()
    val actions = listOf(
        DetailAction("閉じる", onClose),
        DetailAction(deleteLabel(delete.armed)) { if (delete.press()) { onClose(); onDelete() } },
    )
    // 観た位置は出さない (一覧で読んだときのもので、観ているいまの位置とずれる)
    ProgramDetailDialog(recordingFacts(recording, rememberRecordingDetail(repo, recording, onUnauthorized), watched = false), actions, onClose, overVideo = true)
}

/** 番組の中身を取る (開いたときに1度)。古い denpa・読めなければ null のまま */
@Composable
private fun rememberRecordingDetail(repo: Repository, recording: Recording, onUnauthorized: () -> Unit): RecordingDetail? {
    val detail by produceState<RecordingDetail?>(null, recording.id) {
        value = try {
            repo.api.recordingDetail(repo.base, recording.id)
        } catch (_: Unauthorized) {
            onUnauthorized()
            null
        }
    }
    return detail
}

/** 録画の詳しくの中身。札は形 (AV1 / H.264 / 生TS) と音声、録画中なら頭に「● 録画中」。観た位置があれば進みの帯 */
internal fun recordingFacts(recording: Recording, detail: RecordingDetail?, watched: Boolean = true): DetailFacts {
    val end = recording.durationMs?.let { recording.startAt + it } ?: recording.endAt
    val length = recording.durationMs ?: end?.minus(recording.startAt)
    val resume = recording.resumeMs?.takeIf { it > 0 && watched }
    return DetailFacts(
        title = recording.title,
        meta = programMeta(recording.serviceName, recording.startAt, end),
        chips = (if (recording.recording) emptyList() else recording.codecLabels) + recordingAudioLabels(recording),
        badge = if (recording.recording) "● 録画中" else null,
        progress = if (resume != null && length != null && length > 0) resume to length else null,
        progressLabel = resume?.let { "${position(it)} まで観た" },
        description = detail?.description.orEmpty(),
        extended = detail?.extended?.toList().orEmpty(),
    )
}

/**
 * 録画の音声の札。音声ごとに1つ (デュアルモノは「主音声 / 副音声」)。denpa が名前を言っていない既定の1本 (「音声」) は出さない
 */
private fun recordingAudioLabels(recording: Recording): List<String> =
    recording.audios.map { it.stream }.distinct().mapNotNull { stream ->
        val dual = dualMonoLabels(recording.audios, stream)
        if (dual != null) {
            "${dual[AudioSide.Main]} / ${dual[AudioSide.Sub]}"
        } else {
            recording.audios.firstOrNull { it.stream == stream && it.side == "both" }?.label?.takeIf { it.isNotBlank() && it != "音声" }
        }
    }.distinct()

/**
 * 再生の画面 (録画・追っかけ) から録画を消す。消せたら一覧から抜き、戻ったときに隣の録画に合うようにして `onDeleted`
 * (一覧へ戻る)。録画中などで断られたら1行知らせる
 */
fun CoroutineScope.deleteFromPlayer(
    repo: Repository,
    id: Long,
    flash: (String) -> Unit,
    onUnauthorized: () -> Unit,
    onDeleted: () -> Unit,
) = launch {
    val done = try {
        repo.api.deleteRecording(repo.base, id)
    } catch (_: Unauthorized) {
        return@launch onUnauthorized()
    }
    if (!done) return@launch flash(NOT_DELETED)
    repo.focusOnReturn = repo.forgetRecording(id)
    onDeleted()
}

/** 消せなかったときの1行 (一覧・再生の画面) */
const val NOT_DELETED = "消せませんでした (録画中は消せません)"

/** 再生位置 (1:02:03) */
fun position(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}
