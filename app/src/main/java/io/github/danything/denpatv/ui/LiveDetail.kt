package io.github.danything.denpatv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.danything.denpatv.data.NowProgram
import io.github.danything.denpatv.data.ProgramInfo
import io.github.danything.denpatv.data.ProgramLookup
import io.github.danything.denpatv.data.Service
import io.github.danything.denpatv.data.Unauthorized
import io.github.danything.denpatv.data.programMeta
import kotlinx.coroutines.delay

/**
 * ライブの詳しく (決定の長押し・情報キー)。**いま映している局で放送中の番組**を、録画の詳しくと同じもの
 * (`ProgramDetailDialog`) で出す。番組名・局・時間と進み・残り・札 (ジャンル・映像・音声)・説明・放送の詳細。
 *
 * - 中身は denpa の `api/programs/<局の now.id>` から開いたときに取る。取れるまでと、取れないとき (番組表から消えた・
 *   口の無い古い denpa・`now.id` の無い古い denpa) は `now` のぶん (番組名・時間) だけ
 * - 札は **「録画」(録っている・録る予定なら「録画中」「録画予約済み」) → 「閉じる」**。録画はメニューの「録画」と同じ口
 *   (`POST api/services/<id>/record`)。結果は札の下に1行
 * - 番組が替わったら (局を取り直して `now` が替わる) 新しい番組を取り直す。映像はそのまま流れる
 */
@Composable
fun LiveDetailDialog(
    repo: Repository,
    service: Service,
    onRecord: () -> Unit,
    /** 録画を押した結果の1行 */
    note: String?,
    onClose: () -> Unit,
    onUnauthorized: () -> Unit,
) {
    val now = service.now
    val info by produceState<ProgramInfo?>(null, now?.id) {
        value = null
        val id = now?.id ?: return@produceState
        value = try {
            (repo.api.program(repo.base, id) as? ProgramLookup.Found)?.info
        } catch (_: Unauthorized) {
            onUnauthorized()
            null
        }
    }
    // 進みと残りは開いている間も進める
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(PROGRESS_TICK_MS)
            clock = System.currentTimeMillis()
        }
    }
    val actions = listOf(
        DetailAction(recordLabel(now), onRecord),
        DetailAction("閉じる", onClose),
    )
    ProgramDetailDialog(liveFacts(service, info, clock).copy(logo = repo.url(service.logo)), actions, onClose, note, overVideo = true, token = repo.token)
}

/** 録画の札の名前 (メニューの「録画」の札と同じ) */
internal fun recordLabel(now: NowProgram?): String = when {
    now?.recording == true -> "録画中"
    now?.reserved == true -> "録画予約済み"
    else -> "録画"
}

/** ライブの詳しくの中身。`info` (番組表の中身) が無ければ `now` のぶんだけ */
internal fun liveFacts(service: Service, info: ProgramInfo?, at: Long): DetailFacts {
    val now = service.now
        ?: return DetailFacts(title = service.name, meta = "番組表にいまの番組がありません")
    val title = now.title.ifBlank { info?.name.orEmpty() }.ifBlank { service.name }
    return DetailFacts(
        title = title,
        meta = programMeta(service.name, now.startAt, now.endAt),
        chips = info?.chips.orEmpty(),
        badge = when {
            now.recording -> "● 録画中"
            now.reserved -> "録画予約済み"
            else -> null
        },
        progress = (at - now.startAt).coerceAtLeast(0) to (now.endAt - now.startAt),
        progressLabel = "あと${now.remainingMinutes(at)}分",
        description = info?.description.orEmpty(),
        extended = info?.extended.orEmpty(),
    )
}

/** 詳しくの進みを進める間 (ミリ秒) */
private const val PROGRESS_TICK_MS = 15_000L
