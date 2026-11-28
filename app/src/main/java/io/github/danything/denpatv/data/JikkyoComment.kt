package io.github.danything.denpatv.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * **実況のコメント** (本文と投稿時刻と流し方だけ)。読み方はブラウザの denpa (`src/lib/jikkyo/live.ts`・`command.ts`) と同じ。
 *
 * denpa のサーバの受け口 (`api/jikkyo/feed`・`api/jikkyo/kakolog`) は `{ at, text, mail, src, id }` を返す。
 * 持つのは本文と投稿時刻と、`mail` のうち色・位置・大きさだけ (`src` は読んだその場で使い、`id` は読まない。テレビは書き込まないので、自分の書き込みを見分けない)
 *
 * @property at 投稿時刻 (unix ms。公式のものはニコニコ、ほかは NX-Jikkyo か denpa-hub のサーバの時計)
 * @property style 色・位置・大きさ。既定 (白・流れる・中) なら null
 */
data class JikkyoComment(val at: Long, val text: String, val style: CommentStyle? = null)

/**
 * コメントの流し方 (`mail` のコマンド)。既定 (白・流れる・中) のものは持たない。
 *
 * @property color 字の色 (`#rrggbb`)。白なら null
 * @property place 上 (`ue`) か下 (`shita`) に止める。流れるなら null
 * @property size `big` か `small`。中なら null
 */
data class CommentStyle(val color: String? = null, val place: String? = null, val size: String? = null) {
    val isDefault: Boolean get() = color == null && place == null && size == null
}

/** 色の名前 → 色 (ニコニコのコマンドの色。プレミアム会員の色も含む) */
private val COLOR_VALUES = mapOf(
    "white" to "#ffffff",
    "red" to "#ff0000",
    "pink" to "#ff8080",
    "orange" to "#ffc000",
    "yellow" to "#ffff00",
    "green" to "#00ff00",
    "cyan" to "#00ffff",
    "blue" to "#0000ff",
    "purple" to "#c000ff",
    "black" to "#000000",
    "white2" to "#cccc99",
    "niconicowhite" to "#cccc99",
    "red2" to "#cc0033",
    "truered" to "#cc0033",
    "pink2" to "#ff33cc",
    "orange2" to "#ff6600",
    "passionorange" to "#ff6600",
    "yellow2" to "#999900",
    "madyellow" to "#999900",
    "green2" to "#00cc66",
    "elementalgreen" to "#00cc66",
    "cyan2" to "#00cccc",
    "blue2" to "#3399ff",
    "marineblue" to "#3399ff",
    "purple2" to "#6633cc",
    "nobleviolet" to "#6633cc",
    "black2" to "#666666",
)

private val HEX_COLOR = Regex("^#[0-9a-f]{6}$")
private val SPACES = Regex("\\s+")

/**
 * `mail` (空白で区切ったコマンドの並び。`184 red ue big` など) を読む。色・位置・大きさのほかと、読めないものは無視する。
 * 同じ種類が 2 つあれば後ろのもの。白は字の色を替えない。既定のままなら null
 */
fun readMail(mail: String?): CommentStyle? {
    if (mail == null) return null
    var color: String? = null
    var place: String? = null
    var size: String? = null
    for (command in mail.trim().split(SPACES)) {
        val name = command.lowercase()
        when {
            HEX_COLOR.matches(name) -> color = name
            name in COLOR_VALUES -> color = COLOR_VALUES.getValue(name)
            name == "ue" || name == "shita" -> place = name
            name == "naka" -> place = null
            name == "big" || name == "small" -> size = name
            name == "medium" -> size = null
        }
    }
    if (color == COLOR_VALUES["white"]) color = null
    return CommentStyle(color, place, size).takeUnless { it.isDefault }
}

/**
 * 公式のコメントの `style` (`{ color, place, size }`。denpa のサーバが `mail` を読んだもの) を読む。
 * 読めないところは既定 (白・流れる・中) にする。既定のままなら null
 */
fun readStyle(raw: JsonElement?): CommentStyle? {
    val style = raw as? JsonObject ?: return null
    val color = style["color"].string()?.lowercase()?.takeIf { HEX_COLOR.matches(it) && it != COLOR_VALUES["white"] }
    val place = style["place"].string()?.takeIf { it == "ue" || it == "shita" }
    val size = style["size"].string()?.takeIf { it == "big" || it == "small" }
    return CommentStyle(color, place, size).takeUnless { it.isDefault }
}

/** 受け口のコメントを1つ読んだ結果 */
sealed interface CommentRead {
    /** `src` は出どころ (`hub`・`nx`・`nicolive`)。無いものは `nx` (知らない値も `nx` と同じに扱う) */
    data class Ok(val comment: JikkyoComment, val src: String) : CommentRead

    /** 流さないもの (本文が空・`/nicoad` などの運営のコメント) */
    data object Skip : CommentRead

    /** 形式がずれていて読めない (呼ぶ側がまとめて1回だけ警告する) */
    data object Odd : CommentRead
}

private val OPERATOR = Regex("^/[a-z]")

/** 文字列の値。文字列でなければ null */
internal fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** 数の値。数でない (文字列も) なら null */
internal fun JsonElement?.number(): Double? =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }

/** 受け口のコメント (`{ at, text, mail, src }`) を1つ読む */
fun readComment(raw: JsonElement?): CommentRead {
    val c = raw as? JsonObject ?: return CommentRead.Odd
    val at = c["at"].number() ?: return CommentRead.Odd
    val text = c["text"].string()?.trim() ?: return CommentRead.Odd
    // 運営のコメント (`/nicoad`・`/gift` など。古い過去ログに入っている) は流さない
    if (text.isEmpty() || OPERATOR.containsMatchIn(text)) return CommentRead.Skip
    return CommentRead.Ok(JikkyoComment(at.toLong(), text, readMail(c["mail"].string())), c["src"].string() ?: "nx")
}

/** 受け口の `comments` を読む。流さないものは飛ばし、形式のずれた行の数を返す */
fun readComments(list: JsonArray): Pair<List<CommentRead.Ok>, Int> {
    val comments = mutableListOf<CommentRead.Ok>()
    var odd = 0
    for (raw in list) {
        when (val read = readComment(raw)) {
            is CommentRead.Ok -> comments += read
            CommentRead.Odd -> odd++
            CommentRead.Skip -> Unit
        }
    }
    return comments to odd
}

/** 控えに持つ長さ。前後あわせてこれを超えたら、再生位置から遠いほうから捨てる (長く観てもメモリが増え続けない) */
const val KEEP_SPAN_MS = 2 * 60 * 60_000L

/** 同じ時刻・同じ本文のものを1つにするキー */
fun commentKey(comment: JikkyoComment): String = "${comment.at}\u0000${comment.text}"

/**
 * **時刻つきのコメントの控え。** 観ている局 (録画) ごとに1つ。鍵は呼ぶ側 (`JikkyoFeed`) が掛ける。
 *
 * - コメントは投稿時刻の順に並べる。同じ時刻・同じ本文のものは1つにする (過去ログとライブの受け口の境目で同じものが2度来る)
 * - **埋めた区間** (`[from, to)` の集まり) も持つ。過去ログに何度も同じ区間を訊かないため
 */
class CommentStore {
    private val list = ArrayList<JikkyoComment>()
    private val keys = HashSet<String>()
    private var spans = listOf<Pair<Long, Long>>()

    val size: Int get() = list.size

    /** コメントを足す。`span` を渡すと、その区間を「埋めた」ことにする。前から持っていなかったものを返す */
    fun add(comments: List<JikkyoComment>, span: Pair<Long, Long>? = null): List<JikkyoComment> {
        val added = mutableListOf<JikkyoComment>()
        for (comment in comments) {
            if (!keys.add(commentKey(comment))) continue
            list.add(after(comment.at), comment)
            added += comment
        }
        if (span != null && span.first < span.second) mark(span)
        return added
    }

    /** `from < at ≤ to` のコメント (時刻の順) */
    fun between(from: Long, to: Long): List<JikkyoComment> {
        val lo = after(from)
        val hi = after(to)
        return if (hi <= lo) emptyList() else ArrayList(list.subList(lo, hi))
    }

    /** `from < at ≤ to` のコメントの数 (勢い) */
    fun count(from: Long, to: Long): Int = (after(to) - after(from)).coerceAtLeast(0)

    /** `from < at ≤ to` のうち、`keep` を満たす新しいほうから `limit` 件 (時刻の順で返す) */
    fun latest(from: Long, to: Long, limit: Int, keep: (JikkyoComment) -> Boolean = { true }): List<JikkyoComment> {
        val out = ArrayList<JikkyoComment>()
        val lo = after(from)
        var i = after(to) - 1
        while (i >= lo && out.size < limit) {
            val comment = list[i]
            if (keep(comment)) out += comment
            i--
        }
        out.reverse()
        return out
    }

    /** `[from, to)` のうち、まだ埋めていない区間 */
    fun missing(from: Long, to: Long): List<Pair<Long, Long>> {
        val gaps = mutableListOf<Pair<Long, Long>>()
        var at = from
        for ((start, end) in spans) {
            if (end <= at) continue
            if (start >= to) break
            if (start > at) gaps += at to start
            at = maxOf(at, end)
            if (at >= to) break
        }
        if (at < to) gaps += at to to
        return gaps
    }

    /** 前後あわせて `KEEP_SPAN_MS` に収める。`center` (いまの放送時刻) を含む窓を残し、外のコメントと埋めた区間を捨てる */
    fun trim(center: Long) {
        val first = list.firstOrNull()?.at ?: return
        val last = list.last().at
        if (last - first <= KEEP_SPAN_MS) return
        var lo = center - KEEP_SPAN_MS / 2
        var hi = center + KEEP_SPAN_MS / 2
        if (first > lo) {
            lo = first
            hi = first + KEEP_SPAN_MS
        } else if (last < hi) {
            lo = last - KEEP_SPAN_MS
            hi = last
        }
        val kept = list.filter { it.at in lo..hi }
        list.filter { it.at !in lo..hi }.forEach { keys.remove(commentKey(it)) }
        list.clear()
        list.addAll(kept)
        spans = spans.map { (start, end) -> maxOf(start, lo) to minOf(end, hi) }.filter { (start, end) -> start < end }
    }

    /** `at` より後ろに来る最初の位置 (同じ時刻のものの後ろ) */
    private fun after(at: Long): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].at <= at) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun mark(span: Pair<Long, Long>) {
        val merged = mutableListOf<LongArray>()
        for ((start, end) in (spans + span).sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && start <= last[1]) last[1] = maxOf(last[1], end) else merged += longArrayOf(start, end)
        }
        spans = merged.map { it[0] to it[1] }
    }
}
