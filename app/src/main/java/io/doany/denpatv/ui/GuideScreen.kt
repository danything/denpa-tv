package io.doany.denpatv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.layout.LazyLayout
import androidx.compose.foundation.lazy.layout.LazyLayoutItemProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.doany.denpatv.data.CancelResult
import io.doany.denpatv.data.DenpaEvent
import io.doany.denpatv.data.GuideAction
import io.doany.denpatv.data.GuideCell
import io.doany.denpatv.data.GuideLayout
import io.doany.denpatv.data.GuideMove
import io.doany.denpatv.data.GuideMoves
import io.doany.denpatv.data.GuideProgram
import io.doany.denpatv.data.GuideService
import io.doany.denpatv.data.GuideSpot
import io.doany.denpatv.data.GuideWindows
import io.doany.denpatv.data.ProgramInfo
import io.doany.denpatv.data.ProgramLookup
import io.doany.denpatv.data.ReserveResult
import io.doany.denpatv.data.Reservation
import io.doany.denpatv.data.ReservationState
import io.doany.denpatv.data.Unauthorized
import io.doany.denpatv.data.airing
import io.doany.denpatv.data.broadcastDay
import io.doany.denpatv.data.dayHour
import io.doany.denpatv.data.guideActions
import io.doany.denpatv.data.guideLayout
import io.doany.denpatv.data.guideTooOld
import io.doany.denpatv.data.guideTooOldMessage
import io.doany.denpatv.data.liveChannelKey
import io.doany.denpatv.data.nextDay
import io.doany.denpatv.data.number
import io.doany.denpatv.data.previousDay
import io.doany.denpatv.data.programMeta
import io.doany.denpatv.data.remoteKeyService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 番組表のフォーカスの位置 (観に行って戻ったときに戻す。`Repository.guideReturn`) */
data class GuidePlace(val type: String, val serviceId: Long?, val programId: Long?, val anchor: Long)

/**
 * **番組表** (左のメニューの「番組表」。ドロワーの右に出す)。局を横、時刻を縦に並べる。
 *
 * - 上の段に種別 (局のある種別だけ)・日付・時刻の飛び先 (今・朝・昼・夜・深夜)。決定でその種別・日・時刻へ表を動かす (フォーカスは上の段のまま。下キーで表へ)
 * - 表は**全体で1つのフォーカスできる部品** (`GuideGrid`) で、キーを全部ここで受ける。持つのは列・番組・基準の時刻 (`GuideMoves`)
 * - 開いたときは、今日・最後に観ていた局・いま流れている番組にフォーカスする。観に行って戻ったときは、出かけた番組に。
 *   ライブの「番組表」とリンク (`denpa://guide`) は、`Repository.guideReturn` に置いた局のいまの番組に
 * - サブチャンネルの列は、分割放送の間だけ自分のマスを持ち、ほかの時間は本チャンネルのマスが横に伸びる (`guideLayout`)
 * - マスの地の色はジャンルの大分類、録画中は録画中の赤 (`Palette.programCell`。ブラウザの denpa の番組表と同じ決まり)
 * - 「いま」は表に線を引かず、過ぎた所をマスの上から暗くして示す。時刻の列には、いまの位置に分だけのカプセルを置く
 * - 録画キーで、フォーカスしている番組を予約する (予約できない番組は詳細を開く)
 * - 取得は種別ごとに放送日 (4:00 から) の 24 時間ずつ。フォーカスが取得した端から 3 時間以内に来たら、次 (前) の 24 時間を先に取得する。
 *   denpa の `reservations`・`programs` の通知で、見ている種別の窓を取得し直す
 * - 古い denpa (`MIN_GUIDE` より前) では、真ん中に要るバージョンを出す (ほかの画面は今までどおり)。dev ビルドと読めないバージョンは確かめずに呼び、404 なら同じ文
 */
@Composable
fun GuideScreen(repo: Repository, onLive: () -> Unit, onWatch: (Long) -> Unit, onUnauthorized: () -> Unit) {
    val returning = remember {
        repo.guide.opened()
        repo.guideReturn.also { repo.guideReturn = null }
    }
    /** 局の一覧を読んだか (種別と最後に観ていた局を決める)。読めなかったら全部の種別を出す */
    var ready by remember { mutableStateOf(false) }
    var servicesFailed by remember { mutableStateOf(false) }
    var lastService by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        if (repo.services.isEmpty() || repo.servicesStale) {
            try {
                repo.refreshServices()
            } catch (_: Unauthorized) {
                return@LaunchedEffect onUnauthorized()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                servicesFailed = true
            }
        }
        lastService = repo.app.settings.lastService.first()
        ready = true
    }
    val old = guideTooOld(repo.denpaVersion)
    if (old != null || repo.guide.tooOld) return Centered(old ?: guideTooOldMessage(repo.denpaVersion))
    if (repo.guide.forbidden) return Centered(FORBIDDEN)
    if (!ready) return Centered("読み込んでいます…")
    val types = if (servicesFailed && repo.services.isEmpty()) GUIDE_TYPES else GUIDE_TYPES.filter { t -> repo.services.any { it.type == t } }
    if (types.isEmpty()) return Centered(NO_CHANNELS)
    val start = returning ?: run {
        val last = repo.services.firstOrNull { it.id == lastService }
        GuidePlace(last?.type?.takeIf { it in types } ?: types.first(), last?.id, null, System.currentTimeMillis())
    }
    GuideBody(repo, types, start, takeFocus = returning != null, onLive, onWatch, onUnauthorized)
}

@Composable
private fun GuideBody(
    repo: Repository,
    types: List<String>,
    start: GuidePlace,
    takeFocus: Boolean,
    onLive: () -> Unit,
    onWatch: (Long) -> Unit,
    onUnauthorized: () -> Unit,
) {
    val store = repo.guide
    val scope = rememberCoroutineScope()
    /** 表の始まり (開いた日の放送日の 4:00) と終わり (`DAYS` 日後) */
    val origin = remember { broadcastDay(System.currentTimeMillis()) }
    val end = remember { origin + DAYS * DAY_MS }
    var type by remember { mutableStateOf(start.type.takeIf { it in types } ?: types.first()) }
    var serviceId by remember { mutableStateOf(start.serviceId) }
    var programId by remember { mutableStateOf(start.programId) }
    var anchor by remember { mutableLongStateOf(start.anchor.coerceIn(origin, end - 1)) }
    /** 表を基準の時刻に合わせて送り直す (開いたとき・上の段で種別・日付・時刻を選んだとき・早送り)。新しいものを入れると送る */
    var jump by remember { mutableStateOf(GuideJump(NOW_ALIGN)) }
    /** いまの時刻。分の変わり目に合わせて進める (時刻の列のカプセルは分を書くので、時計の分とずらさない。ブラウザの番組表と同じ) */
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MINUTE_MS - System.currentTimeMillis() % MINUTE_MS)
            now.longValue = System.currentTimeMillis()
        }
    }
    /**
     * 過ぎた所を暗くする位置の時刻。いまの時刻が進んだら、前の時刻から `Motion.MOVE_MS` かけて送る
     * (ブラウザの番組表の `.program` の `background-size` の transition と同じ時間と曲線。覆いがマスごとに伸びる)
     */
    val shade = remember { mutableLongStateOf(now.longValue) }
    LaunchedEffect(Unit) {
        snapshotFlow { now.longValue }.collectLatest { to ->
            val from = shade.longValue
            if (from == to) return@collectLatest
            animate(0f, 1f, animationSpec = tween(Motion.MOVE_MS, easing = Motion.Ease)) { progress, _ ->
                shade.longValue = from + ((to - from) * progress).toLong()
            }
        }
    }
    val columns by remember { derivedStateOf { store.columns(type) } }
    val table = remember(columns) { guideLayout(columns) }
    val column = columns.indexOfFirst { it.id == serviceId }.coerceAtLeast(0)
    // フォーカスで辿る番組 (サブチャンネルの列では、本チャンネルの番組が分割放送で2つに分かれて入ることがある。基準の時刻を含むほう)
    val programs = table.lanes.getOrNull(column).orEmpty()
    val program = programs.indexOfFirst { it.id == programId && anchor >= it.startAt && anchor < it.endAt }.takeIf { it >= 0 }
        ?: programs.indexOfFirst { it.id == programId }.takeIf { it >= 0 }
        ?: GuideMoves.programAt(programs, anchor)
    /** フォーカスしている番組。列で見えている時間に縮めたもの (詳細・予約・読み上げには `focusedProgram`) */
    val focused = program?.let { programs.getOrNull(it) }
    val focusedProgram = focused?.let { programOf(columns, it.id)?.second }
    val focusedSpot = rememberUpdatedState(focused?.let { GuideFocus(it.id, column, it.startAt, it.endAt) })

    // ---- 取得: 基準の時刻を含む 24 時間と、端から 3 時間以内なら隣の 24 時間 ----
    val day = broadcastDay(anchor)
    val until = store.until(type)
    val want = buildList {
        add(day)
        if (anchor - day < EDGE_MS && day > origin) add(previousDay(day))
        val next = nextDay(day)
        if (next - anchor < EDGE_MS && next < end && (until == null || next < until)) add(next)
    }
    val keep = want.map { GuideWindows.Key(type, it) }.toSet()
    LaunchedEffect(type, want) {
        try {
            want.forEach { store.load(type, it, keep) }
        } catch (_: Unauthorized) {
            onUnauthorized()
        }
    }
    // denpa の通知 (予約・番組表が変わった、繋ぎ直した) で、見ている種別の窓を取得し直す。続けて来たらまとめて1回
    // (繋ぎ直したときは、取得できていなかった窓も取得し直す)
    val shownType by rememberUpdatedState(type)
    val shownWant by rememberUpdatedState(want)
    val shownKeep by rememberUpdatedState(keep)
    LaunchedEffect(Unit) {
        repo.events.filter { it == DenpaEvent.Opened || (it is DenpaEvent.Changed && it.name in GUIDE_EVENTS) }.collectLatest {
            delay(EVENT_SETTLE_MS)
            try {
                store.reload(shownType, shownWant, shownKeep)
            } catch (_: Unauthorized) {
                onUnauthorized()
            }
        }
    }

    val top = remember { FocusRequester() }
    val grid = remember { FocusRequester() }
    var gridFocused by remember { mutableStateOf(false) }
    /** 決定を表で押しはじめたか (前の画面で押した決定の離しで、詳細を開かない) */
    val centerDown = remember { BooleanArray(1) }

    /** 動いた先へ。`OutUp` は上の段へ、`OutLeft` は false (フォーカスを左のメニューへ移させる) */
    fun go(move: GuideMove): Boolean {
        when (move) {
            is GuideMove.To -> {
                val target = columns.getOrNull(move.spot.column) ?: return true
                serviceId = target.id
                programId = move.spot.program?.let { table.lanes.getOrNull(move.spot.column)?.getOrNull(it)?.id }
                anchor = move.spot.anchor
            }
            GuideMove.OutUp -> runCatching { top.requestFocus() }
            GuideMove.OutLeft -> return false
            is GuideMove.Load -> scope.launch {
                val wanted = broadcastDay(move.at)
                try {
                    store.load(type, wanted, keep + GuideWindows.Key(type, wanted))
                } catch (_: Unauthorized) {
                    onUnauthorized()
                }
            }
            GuideMove.Stay -> {}
        }
        return true
    }
    val moves = GuideMoves(table.lanes, origin, end) { store.loaded(type, broadcastDay(it)) }
    val spot = GuideSpot(column, program, anchor)

    /** 詳細を開いている番組 */
    var opened by remember { mutableStateOf<Long?>(null) }
    BackHandler(enabled = gridFocused && opened == null) { runCatching { top.requestFocus() } }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        repeat(FOCUS_TRIES) {
            if (runCatching { grid.requestFocus() }.getOrDefault(false) && gridFocused) return@LaunchedEffect
            delay(FOCUS_WAIT_MS)
        }
    }

    /** 観に行く前に、戻ったときの位置を預ける */
    fun leave() {
        repo.guideReturn = GuidePlace(type, columns.getOrNull(column)?.id, focused?.id, anchor)
    }

    /** 録画キーで予約した結果の1行 (表の下の端に `NOTICE_MS` だけ出す) */
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MS)
            notice = null
        }
    }

    /**
     * 録画キー: フォーカスしている番組を予約する。予約できない番組 (予約済み・終わった・コピー禁止) なら詳細を開く
     * (予約の状態と、取り消すボタンが見える)
     */
    fun recordKey() {
        val target = focusedProgram ?: return
        if (GuideAction.Reserve !in guideActions(target, now.longValue, airing = false)) {
            opened = target.id
            return
        }
        notice = "予約しています…"
        scope.launch {
            notice = try {
                reserveProgram(repo, target)
            } catch (_: Unauthorized) {
                return@launch onUnauthorized()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.Background).padding(top = SAFE_VERTICAL, end = SAFE_HORIZONTAL)) {
        TopBar(
            types = types,
            type = type,
            origin = origin,
            day = day,
            offline = store.offline,
            focus = top,
            down = grid,
            onType = {
                if (it != type) {
                    type = it
                    serviceId = repo.services.firstOrNull { s -> s.type == it && s.id == serviceId }?.id
                    programId = null
                    jump = GuideJump(null)
                }
            },
            onDay = { target ->
                anchor = (target + (anchor - day)).coerceIn(origin, end - 1)
                programId = null
                jump = GuideJump(null)
            },
            onTime = { at, align ->
                anchor = at.coerceIn(origin, end - 1)
                programId = null
                jump = GuideJump(align)
            },
            now = { now.longValue },
        )
        Spacer(Modifier.height(10.dp))
        // 表は番組が無いときも置いたままにし (フォーカスの行き場を無くさない。数字キーで種別を替えて読み込んでいる間も)、真ん中に1行を重ねる
        val message = when {
            columns.isEmpty() && !store.has(type) -> when {
                store.offline -> NOT_CONNECTED
                store.unreadable -> UNREADABLE
                else -> "読み込んでいます…"
            }
            columns.isEmpty() -> NO_CHANNELS
            columns.all { it.programs.isEmpty() } && !store.offline -> NO_PROGRAMS
            else -> null
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            GuideGrid(
                repo = repo,
                columns = columns,
                table = table,
                column = column,
                focused = focused,
                focusedSpot = focusedSpot,
                anchor = anchor,
                jump = jump,
                origin = origin,
                end = end,
                now = now,
                shade = shade,
                modifier = Modifier
                    .focusRequester(grid)
                    .onFocusChanged { gridFocused = it.isFocused }
                    .focusProperties { up = top }
                    .onKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (event.type == KeyEventType.KeyUp) {
                            if (native.keyCode in CENTER_KEYS && !native.isCanceled && focused != null && centerDown[0]) {
                                centerDown[0] = false
                                opened = focused.id
                                return@onKeyEvent true
                            }
                            return@onKeyEvent native.keyCode in CENTER_KEYS
                        }
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (native.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> go(moves.up(spot))
                            KeyEvent.KEYCODE_DPAD_DOWN -> go(moves.down(spot))
                            KeyEvent.KEYCODE_DPAD_LEFT -> go(moves.left(spot))
                            KeyEvent.KEYCODE_DPAD_RIGHT -> go(moves.right(spot))
                            KeyEvent.KEYCODE_CHANNEL_UP -> go(moves.page(spot, PAGE_COLUMNS))
                            KeyEvent.KEYCODE_CHANNEL_DOWN -> go(moves.page(spot, -PAGE_COLUMNS))
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { jump = GuideJump(null); go(moves.shift(spot, SHIFT_MS)) }
                            KeyEvent.KEYCODE_MEDIA_REWIND -> { jump = GuideJump(null); go(moves.shift(spot, -SHIFT_MS)) }
                            KeyEvent.KEYCODE_MEDIA_RECORD -> {
                                if (native.repeatCount == 0) recordKey()
                                true
                            }
                            in CENTER_KEYS -> {
                                if (native.repeatCount == 0) centerDown[0] = true
                                true
                            }
                            else -> liveChannelKey(native.keyCode)?.let { key ->
                                // ライブの数字キーと同じ決め方 (いまの種別を先に、次に放送している局)
                                val pick = remoteKeyService(repo.services.filter { it.type in types }, key, type)
                                    ?.takeIf { s -> s.type != type || columns.any { it.id == s.id } }
                                if (pick != null) {
                                    type = pick.type
                                    serviceId = pick.id
                                    programId = null
                                } else {
                                    columns.indexOfFirst { it.remoteControlKey == key }.takeIf { it >= 0 }?.let { go(moves.column(it, anchor)) }
                                }
                                true
                            } ?: false
                        }
                    }
                    .semantics {
                        // 読み上げは表に1つ。フォーカスした番組を読ませる (表の中は1つのフォーカスなので、替わったら読み直させる)
                        contentDescription = focusedProgram?.let { describe(programOf(columns, it.id)?.first, it) } ?: "番組表"
                        liveRegion = LiveRegionMode.Polite
                    }
                    .focusable(),
            )
            message?.let { Centered(it) }
            // 録画キーで予約した結果 (下の端に数秒)
            notice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleSmall,
                    color = Palette.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = SAFE_VERTICAL)
                        .widthIn(max = NOTICE_WIDTH)
                        .background(Palette.SurfaceRaised, TagShape)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    val detail = opened?.let { programOf(columns, it) }
    // 開いている番組が表から消えた (取得し直して無くなった) ら閉じる
    LaunchedEffect(opened != null && detail == null) { if (opened != null && detail == null) opened = null }
    detail?.let { (service, shown) ->
        GuideDetail(
            repo = repo,
            service = service,
            program = shown,
            now = now.longValue,
            onClose = {
                opened = null
                runCatching { grid.requestFocus() }
            },
            onLive = {
                leave()
                scope.launch {
                    repo.app.settings.setLastService(service.id)
                    onLive()
                }
            },
            onPlay = { id ->
                // 録画の一覧に無ければ (番組表から来たばかりの録画) 読み直してから。それでも無ければ false
                if (repo.recordings.none { it.id == id }) {
                    try {
                        repo.refreshRecordings()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Unauthorized) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
                repo.recordings.any { it.id == id }.also { found ->
                    if (found) {
                        leave()
                        onWatch(id)
                    }
                }
            },
            onUnauthorized = onUnauthorized,
        )
    }
}

private val CENTER_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)

/** 番組 `id` と、それを流している局 (表の列。サブチャンネルの列に伸びた本チャンネルの番組は本チャンネル) */
private fun programOf(columns: List<GuideService>, id: Long): Pair<GuideService, GuideProgram>? =
    columns.firstNotNullOfOrNull { s -> s.programs.firstOrNull { it.id == id }?.let { s to it } }

/** フォーカスしている番組と、それを辿っている列・その列で見えている時間 (サブチャンネルの列では縮めたもの) */
data class GuideFocus(val id: Long, val column: Int, val startAt: Long, val endAt: Long) {
    /** マスがこのフォーカスに入るか (同じ番組で、その列に掛かり、時間がこの中) */
    fun holds(cell: GuideCell): Boolean = cell.program.id == id && cell.covers(column) && cell.startAt >= startAt && cell.endAt <= endAt
}

/**
 * 上の段: 種別 (局のある種別だけ)、日付 (今日から `DAYS` 日。今日・明日のあとは「9(月)」)、時刻の飛び先。いま選んでいる種別と日付は塗る。
 * 横に送れる1列 (収まらない分は、フォーカスを移すと送られて出る)。下キーで表へ
 */
@Composable
private fun TopBar(
    types: List<String>,
    type: String,
    origin: Long,
    day: Long,
    offline: Boolean,
    focus: FocusRequester,
    down: FocusRequester,
    onType: (String) -> Unit,
    onDay: (Long) -> Unit,
    /** 時刻 (`at`) へ表を送る。`align` はその時刻を表の上から高さのどの割合に置くか */
    onTime: (at: Long, align: Float) -> Unit,
    now: () -> Long,
) {
    val days = remember(origin) { (0 until DAYS).map { dayHour(origin, 4 + 24 * it) } }
    Row(Modifier.fillMaxWidth().height(TOP_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            Modifier.weight(1f).focusProperties { this.down = down },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        ) {
            types.forEach { t ->
                item(key = "type:$t") {
                    Chip(typeName(t), selected = t == type, onClick = { onType(t) })
                }
            }
            item(key = "gap1") { Spacer(Modifier.width(4.dp)) }
            days.forEachIndexed { index, d ->
                item(key = "day:$d") {
                    Chip(dayLabel(index, d), selected = d == day, onClick = { onDay(d) }, modifier = if (d == day) Modifier.focusRequester(focus) else Modifier)
                }
            }
            item(key = "gap2") { Spacer(Modifier.width(4.dp)) }
            // 「今」は開いたときと同じ高さ (上から 4 分の 1)、朝・昼・夜・深夜はその時を表の上の端 (局の見出しのすぐ下) に置く (ブラウザの番組表と同じ)
            item(key = "now") { Chip("今", selected = false, onClick = { onTime(now(), NOW_ALIGN) }) }
            JUMPS.forEach { (label, hour) ->
                item(key = "jump:$label") { Chip(label, selected = false, onClick = { onTime(dayHour(day, hour), 0f) }) }
            }
        }
        if (offline) {
            Text(
                NOT_CONNECTED,
                style = MaterialTheme.typography.labelMedium,
                color = Palette.Background,
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp).background(Palette.Reserved, TagShape).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * 上の段のボタン。見せ方はボタン (`DenpaButton`) と同じで、選んでいるものは塗り、フォーカスしたものは白く塗って azure の縁と光。
 * **幅は字に合わせる** (ボタンの最小幅 58dp では、1 字の「今」「朝」も広がって上の段に収まらない)
 */
@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = PillShape
    Surface(
        onClick = onClick,
        modifier = modifier.widthIn(min = CHIP_MIN_WIDTH).height(CHIP_HEIGHT),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Palette.Accent else Color.Transparent,
            contentColor = if (selected) Palette.OnAccent else Palette.Text,
            focusedContainerColor = Color.White,
            focusedContentColor = Palette.Background,
            pressedContainerColor = Palette.Text,
            pressedContentColor = Palette.Background,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.5.dp, if (selected) Palette.Accent else Palette.Line), shape = shape),
            focusedBorder = Focus.border(shape, 2f),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Focus.glow),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * 表。左に時刻の列、上に局の見出しの行 (どちらも貼り付ける)、その間に番組のマス。**作るのは見えているマスだけ** (`LazyLayout` で2次元に並べる)。
 * フォーカスは表全体に1つで、フォーカスしている番組を縁で示す。送るのは見えるところへ (フォーカスした番組の頭が見えるように)
 */
@Composable
private fun GuideGrid(
    repo: Repository,
    columns: List<GuideService>,
    table: GuideLayout,
    column: Int,
    focused: GuideProgram?,
    focusedSpot: State<GuideFocus?>,
    anchor: Long,
    jump: GuideJump,
    origin: Long,
    end: Long,
    now: State<Long>,
    /** 過ぎた所を暗くする位置の時刻 (いまの時刻を滑らかに追う) */
    shade: State<Long>,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val hourPx = with(density) { HOUR_HEIGHT.toPx() }
    val columnPx = with(density) { COLUMN_WIDTH.toPx() }
    val pad = with(density) { EDGE_PAD.toPx() }
    val pxPerMs = hourPx / HOUR_MS
    val contentHeight = (end - origin) * pxPerMs
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val scrollX = remember { Animatable(0f) }
    val scrollY = remember { Animatable((anchor - origin) * pxPerMs) }
    var firstColumn by remember { mutableIntStateOf(0) }
    val lastJump = remember { arrayOfNulls<GuideJump>(1) }
    /** 前に送ったときの基準の時刻の位置 (表の中の px)。`GuideJump.align` が null のとき、画面の上での位置をこれと変えない */
    val lastAnchorY = remember { FloatArray(1) }
    val placed = remember { BooleanArray(1) }
    LaunchedEffect(column, focused?.id, focused?.startAt, anchor, jump, viewport, columns.size) {
        if (viewport.height == 0 || viewport.width == 0) return@LaunchedEffect
        val visible = (viewport.width / columnPx).toInt().coerceAtLeast(1)
        var first = firstColumn
        if (column < first) first = column
        if (column > first + visible - 1) first = column - visible + 1
        first = first.coerceIn(0, (columns.size - visible).coerceAtLeast(0))
        firstColumn = first
        val h = viewport.height.toFloat()
        val y0 = scrollY.targetValue
        val anchorY = (anchor - origin) * pxPerMs
        val fresh = jump !== lastJump[0]
        lastJump[0] = jump
        val keepFrom = lastAnchorY[0]
        lastAnchorY[0] = anchorY
        val target = if (fresh || focused == null) {
            val align = if (fresh) jump.align else null
            if (align != null) anchorY - h * align else y0 + anchorY - keepFrom
        } else {
            val top = (focused.startAt - origin) * pxPerMs
            val bottom = (focused.endAt - origin) * pxPerMs
            if (bottom - top <= h - pad * 2) {
                when {
                    top < y0 + pad -> top - pad
                    bottom > y0 + h - pad -> bottom - h + pad
                    else -> y0
                }
            } else {
                // 画面より長い番組: 基準の時刻が見えるように。番組の外までは送らない
                val inView = anchorY in (y0 + pad)..(y0 + h - pad) && top <= y0 + h - pad
                (if (inView) y0 else anchorY - h * NOW_ALIGN).coerceIn(top - pad, bottom - h + pad)
            }
        }.coerceIn(0f, (contentHeight - h).coerceAtLeast(0f))
        val x = first * columnPx
        if (!placed[0]) {
            placed[0] = true
            scrollX.snapTo(x)
            scrollY.snapTo(target)
            return@LaunchedEffect
        }
        launch { scrollX.animateTo(x, tween(SCROLL_MS)) }
        scrollY.animateTo(target, tween(SCROLL_MS))
    }
    val x = { scrollX.value }
    val y = { scrollY.value }
    Box(modifier.fillMaxSize()) {
        // 時刻の列 (左に貼り付ける)
        TimeColumn(origin, end, pxPerMs, now, y, Modifier.padding(top = HEADER_HEIGHT).width(TIME_WIDTH).fillMaxHeight().clipToBounds())
        Box(Modifier.padding(start = TIME_WIDTH).fillMaxSize().clipToBounds()) {
            ServiceHeaders(repo, columns, column, columnPx, x, Modifier.fillMaxWidth().height(HEADER_HEIGHT).clipToBounds())
            Box(Modifier.padding(top = HEADER_HEIGHT).fillMaxSize().clipToBounds().onSizeChanged { viewport = it }) {
                GuideCells(table, columns.size, focusedSpot, shade, origin, pxPerMs, columnPx, x, y)
            }
        }
    }
}

/**
 * 番組のマスを、見えている範囲だけ作って並べる。サブチャンネルの列へ伸びたマスは、その列の数だけ幅を取る。
 * フォーカスしているマスは手前に置き、短いマスは読める高さまで下へ広げて重ねる
 */
@Composable
private fun GuideCells(
    table: GuideLayout,
    columnCount: Int,
    focusedSpot: State<GuideFocus?>,
    shade: State<Long>,
    origin: Long,
    pxPerMs: Float,
    columnPx: Float,
    x: () -> Float,
    y: () -> Float,
) {
    val density = LocalDensity.current
    val gap = with(density) { CELL_GAP.roundToPx() }
    val expanded = with(density) { FOCUSED_MIN_HEIGHT.roundToPx() }
    val margin = with(density) { HOUR_HEIGHT.toPx() }
    val cells = table.cells
    /** 左の端の列ごとのマスの番号 (受け持つ時刻の順)。いちばん広いマスの列の数 */
    val byColumn = remember(table, columnCount) {
        val lists = List(columnCount) { ArrayList<Int>() }
        cells.forEachIndexed { i, cell -> lists.getOrNull(cell.column)?.add(i) }
        lists
    }
    val widest = remember(table) { cells.maxOfOrNull { it.span } ?: 1 }
    val linePx = with(density) { NAME_LINE.toPx() }
    val padPx = with(density) { CELL_PAD_TOP.toPx() }
    val provider = remember(table, pxPerMs, gap, expanded, linePx, padPx) {
        object : LazyLayoutItemProvider {
            override val itemCount: Int get() = cells.size

            @Composable
            override fun Item(index: Int, key: Any) {
                val cell = cells[index]
                val isFocused by remember(cell) { derivedStateOf { focusedSpot.value?.holds(cell) == true } }
                // 入る行数は、並べるときと同じ高さ (`cellHeight`) から出す。送っている間も、行数が変わったときだけ組み直す
                val lines by remember(cell, isFocused, expanded, linePx, padPx) {
                    derivedStateOf { cellLines(cellHeight(cell, origin, pxPerMs, y(), gap, isFocused, expanded), padPx, linePx) }
                }
                ProgramCell(cell, isFocused, lines, shade, pxPerMs, gap)
            }

            override fun getKey(index: Int): Any = cells[index].let { "${it.program.id}:${it.column}:${it.startAt}" }

            override fun getContentType(index: Int): Any = CELL
        }
    }
    LazyLayout(itemProvider = { provider }, modifier = Modifier.fillMaxSize()) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val sx = x()
        val sy = y()
        val firstColumn = floor(sx / columnPx).toInt().coerceAtLeast(0)
        val lastColumn = floor((sx + width) / columnPx).toInt().coerceAtMost(columnCount - 1)
        val from = origin + ((sy - margin) / pxPerMs).toLong()
        val to = origin + ((sy + height + margin) / pxPerMs).toLong()
        val focusNow = focusedSpot.value
        class Placed(val placeable: androidx.compose.ui.layout.Placeable, val x: Int, val y: Int, val front: Boolean)
        val placed = ArrayList<Placed>()
        // 左の見えていない列から伸びてくるマスもあるので、いちばん広いマスのぶん左の列から見る
        for (c in (firstColumn - widest + 1).coerceAtLeast(0)..lastColumn) {
            val list = byColumn[c]
            var i = firstEndingAfter(cells, list, from)
            while (i < list.size && cells[list[i]].startAt < to) {
                val cell = cells[list[i]]
                i++
                if (cell.column + cell.span <= firstColumn) continue
                // 上の端で切れるマスは、見えているところに字を寄せる。フォーカスして広げたマスは、下の端からはみ出さないように上げる
                val front = focusNow?.holds(cell) == true
                val natural = cellHeight(cell, origin, pxPerMs, sy, gap, false, expanded)
                val shownHeight = cellHeight(cell, origin, pxPerMs, sy, gap, front, expanded)
                var cellTop = maxOf((cell.startAt - origin) * pxPerMs - sy, 0f).roundToInt()
                if (shownHeight > natural && cellTop + shownHeight > height) cellTop = (height - shownHeight).coerceAtLeast(0)
                if (shownHeight > 0) {
                    val measurables = compose(list[i - 1])
                    val constraint = Constraints.fixed((columnPx * cell.span - gap).roundToInt().coerceAtLeast(1), shownHeight)
                    measurables.forEach { placed += Placed(it.measure(constraint), (c * columnPx - sx).roundToInt(), cellTop, front) }
                }
            }
        }
        layout(width, height) {
            placed.forEach { if (!it.front) it.placeable.place(it.x, it.y) }
            placed.forEach { if (it.front) it.placeable.place(it.x, it.y, zIndex = 1f) }
        }
    }
}

/** `list` (マスの番号。受け持つ時刻の順) の中で、終わりが `at` より後の最初のもの (二分探索) */
private fun firstEndingAfter(cells: List<GuideCell>, list: List<Int>, at: Long): Int {
    var low = 0
    var high = list.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (cells[list[mid]].endAt <= at) low = mid + 1 else high = mid
    }
    return low
}

/**
 * マスの高さ (px)。上の端 (`sy` は表を送った量) で切れるマスは見えているぶんだけ、フォーカスして広げる (`grow`) 短いマスは `expanded` まで。
 * 並べるとき (`GuideCells`) と、入る行数を出すとき (`cellLines`) の両方で使う
 */
private fun cellHeight(cell: GuideCell, origin: Long, pxPerMs: Float, sy: Float, gap: Int, grow: Boolean, expanded: Int): Int {
    val top = (cell.startAt - origin) * pxPerMs - sy
    val bottom = (cell.endAt - origin) * pxPerMs - sy
    val height = (bottom - maxOf(top, 0f)).roundToInt() - gap
    return if (grow && height < expanded) expanded else height
}

/**
 * マスに入る字の行数。**行の途中で切らず、入りきらない行は「…」で止める** (ブラウザの番組表の `lines()`・`.clamp`)。
 * 高さ `height` から上の余白 `pad` を引き、1 行 `line` で割って切り捨てる。1 行も入らないマスでも 1 行は出す
 */
internal fun cellLines(height: Int, pad: Float, line: Float): Int = maxOf(1, floor((height - pad) / line).toInt())

/**
 * 番組の1マス。地はジャンルの色、録画中なら録画中の赤 (ブラウザの denpa の番組表と同じ決まり。`Palette.programCell`)。
 * 中は1つの文で、ジャンルの色の点・始まりの時刻・印 (予約済み・録画中・競合)・番組名 (放送の字、15sp) を続けて書き、次の行から説明。
 * **行の途中で切らない**: 入る行数 (`lines`。`cellLines`) の最後の行を「…」で終える (ブラウザの番組表の `.clamp`。時刻と番組名を同じ行に置くのも同じ)。
 * **短いマス (`SHORT_MS` 未満) は地の色だけ**にし、フォーカスしたら字を出す (呼ぶ側が手前に広げて重ねる)。
 *
 * **放送が過ぎた所は、マスの上からいまの位置まで地を暗くする** (ブラウザの番組表の `.program` の `--past`・`--shade`)。
 * 放送中の番組は過ぎた所を 45%、終わった番組はマス全体を 78% の地の色で覆う。覆うのは地だけで、字はその上に描くので読めたまま。
 * 位置は `shade` (いまの時刻を `Motion.MOVE_MS` かけて追う) で、分が進むと覆いが下へ伸びる。
 * フォーカスした終わった番組は覆わない (テレビでは詳細を開く前にマスで読むため)
 */
@Composable
private fun ProgramCell(cell: GuideCell, focused: Boolean, lines: Int, shade: State<Long>, pxPerMs: Float, gap: Int) {
    val program = cell.program
    val short = cell.endAt - cell.startAt < SHORT_MS
    val recording = program.reservation?.state == ReservationState.RECORDING
    Box(
        Modifier
            .fillMaxSize()
            .clip(CELL_SHAPE)
            .background(Palette.programCell(program.genre, recording, focused))
            .drawBehind {
                val at = shade.value
                if (at <= cell.startAt) return@drawBehind
                if (at >= cell.endAt) {
                    if (!focused) drawRect(ENDED_SHADE)
                    return@drawBehind
                }
                // マスの下の端は番組の終わり (上の端は表の上で切れることがある) なので、下から測る。
                // フォーカスして広げたマスは高さが番組の長さと合わないので、番組の長さに対する割合で
                val natural = (cell.endAt - cell.startAt) * pxPerMs - gap
                val shaded = if (size.height > natural + 1f) {
                    size.height * (at - cell.startAt) / (cell.endAt - cell.startAt)
                } else {
                    size.height + gap - (cell.endAt - at) * pxPerMs
                }
                drawRect(AIRING_SHADE, size = Size(size.width, shaded.coerceIn(0f, size.height)))
            }
            .then(if (focused) Modifier.border(2.dp, Palette.AccentBright, CELL_SHAPE) else Modifier),
    ) {
        if (!short || focused) CellText(program, lines)
    }
}

/**
 * マスの字。ジャンルの点 (ブラウザの番組表の時刻の前の点と同じ)・始まりの時刻・印・番組名を続けて書き、改行して説明。
 * 行の高さはどれも `NAME_LINE` にそろえる (行数を高さから数えるため)。
 * 印: 予約済みは琥珀の「予約済み」、録画中は赤の「●」、競合は赤の「⚠ 競合」。ほかの状態 (録画しない・完了など) は名前を薄く
 */
@Composable
private fun CellText(program: GuideProgram, lines: Int) {
    val density = LocalDensity.current
    val label = MaterialTheme.typography.labelSmall
    val descriptionSize = MaterialTheme.typography.bodySmall.fontSize
    val family = BroadcastFont
    val tint = Palette.genreTint(program.genre)
    val state = program.reservation?.state
    // 地を塗る印 (予約済み・競合) は角を丸めたラベルにするので、字の中に場所を空けて (`Placeholder`) 重ねる。幅は字を測って決める
    val tag = state?.takeIf { it != ReservationState.RECORDING && stateColors(it).first != Color.Transparent }
    val tagWidth = tag?.let {
        val measurer = rememberTextMeasurer(cacheSize = 4)
        remember(it, label, density) {
            with(density) { (measurer.measure(ReservationState.label(it), label, maxLines = 1, softWrap = false).size.width + TAG_SPACE.toPx()).toSp() }
        }
    }
    val text = remember(program, tint, tag, family, label, descriptionSize) {
        buildAnnotatedString {
            if (tint != null) appendInlineContent(DOT_ID, "●")
            withStyle(SpanStyle(fontSize = label.fontSize, color = Palette.TextMuted)) {
                append(TIME.format(Date(program.startAt)))
                when {
                    state == null -> {}
                    state == ReservationState.RECORDING -> withStyle(SpanStyle(color = Palette.Recording)) { append(" ●") }
                    tag != null -> appendInlineContent(TAG_ID, ReservationState.label(tag))
                    else -> withStyle(SpanStyle(color = stateColors(state).second)) { append(" ${ReservationState.label(state)}") }
                }
            }
            append(" ")
            withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = Palette.Text)) { appendBroadcast(program.name) }
            if (program.description.isNotEmpty()) {
                append("\n")
                withStyle(SpanStyle(fontSize = descriptionSize, color = Palette.TextMuted)) { appendBroadcast(program.description) }
            }
        }.let { if (family != null) it.withFont(family) else it }
    }
    val inline = remember(tint, tag, tagWidth, density, label) {
        buildMap {
            if (tint != null) {
                val dot = with(density) { GENRE_DOT.toSp() }
                put(DOT_ID, InlineTextContent(Placeholder(with(density) { (GENRE_DOT + DOT_SPACE).toSp() }, dot, PlaceholderVerticalAlign.TextCenter)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { Box(Modifier.size(GENRE_DOT).background(tint, CircleShape)) }
                })
            }
            if (tag != null && tagWidth != null) {
                val (background, color) = stateColors(tag)
                put(TAG_ID, InlineTextContent(Placeholder(tagWidth, label.lineHeight, PlaceholderVerticalAlign.TextCenter)) {
                    Box(Modifier.fillMaxSize().padding(start = DOT_SPACE).background(background, TagShape), contentAlignment = Alignment.Center) {
                        Text(ReservationState.label(tag), style = label, color = color, maxLines = 1, softWrap = false)
                    }
                })
            }
        }
    }
    Text(
        text,
        modifier = Modifier.padding(start = 6.dp, end = 5.dp, top = CELL_PAD_TOP),
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = NAME_SIZE, lineHeight = NAME_LINE),
        color = Palette.Text,
        overflow = TextOverflow.Ellipsis,
        maxLines = lines,
        inlineContent = inline,
    )
}

/** 予約の状態の色 (地と字)。予約済みは琥珀、競合・録画中は赤 (予約済みと同じ色にしない)。ほかは地を塗らない */
internal fun stateColors(state: String): Pair<Color, Color> = when (state) {
    ReservationState.SCHEDULED -> Palette.Reserved to Palette.Background
    ReservationState.CONFLICT, ReservationState.RECORDING -> Palette.Recording to Color.White
    else -> Color.Transparent to Palette.TextMuted
}

/**
 * 時刻の列。1時間ごとの時を左に寄せ、放送日の始まり (4 時) には日付も添える。**時の数字はその1時間のあいだ列の上の端に残る**
 * (ブラウザの番組表の `.hour-label` と同じ。時の終わりで次の時に押し上げられる)。
 * いまの位置には、分だけを書いたカプセルを重ねる (時は同じ高さの時の数字で読む。読み上げには時まで渡す)
 */
@Composable
private fun TimeColumn(origin: Long, end: Long, pxPerMs: Float, now: State<Long>, y: () -> Float, modifier: Modifier) {
    val density = LocalDensity.current
    val hourPx = with(density) { HOUR_HEIGHT.toPx() }
    val firstHour by remember { derivedStateOf { floor(y() / hourPx).toInt().coerceAtLeast(0) } }
    Box(modifier) {
        val hours = ((end - origin) / HOUR_MS).toInt()
        for (h in firstHour until (firstHour + SHOWN_HOURS).coerceAtMost(hours)) {
            val at = origin + h * HOUR_MS
            Column(
                Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            // 時の頭が上へ出たら上の端に止め、時の終わりが来たら一緒に上へ出ていく
                            val top = h * hourPx - y()
                            placeable.place(0, top.coerceAtLeast(0f).coerceAtMost(top + hourPx - placeable.height).roundToInt())
                        }
                    }
                    .padding(top = 4.dp, start = 4.dp),
            ) {
                Text(HOUR.format(Date(at)), style = MaterialTheme.typography.titleSmall, color = Palette.Text)
                if (h % 24 == 0) Text(SHORT_DAY.format(Date(at)), style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted, maxLines = 1, softWrap = false)
            }
        }
        // いまの時刻のカプセル。高さの真ん中をいまの位置に合わせる
        Text(
            MINUTE.format(Date(now.value)),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Palette.RecordingText,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.place(0, ((now.value - origin) * pxPerMs - y() - placeable.height / 2f).roundToInt())
                    }
                }
                .fillMaxWidth()
                .shadow(4.dp, PillShape)
                .background(Palette.SurfaceRaised, PillShape)
                .semantics { contentDescription = "いま ${TIME.format(Date(now.value))}" },
        )
    }
}

/** 局の見出しの行 (上に貼り付ける)。番号・ロゴ・局名 (放送の字)。フォーカスしている列は azure の字 */
@Composable
private fun ServiceHeaders(repo: Repository, columns: List<GuideService>, focusedColumn: Int, columnPx: Float, x: () -> Float, modifier: Modifier) {
    val density = LocalDensity.current
    val first by remember(columnPx) { derivedStateOf { floor(x() / columnPx).toInt().coerceAtLeast(0) } }
    Box(modifier) {
        for (c in first until (first + SHOWN_COLUMNS).coerceAtMost(columns.size)) {
            val service = columns[c]
            Row(
                Modifier
                    .offset { IntOffset((c * columnPx - x()).roundToInt(), 0) }
                    .width(with(density) { columnPx.toDp() } - CELL_GAP)
                    .fillMaxHeight()
                    .background(Palette.Surface, CELL_SHAPE)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val logo = repo.url(service.logo)
                if (logo != null) RemoteImage(logo, ContentScale.Fit, Modifier.width(32.dp).height(18.dp), repo.token, placeholder = Color.Transparent)
                // 番号 (ライブの局の列と同じ決め方) と局名を2行まで。局名は放送の字
                val number = repo.services.firstOrNull { it.id == service.id }?.number ?: service.remoteControlKey
                Text(
                    buildAnnotatedString {
                        number?.let { append("$it ") }
                        appendBroadcast(service.name)
                    }.withBroadcastFont(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (c == focusedColumn) Palette.AccentBright else Palette.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 番組表の番組の詳細 (決定)。録画・ライブと同じもの (`ProgramDetailDialog`) で、中身は `api/programs/<id>` から取る。
 * ボタンは「予約」(主)・「予約取消」・「視聴」・「再生」のうち出せるもの (`guideActions`) と、右の端に「閉じる」。
 * **録画中の予約取消は2回押し** (1回目で「録画を止めて取消」、4 秒以内にもう一度で取り消す)。押した結果はボタンの下に1行
 */
@Composable
private fun GuideDetail(
    repo: Repository,
    service: GuideService,
    program: GuideProgram,
    now: Long,
    onClose: () -> Unit,
    onLive: () -> Unit,
    /** 録画を観に行く。録画が見つからなければ false */
    onPlay: suspend (Long) -> Boolean,
    onUnauthorized: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val info by produceState<ProgramInfo?>(null, program.id) {
        value = try {
            (repo.api.program(repo.base, program.id) as? ProgramLookup.Found)?.info
        } catch (_: Unauthorized) {
            onUnauthorized()
            null
        }
    }
    var note by remember(program.id) { mutableStateOf<String?>(null) }
    val stop = rememberTwoPress()
    val reservation = program.reservation
    val recording = reservation?.state == ReservationState.RECORDING
    val live = repo.services.firstOrNull { it.id == service.id }
    val shown = guideActions(program, now, airing = live?.airing == true)
    // 主のボタンは1つだけ: 予約があればそれ、無ければ視聴、それも無ければ再生
    val primary = shown.firstOrNull { it == GuideAction.Reserve } ?: shown.firstOrNull { it == GuideAction.Watch } ?: shown.firstOrNull { it == GuideAction.Play }

    fun reserve() = scope.launch {
        note = try {
            reserveProgram(repo, program)
        } catch (_: Unauthorized) {
            return@launch onUnauthorized()
        }
    }

    fun cancel() = scope.launch {
        val rule = reservation?.ruleId
        val result = try {
            repo.api.cancelProgram(repo.base, program.id)
        } catch (_: Unauthorized) {
            return@launch onUnauthorized()
        }
        note = when (result) {
            is CancelResult.Canceled -> {
                // ルールが立てた予約は「録画しない」になって残る (取得し直すと denpa の状態に揃う)
                val after = if (rule != null && reservation.state != ReservationState.SKIPPED) Reservation(ReservationState.SKIPPED, null, rule) else null
                repo.guide.update(program.id) { it.copy(reservation = after) }
                cancelNote(stopped = result.stopped || recording, rule = rule != null && reservation.state != ReservationState.SKIPPED)
            }
            CancelResult.Missing -> {
                repo.guide.update(program.id) { it.copy(reservation = null) }
                "予約がありません"
            }
            is CancelResult.Failed -> "予約を取り消せません: ${result.message}"
        }
    }

    fun play() = scope.launch {
        val id = program.recordingId ?: return@launch
        val found = try {
            onPlay(id)
        } catch (_: Unauthorized) {
            return@launch onUnauthorized()
        }
        if (!found) note = "録画が見つかりません"
    }

    val actions = shown.map { action ->
        when (action) {
            GuideAction.Reserve -> DetailAction("予約", primary = action == primary, initial = action == primary) { reserve() }
            GuideAction.Cancel -> DetailAction(if (recording && stop.armed) "録画を止めて取消" else "予約取消", initial = action == primary) {
                if (!recording || stop.press()) cancel()
            }
            GuideAction.Watch -> DetailAction("視聴", primary = action == primary, initial = action == primary, onClick = onLive)
            GuideAction.Play -> DetailAction("再生", primary = action == primary, initial = action == primary) { play() }
        }
    } + DetailAction(CLOSE, onClick = onClose)
    val conflict = reservation?.takeIf { it.state == ReservationState.CONFLICT }?.let { "録画できません: ${it.reason ?: ReservationState.label(it.state)}" }
    ProgramDetailDialog(
        facts = guideFacts(repo, service, program, info, now),
        actions = actions,
        onDismiss = onClose,
        note = note ?: conflict,
        token = repo.token,
    )
}

/** 番組を予約し、手元のマスも書き換える (詳細の「予約」と、表の録画キー)。結果の1行を返す。401 は `Unauthorized` */
private suspend fun reserveProgram(repo: Repository, program: GuideProgram): String =
    when (val result = repo.api.reserveProgram(repo.base, program.id)) {
        is ReserveResult.Reserved -> {
            repo.guide.update(program.id) { it.copy(reservation = Reservation(result.state, result.reason, null)) }
            reserveNote(result)
        }
        is ReserveResult.Failed -> "予約できません: ${result.message}"
    }

/** 予約した結果の1行。予約できたが録画できない (競合など) ときは理由も */
internal fun reserveNote(result: ReserveResult.Reserved): String = when {
    !result.reserved || result.state == ReservationState.CONFLICT -> "予約しましたが、録画できません: ${result.reason ?: ReservationState.label(result.state)}"
    result.state == ReservationState.RECORDING -> "録画を始めました"
    else -> "予約しました"
}

/** 取り消した結果の1行。録画中を止めたときは、録れたところまでは残ると添える。ルールが立てた予約なら、ルールでも録らないと添える */
internal fun cancelNote(stopped: Boolean, rule: Boolean): String =
    (if (stopped) "録画を止めました。ここまでの録画は残ります" else "予約を取り消しました") + if (rule) "。この放送はルールでも録画しません" else ""

/** 番組表の詳細の中身。`info` (番組表の中身) が取れるまでは番組表のぶんだけ */
private fun guideFacts(repo: Repository, service: GuideService, program: GuideProgram, info: ProgramInfo?, now: Long): DetailFacts {
    val reservation = program.reservation
    val airing = program.startAt <= now && now < program.endAt
    return DetailFacts(
        title = program.name.ifBlank { info?.name.orEmpty() }.ifBlank { service.name },
        meta = programMeta(service.name, program.startAt, program.endAt),
        service = service.name,
        chips = listOfNotNull(RULE.takeIf { reservation?.ruleId != null }) + info?.chips.orEmpty(),
        badge = reservation?.let { ReservationState.label(it.state) },
        badgeColors = reservation?.let { stateColors(it.state).let { (bg, fg) -> if (bg == Color.Transparent) Palette.SurfaceRaised to Palette.Text else bg to fg } },
        progress = if (airing) (now - program.startAt) to (program.endAt - program.startAt) else null,
        progressLabel = if (airing) "あと${((program.endAt - now) + 59_999) / 60_000}分" else null,
        description = info?.description?.ifEmpty { null } ?: program.description,
        extended = info?.extended.orEmpty(),
        logo = repo.url(service.logo),
    )
}

/** 読み上げの文: 局・時刻・番組名・予約の状態 */
private fun describe(service: GuideService?, program: GuideProgram): String =
    listOfNotNull(
        service?.name,
        programMeta(null, program.startAt, program.endAt),
        program.name,
        program.reservation?.let { ReservationState.label(it.state) },
    ).joinToString("、")

private fun typeName(type: String): String = when (type) {
    "GR" -> "地上波"
    else -> type
}

/** 日付のボタンの名前。今日・明日、そのあとは「9(月)」 (上の段に収まるように月は省く) */
private fun dayLabel(index: Int, day: Long): String = when (index) {
    0 -> "今日"
    1 -> "明日"
    else -> DAY_OF_MONTH.format(Date(day))
}

/** 番組表に出す種別 (`api/programs` が受けるもの) */
private val GUIDE_TYPES = listOf("GR", "BS", "CS")

/** 取得し直す通知 */
private val GUIDE_EVENTS = setOf("reservations", "programs")

/** 時刻の飛び先と、放送日の何時か (ブラウザの番組表と同じ) */
private val JUMPS = listOf("朝" to 6, "昼" to 12, "夜" to 19, "深夜" to 23)

private const val NO_CHANNELS = "チャンネルがありません。チャンネルスキャンは denpa の設定でします"
private const val NO_PROGRAMS = "番組表がまだありません。denpa が番組表を取得するとここに出ます"
private const val NOT_CONNECTED = "denpa に繋がっていません"
private const val FORBIDDEN = "denpa に断られました (403)"
private const val UNREADABLE = "denpa の応答を読めません"
private const val RULE = "ルール"

/** 表に出す日数 (今日から) */
private const val DAYS = 7
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS

/** 取得した端からこの時間以内に来たら、隣の 24 時間を先に取得する */
private const val EDGE_MS = 3 * HOUR_MS

/** 早送り・巻き戻しで送る時間と、CH+ / CH- で送る列の数 */
private const val SHIFT_MS = 3 * HOUR_MS
private const val PAGE_COLUMNS = 6

/** この長さより短い番組は色の帯だけ (1 時間 144dp で 24dp。時刻と番組名の1行が入らない) */
private const val SHORT_MS = 10 * 60_000L

/**
 * 1時間の高さ (ブラウザの番組表と同じ割合。5 分のマスが 0.75rem で、1 時間 9rem = 144px)、局の列の幅 (1080p で 6 局が並ぶ)、
 * 時刻の列の幅、局の見出しの高さ、上の段の高さ
 */
private val HOUR_HEIGHT = 144.dp
private val COLUMN_WIDTH = 128.dp
private val TIME_WIDTH = 44.dp
private val HEADER_HEIGHT = 44.dp
private val TOP_HEIGHT = 48.dp
private val CHIP_MIN_WIDTH = 40.dp
private val CHIP_HEIGHT = 34.dp

/** マスの間、フォーカスした短い番組を広げる高さ、フォーカスした番組の上下に空ける余白、マスの形、ジャンルの点の大きさ (ブラウザの 0.4375rem) */
private val CELL_GAP = 2.dp
private val FOCUSED_MIN_HEIGHT = 96.dp
private val EDGE_PAD = 24.dp
private val CELL_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
private val GENRE_DOT = 7.dp

/** 番組名の字 (14sp 以上。離れて読む) と、マスの字の 1 行の高さ (時刻・説明の行も同じ)、マスの上の余白 (1 行の高さと合わせて 10 分のマスに 1 行が入る) */
private val NAME_SIZE = 15.sp
private val NAME_LINE = 20.sp
private val CELL_PAD_TOP = 2.dp

/** マスの字の中の点と印 (`Placeholder`)。点の後ろと印の前の空き、印のラベルの左右の余白を足した幅 */
private const val DOT_ID = "dot"
private const val TAG_ID = "tag"
private val DOT_SPACE = 4.dp
private val TAG_SPACE = 12.dp

/** 過ぎた所に重ねる地の色。放送中の番組は浅く (終わった番組と同じ濃さだと、いま観られる番組まで終わって見える) */
private val AIRING_SHADE = Palette.Background.copy(alpha = 0.45f)
private val ENDED_SHADE = Palette.Background.copy(alpha = 0.78f)

/**
 * 表を送り直す合図。`align` は基準の時刻を表の上から高さのどの割合に置くか (0 は上の端)。
 * null は画面の上での位置を前のまま変えない (種別・日付を替えたとき・早送り。ブラウザの番組表で種別や日を選び直しても縦の位置が残るのと同じ)。
 * 同じ値でも新しく作ったものは別の合図 (`!==` で比べる)
 */
private class GuideJump(val align: Float?)

/**
 * 開いたときと「今」で、いまの時刻を表の上から高さの 4 分の 1 に置く (ブラウザの番組表の `nowTop`)。
 * 上の端ちょうどに置くと、直前の番組が見えず、放送中の番組の始まりが切れる
 */
private const val NOW_ALIGN = 0.25f
private const val SCROLL_MS = 250

/** 時刻の列と見出しで、作る数 (見えるぶん + 1 より多め。1080p で時は 3 つほど、局は 6 つ見える) */
private const val SHOWN_HOURS = 7
private const val SHOWN_COLUMNS = 8

private const val CELL = "cell"
private const val MINUTE_MS = 60_000L
private const val EVENT_SETTLE_MS = 1_000L
private const val FOCUS_TRIES = 20
private const val FOCUS_WAIT_MS = 50L

/** 録画キーで予約した結果を出しておく長さと、その1行の幅の上限 */
private const val NOTICE_MS = 4_000L
private val NOTICE_WIDTH = 640.dp

private val TIME = SimpleDateFormat("HH:mm", Locale.JAPAN)
private val MINUTE = SimpleDateFormat("mm", Locale.JAPAN)
private val HOUR = SimpleDateFormat("H", Locale.JAPAN)
private val SHORT_DAY = SimpleDateFormat("M/d(E)", Locale.JAPAN)
private val DAY_OF_MONTH = SimpleDateFormat("d(E)", Locale.JAPAN)
