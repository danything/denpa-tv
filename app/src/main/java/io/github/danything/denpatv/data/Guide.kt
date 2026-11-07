package io.github.danything.denpatv.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Calendar
import java.util.TimeZone

/**
 * 番組表の1回の答え (`GET api/programs?type=&from=&to=`。denpa v2.33.0 から)。1回で最大 24 時間ぶん。
 * `until` は denpa が番組表を持っている終わり (UNIX ミリ秒)。読めなければ null (終わりを決めない)
 */
@Immutable
data class GuidePage(
    val type: String,
    val from: Long,
    val to: Long,
    val until: Long?,
    val services: List<GuideService>,
)

/**
 * 番組表の局 (1列)。番組は始まりの順。
 * サブチャンネルの列 (`mainId` が本チャンネルの id) の番組は、本チャンネルと違う番組 (分割放送) だけ
 */
@Immutable
data class GuideService(
    val id: Long,
    val name: String,
    /** サブチャンネルなら本チャンネルの id。本チャンネルと、denpa が返さない (古い denpa) ときは null */
    val mainId: Long? = null,
    val remoteControlKey: Int? = null,
    val logo: String? = null,
    val programs: List<GuideProgram> = emptyList(),
)

/** 番組表の番組 (1マス)。時刻は UNIX ミリ秒 */
@Immutable
data class GuideProgram(
    val id: Long,
    val name: String,
    val startAt: Long,
    val endAt: Long,
    val description: String = "",
    /** ジャンルの大分類 (ARIB の content_nibble の上 4 ビット)。マスの色。無ければ null */
    val genre: Int? = null,
    /** コピー禁止 (denpa は録画しない。「予約」のボタンを出さない) */
    val copyNever: Boolean = false,
    val reservation: Reservation? = null,
    /** 録れたもの (録画中も)。無ければ null */
    val recordingId: Long? = null,
)

/** 番組の予約。`ruleId` はルールが立てた予約のとき */
@Immutable
data class Reservation(val state: String, val reason: String? = null, val ruleId: Long? = null)

/**
 * 番組表の答えを読む。**番組 1 件が読めなければ、その番組だけ捨てて `warn` に書く** (表ごと読めなくしない)。
 * 局も同じ。全体がオブジェクトでない・`services` が並びでなければ null
 */
fun parseGuide(text: String, warn: (String) -> Unit): GuidePage? {
    val root = runCatching { lenientJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
    val services = root?.get("services") as? JsonArray
    if (root == null || services == null) {
        warn("番組表の答えの形式が違います (denpa のバージョンのずれ?): ${text.take(200)}")
        return null
    }
    return GuidePage(
        type = string(root["type"]) ?: "",
        from = long(root["from"]) ?: 0L,
        to = long(root["to"]) ?: 0L,
        until = long(root["until"]),
        services = services.mapNotNull { parseGuideService(it, warn) },
    )
}

private fun parseGuideService(element: JsonElement, warn: (String) -> Unit): GuideService? {
    val obj = element as? JsonObject
    val id = long(obj?.get("id"))
    if (obj == null || id == null) {
        warn("番組表の局を読めません: ${element.toString().take(200)}")
        return null
    }
    val programs = (obj["programs"] as? JsonArray)?.mapNotNull { parseGuideProgram(it, warn) }.orEmpty().sortedBy { it.startAt }
    return GuideService(
        id = id,
        name = string(obj["name"]) ?: "",
        mainId = long(obj["mainId"])?.takeIf { it != id },
        remoteControlKey = long(obj["remoteControlKey"])?.toInt(),
        logo = string(obj["logo"]),
        programs = programs,
    )
}

private fun parseGuideProgram(element: JsonElement, warn: (String) -> Unit): GuideProgram? {
    val obj = element as? JsonObject
    val id = long(obj?.get("id"))
    val start = long(obj?.get("startAt"))
    val end = long(obj?.get("endAt"))
    if (obj == null || id == null || start == null || end == null || end <= start) {
        warn("番組表の番組を読めないので捨てます: ${element.toString().take(200)}")
        return null
    }
    return GuideProgram(
        id = id,
        name = string(obj["name"]) ?: "",
        startAt = start,
        endAt = end,
        description = string(obj["description"]) ?: "",
        genre = genreOf(obj["genre"]),
        copyNever = boolean(obj["copyNever"]) ?: false,
        reservation = (obj["reservation"] as? JsonObject)?.let { r ->
            string(r["state"])?.let { Reservation(it, string(r["reason"]), long(r["ruleId"])) }
                ?: run { warn("番組 $id の予約の state を読めません: $r"); null }
        },
        recordingId = long(obj["recordingId"]),
    )
}

/** ジャンルの大分類。数・`{lv1}`・その並び (頭の1つ) のどれでも読む */
private fun genreOf(element: JsonElement?): Int? = when (element) {
    null, JsonNull -> null
    is JsonArray -> element.firstOrNull()?.let(::genreOf)
    is JsonObject -> long(element["lv1"])?.toInt()
    is JsonPrimitive -> element.takeUnless { it.isString }?.longOrNull?.toInt()
}

private fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun long(element: JsonElement?): Long? = (element as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

/** 真偽。denpa の DB は 0/1 で持つので、数でも受ける */
private fun boolean(element: JsonElement?): Boolean? =
    (element as? JsonPrimitive)?.takeUnless { it.isString }?.let { it.booleanOrNull ?: it.longOrNull?.let { n -> n != 0L } }

/**
 * 同じ種別の窓 (`pages`。取得した順、後ろほど新しい) を1つの表にする。局の並びはいちばん新しい窓のもの (ほかの窓にしか無い局は後ろへ)。
 * 同じ番組 (id) は新しい窓のもの。**古い窓の番組でも、始まりが新しい窓の時間に入っていれば捨てる** (取得し直して消えた・替わった番組を残さない)。
 * サブチャンネルの列は、どの窓のものでも本チャンネルのすぐ右へ寄せる (分割放送のある日にだけ列が来る)
 */
fun mergeGuide(pages: List<GuidePage>): List<GuideService> {
    val newest = pages.asReversed()
    val order = LinkedHashMap<Long, GuideService>()
    newest.forEach { page -> page.services.forEach { order.putIfAbsent(it.id, it) } }
    return besideMain(order.values.toList()).map { service ->
        val seen = HashSet<Long>()
        val programs = ArrayList<GuideProgram>()
        newest.forEachIndexed { index, page ->
            val newer = newest.subList(0, index)
            page.services.firstOrNull { it.id == service.id }?.programs?.forEach { program ->
                if (program.id in seen) return@forEach
                if (newer.any { program.startAt >= it.from && program.startAt < it.to }) return@forEach
                seen += program.id
                programs += program
            }
        }
        service.copy(programs = programs.sortedBy { it.startAt })
    }
}

/** サブチャンネルの列を、本チャンネルのすぐ右へ寄せる (並びはそのまま)。本チャンネルが無いサブは元の位置のまま */
private fun besideMain(services: List<GuideService>): List<GuideService> {
    val ids = services.mapTo(HashSet()) { it.id }
    val subs = services.filter { it.mainId != null && it.mainId in ids }.groupBy { it.mainId }
    return services.flatMap { service ->
        when {
            service.mainId != null && service.mainId in ids -> emptyList()
            else -> listOf(service) + subs[service.id].orEmpty()
        }
    }
}

/** 表の1マス。`column` から `span` 列ぶん横に伸ばし、`startAt`〜`endAt` を受け持つ (番組の途中で幅が変わると、番組の時間より短くなる) */
@Immutable
data class GuideCell(val program: GuideProgram, val column: Int, val span: Int, val startAt: Long, val endAt: Long) {
    fun covers(c: Int): Boolean = c >= column && c < column + span
}

/**
 * 表の形。`cells` は描くマス (受け持つ時刻の順)、`lanes` は列ごとにフォーカスで辿る番組 (始まりの順)。
 * `lanes` の番組は、その列で見えている時間に縮めたもの (本チャンネルの番組がサブの列で分割放送に切られると、同じ番組が2つに分かれて入る)
 */
@Immutable
data class GuideLayout(val cells: List<GuideCell>, val lanes: List<List<GuideProgram>>)

/**
 * **表のマスを組む** (denpa の Web の番組表の `cellsOf` と同じ形)。サブチャンネルの列は、分割放送の間だけ自分のマスを持ち、
 * ほかの時間は本チャンネルのマスがサブの列まで横に伸びる。分割放送が本チャンネルの番組の途中で始まる・終わるなら、
 * その時刻で本チャンネルのマスを切って、幅を変えたマスを上下に積む。
 * サブの列は本チャンネルのすぐ右にあるもの (`mergeGuide` が寄せる) だけをサブとして扱う
 */
fun guideLayout(columns: List<GuideService>): GuideLayout {
    val cells = ArrayList<GuideCell>()
    var c = 0
    while (c < columns.size) {
        val service = columns[c]
        var end = c + 1
        while (end < columns.size && columns[end].mainId == service.id) end++
        val subs = columns.subList(c + 1, end)
        subs.forEachIndexed { i, sub -> sub.programs.forEach { cells += GuideCell(it, c + 1 + i, 1, it.startAt, it.endAt) } }
        for (program in service.programs) {
            // 番組の中で、分割放送が始まる・終わる時刻で切る
            val edges = sortedSetOf(program.startAt, program.endAt)
            subs.forEach { sub ->
                sub.programs.forEach { p ->
                    if (p.startAt > program.startAt && p.startAt < program.endAt) edges += p.startAt
                    if (p.endAt > program.startAt && p.endAt < program.endAt) edges += p.endAt
                }
            }
            val cuts = edges.toList()
            // 区切りごとに、本チャンネルの番組を出す列のかたまり (左の端と列の数)
            var runs = emptyList<Pair<Int, Int>>()
            var from = cuts.first()
            for (k in 0 until cuts.size - 1) {
                val start = cuts[k]
                val stop = cuts[k + 1]
                val next = mutableListOf(c to 1)
                subs.forEachIndexed { i, sub ->
                    if (sub.programs.any { it.startAt < stop && it.endAt > start }) return@forEachIndexed
                    val (left, span) = next.last()
                    // 1 つ左の列も出しているなら、そのマスを伸ばす
                    if (left + span == c + 1 + i) next[next.lastIndex] = left to span + 1 else next += (c + 1 + i) to 1
                }
                // 幅が変わらなければ切らない
                if (k > 0 && next != runs) {
                    runs.forEach { (left, span) -> cells += GuideCell(program, left, span, from, start) }
                    from = start
                }
                runs = next
            }
            runs.forEach { (left, span) -> cells += GuideCell(program, left, span, from, cuts.last()) }
        }
        c = end
    }
    cells.sortWith(compareBy({ it.startAt }, { it.column }))
    val lanes = List(columns.size) { ArrayList<GuideProgram>() }
    val laneCells = List(columns.size) { ArrayList<GuideCell>() }
    cells.forEach { cell -> for (k in cell.column until cell.column + cell.span) laneCells[k] += cell }
    laneCells.forEachIndexed { k, list ->
        // 同じ番組が続けて並ぶ (幅が変わって切ったマス) なら、フォーカスでは1つにまとめる
        var i = 0
        while (i < list.size) {
            val program = list[i].program
            val start = list[i].startAt
            var stop = list[i].endAt
            while (i + 1 < list.size && list[i + 1].program.id == program.id && list[i + 1].startAt == stop) stop = list[++i].endAt
            lanes[k] += if (start == program.startAt && stop == program.endAt) program else program.copy(startAt = start, endAt = stop)
            i++
        }
    }
    return GuideLayout(cells, lanes)
}

/** 予約の状態 (denpa の `reservations.state`) */
object ReservationState {
    const val SCHEDULED = "scheduled"
    const val CONFLICT = "conflict"
    const val SKIPPED = "skipped"
    const val RECORDING = "recording"
    const val DONE = "done"
    const val FAILED = "failed"
    const val MISSED = "missed"

    /**
     * 画面に出す名前 (denpa の `stateLabel` と同じ呼び方)。競合と録画中は印を付ける。
     * **知らない状態は、名前をそのまま出す** (denpa が増やしたもの)
     */
    fun label(state: String): String = when (state) {
        SCHEDULED -> "予約済み"
        CONFLICT -> "⚠ 競合"
        SKIPPED -> "録画しない"
        RECORDING -> "● 録画中"
        DONE -> "完了"
        FAILED -> "失敗"
        MISSED -> "録り逃し"
        else -> state
    }
}

/** 番組表の詳細で出すボタン (`guideActions`) */
enum class GuideAction { Reserve, Cancel, Watch, Play }

/**
 * 番組表の詳細のボタン。並べる順 (左から)。「閉じる」は呼ぶ側が右の端に足す。
 *
 * - 予約: 予約が無く、まだ終わっていなくて、コピー禁止でない
 * - 予約取消: 予約済み・競合・録画中と、まだ終わっていない「録画しない」
 * - 視聴: いま放送している (`airing` は局がライブに出ているか)
 * - 再生: 録れたものがある
 */
fun guideActions(program: GuideProgram, now: Long, airing: Boolean): List<GuideAction> {
    val state = program.reservation?.state
    val ended = program.endAt <= now
    return buildList {
        if (program.reservation == null && !ended && !program.copyNever) add(GuideAction.Reserve)
        if (state in CANCELABLE || (state == ReservationState.SKIPPED && !ended)) add(GuideAction.Cancel)
        if (airing && program.startAt <= now && now < program.endAt) add(GuideAction.Watch)
        if (program.recordingId != null) add(GuideAction.Play)
    }
}

private val CANCELABLE = setOf(ReservationState.SCHEDULED, ReservationState.CONFLICT, ReservationState.RECORDING)

/** 番組表の1日の始まり (放送日。4:00)。`at` が 0:00〜3:59 なら前の日の 4:00 */
fun broadcastDay(at: Long, zone: TimeZone = TimeZone.getDefault()): Long {
    val calendar = Calendar.getInstance(zone).apply { timeInMillis = at }
    if (calendar.get(Calendar.HOUR_OF_DAY) < DAY_START_HOUR) calendar.add(Calendar.DAY_OF_MONTH, -1)
    calendar.set(Calendar.HOUR_OF_DAY, DAY_START_HOUR)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

/** 放送日 `day` (4:00) の `hour` 時 (4〜27。24 以上は翌日の 0 時から) */
fun dayHour(day: Long, hour: Int, zone: TimeZone = TimeZone.getDefault()): Long {
    val calendar = Calendar.getInstance(zone).apply { timeInMillis = day }
    calendar.add(Calendar.HOUR_OF_DAY, hour - DAY_START_HOUR)
    return calendar.timeInMillis
}

/** 次の放送日 (24 時間後の 4:00。夏時間の無い日本では 24 時間) */
fun nextDay(day: Long, zone: TimeZone = TimeZone.getDefault()): Long = dayHour(day, DAY_START_HOUR + 24, zone)

/** 前の放送日 */
fun previousDay(day: Long, zone: TimeZone = TimeZone.getDefault()): Long = dayHour(day, DAY_START_HOUR - 24, zone)

private const val DAY_START_HOUR = 4

/**
 * 手元に置く番組表 (種別 × 放送日の 24 時間) の決まり。**種別ごとに 2 つまで、合わせて 4 つまで。** 超えたら、使っていない時間の
 * 長いものから捨てる (いま見ている窓 `keep` は捨てない)。並びは使った順 (後ろほど新しい)
 */
class GuideWindows(private val perType: Int = 2, private val total: Int = 4) {
    data class Key(val type: String, val day: Long)

    private val used = ArrayDeque<Key>()

    val keys: List<Key> get() = used.toList()

    operator fun contains(key: Key) = key in used

    /** 使った (取得した・見た)。捨てる鍵を返す */
    fun touch(key: Key, keep: Set<Key> = setOf(key)): List<Key> {
        used.remove(key)
        used.addLast(key)
        val dropped = mutableListOf<Key>()
        fun drop(candidates: List<Key>) {
            val victim = candidates.firstOrNull { it !in keep } ?: return
            used.remove(victim)
            dropped += victim
        }
        while (used.count { it.type == key.type } > perType) {
            val before = used.size
            drop(used.filter { it.type == key.type })
            if (used.size == before) break
        }
        while (used.size > total) {
            val before = used.size
            drop(used.toList())
            if (used.size == before) break
        }
        return dropped
    }

    fun remove(key: Key) {
        used.remove(key)
    }

    fun clear() = used.clear()
}

/** 番組表の上で、フォーカスしているところ。列 (局の並びの何番目)・番組 (その列の何番目。列に番組が無ければ null)・基準の時刻 */
data class GuideSpot(val column: Int, val program: Int?, val anchor: Long)

/** 番組表のキーで動いた先 (`GuideMoves`) */
sealed interface GuideMove {
    data class To(val spot: GuideSpot) : GuideMove
    /** 表の外へ (上は上の段、左は左のメニュー) */
    data object OutUp : GuideMove
    data object OutLeft : GuideMove
    /** まだ取得していない時間へ動こうとした。`at` を含む 24 時間を取得してから、もう一度押してもらう */
    data class Load(val at: Long) : GuideMove
    /** 動かない (右の端・取得できる終わり) */
    data object Stay : GuideMove
}

/**
 * **番組表のフォーカスの動かし方。** 持つのは列・番組・基準の時刻 (`GuideSpot`)。
 *
 * - 上下: 同じ局の前・次の番組。**基準の時刻を、移った先の番組の始まりに替える**
 * - 左右: 隣の局の、基準の時刻に流れている番組。**基準の時刻は替えない** (左右に行き来しても、元の時刻に戻る)
 * - 左の端で左は左のメニュー、最初の番組で上は上の段 (どちらも表の外へ)
 *
 * `columns` は列ごとの番組 (始まりの順)。`loaded` はその時刻を含む 24 時間を取得してあるか、`first`・`last` は表の始まりと終わり
 */
class GuideMoves(
    private val columns: List<List<GuideProgram>>,
    private val first: Long,
    private val last: Long,
    private val loaded: (Long) -> Boolean,
) {
    fun up(spot: GuideSpot): GuideMove {
        val programs = columns.getOrNull(spot.column) ?: return GuideMove.OutUp
        val at = spot.program ?: return GuideMove.OutUp
        if (at > 0) return to(spot.column, at - 1, programs[at - 1].startAt)
        val before = programs[at].startAt - 1
        return if (before >= first && !loaded(before)) GuideMove.Load(before) else GuideMove.OutUp
    }

    fun down(spot: GuideSpot): GuideMove {
        val programs = columns.getOrNull(spot.column) ?: return GuideMove.Stay
        val at = spot.program ?: return GuideMove.Stay
        if (at + 1 < programs.size) return to(spot.column, at + 1, programs[at + 1].startAt)
        val after = programs[at].endAt
        return if (after < last && !loaded(after)) GuideMove.Load(after) else GuideMove.Stay
    }

    fun left(spot: GuideSpot): GuideMove = if (spot.column <= 0) GuideMove.OutLeft else column(spot.column - 1, spot.anchor)

    fun right(spot: GuideSpot): GuideMove = if (spot.column >= columns.size - 1) GuideMove.Stay else column(spot.column + 1, spot.anchor)

    /** `step` 列ずつ送る (CH+ / CH-)。端で止まる */
    fun page(spot: GuideSpot, step: Int): GuideMove {
        val target = (spot.column + step).coerceIn(0, (columns.size - 1).coerceAtLeast(0))
        return if (target == spot.column) GuideMove.Stay else column(target, spot.anchor)
    }

    /** 基準の時刻を `ms` だけ送る (早送り・巻き戻し)。表の始まりと終わりで止まる */
    fun shift(spot: GuideSpot, ms: Long): GuideMove {
        val anchor = (spot.anchor + ms).coerceIn(first, last - 1)
        return if (anchor == spot.anchor) GuideMove.Stay else column(spot.column, anchor)
    }

    /** 列 `column` の、時刻 `anchor` に流れている番組へ (基準の時刻は `anchor`) */
    fun column(column: Int, anchor: Long): GuideMove =
        if (column !in columns.indices) GuideMove.Stay else GuideMove.To(GuideSpot(column, programAt(columns[column], anchor), anchor))

    private fun to(column: Int, program: Int, anchor: Long) = GuideMove.To(GuideSpot(column, program, anchor.coerceAtLeast(first)))

    companion object {
        /**
         * `anchor` に流れている番組。無ければ (番組の無い時間) その後で最初に始まる番組、それも無ければ最後の番組。列が空なら null
         */
        fun programAt(programs: List<GuideProgram>, anchor: Long): Int? {
            if (programs.isEmpty()) return null
            val airing = programs.indexOfFirst { it.startAt <= anchor && anchor < it.endAt }
            if (airing >= 0) return airing
            val next = programs.indexOfFirst { it.startAt > anchor }
            return if (next >= 0) next else programs.lastIndex
        }
    }
}
