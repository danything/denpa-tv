package io.github.danything.denpatv.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 番組表の番組の中身 (`GET api/programs/<id>`。ライブの詳しく)。denpa の `ProgramDetail` そのままで、鍵は snake_case。
 * 番組表から消えた番組は 404 (呼ぶ側は `now` のぶんだけを出す)
 */
data class ProgramInfo(
    val name: String = "",
    val serviceName: String = "",
    val startAt: Long? = null,
    val endAt: Long? = null,
    val description: String = "",
    /** 放送の詳細 (見出し → 本文)。並びは放送のまま */
    val extended: List<Pair<String, String>> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val audios: List<ProgramAudio> = emptyList(),
    val videoType: String? = null,
    val videoResolution: String? = null,
    val isFree: Boolean = true,
) {
    /** 札 (ジャンル・映像・音声・有料)。ブラウザの denpa の詳細 (`ProgramFacts`) と同じ並び。同じ札は1つにまとめる */
    val chips: List<String>
        get() = (
            genres.map(::genreLabel) +
                videoLabel(videoResolution, videoType) +
                audios.map(::audioLabel) +
                (if (isFree) "" else "有料")
            ).filter { it.isNotEmpty() }.distinct()
}

/** ジャンル (ARIB の content_nibble。大分類・中分類) */
data class Genre(val lv1: Int, val lv2: Int)

/** 番組表の音声 (denpa の `Audio`)。`text` は放送が付けた名前 (「解説ステレオ」) */
data class ProgramAudio(val componentType: Int, val langs: List<String> = emptyList(), val text: String? = null)

/**
 * `api/programs/<id>` の答えを読む。**形がずれていても読めるところは読む** (denpa を新しくした・古いままのずれ)。
 * 型の違う鍵はその鍵だけ捨てて `warn` に言う。オブジェクトでなければ null
 */
fun parseProgramInfo(text: String, warn: (String) -> Unit): ProgramInfo? {
    val root = runCatching { lenientJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
    if (root == null) {
        warn("番組の答えがオブジェクトではありません (denpa の版のずれ?)")
        return null
    }
    val read = Fields(root, warn)
    return ProgramInfo(
        name = read.string("name") ?: "",
        serviceName = read.string("service_name") ?: "",
        startAt = read.long("start_at"),
        endAt = read.long("end_at"),
        description = read.string("description") ?: "",
        extended = read.obj("extended")?.mapNotNull { (heading, body) ->
            (body as? JsonPrimitive)?.takeIf { it.isString }?.let { heading to it.content }
                ?: run { warn("extended.$heading が文字ではありません"); null }
        }.orEmpty(),
        genres = read.array("genre_detail")?.mapNotNull { item ->
            val genre = item as? JsonObject
            val lv1 = genre?.get("lv1")?.let(::number)?.toInt()
            val lv2 = genre?.get("lv2")?.let(::number)?.toInt()
            if (lv1 == null) warn("genre_detail の形が違います: $item")
            lv1?.let { Genre(it, lv2 ?: -1) }
        }.orEmpty(),
        audios = read.array("audios")?.mapNotNull { item ->
            val audio = item as? JsonObject
            val type = audio?.get("componentType")?.let(::number)?.toInt()
            if (type == null) {
                warn("audios の形が違います: $item")
                return@mapNotNull null
            }
            ProgramAudio(
                type,
                (audio["langs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty(),
                (audio["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            )
        }.orEmpty(),
        videoType = read.string("video_type"),
        videoResolution = read.string("video_resolution"),
        isFree = read.boolean("is_free") ?: true,
    )
}

/** 鍵を1つずつ読む。無い・null は黙って null、型が違えば `warn` して null */
private class Fields(private val root: JsonObject, private val warn: (String) -> Unit) {
    private fun field(key: String): JsonElement? = root[key]?.takeUnless { it is JsonNull }

    private fun <T> typed(key: String, pick: (JsonElement) -> T?): T? {
        val value = field(key) ?: return null
        return pick(value) ?: run { warn("番組の $key の形が違います (denpa の版のずれ?): $value"); null }
    }

    fun string(key: String) = typed(key) { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    fun long(key: String) = typed(key, ::number)
    /** 真偽。denpa の DB は 0/1 で持つので、数でも受ける */
    fun boolean(key: String) = typed(key) { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.let { p -> p.booleanOrNull ?: p.longOrNull?.let { n -> n != 0L } } }
    fun obj(key: String) = typed(key) { it as? JsonObject }
    fun array(key: String) = typed(key) { it as? JsonArray }
}

/** 数 (文字の "3" は数とみなさない — 形のずれ) */
private fun number(value: JsonElement): Long? = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

/** 番組の口を引いた結果 (`DenpaApi.program`) */
sealed interface ProgramLookup {
    data class Found(val info: ProgramInfo) : ProgramLookup
    /** 番組表に無い (終わって消えた)・口の無い古い denpa・届かない。`now` のぶんだけを出す */
    data object Missing : ProgramLookup
}

/** ジャンル大分類 (denpa の `arib.ts` の `GENRE_LV1`) */
private val GENRE_LV1 = mapOf(
    0 to "ニュース／報道", 1 to "スポーツ", 2 to "情報／ワイドショー", 3 to "ドラマ", 4 to "音楽", 5 to "バラエティ", 6 to "映画",
    7 to "アニメ／特撮", 8 to "ドキュメンタリー／教養", 9 to "劇場／公演", 10 to "趣味／教育", 11 to "福祉", 14 to "拡張", 15 to "その他",
)

/** ジャンル中分類 (`GENRE_LV2`)。大分類ごとに意味が変わる */
private val GENRE_LV2: Map<Int, Map<Int, String>> = mapOf(
    0 to listOf("定時・総合", "天気", "特集・ドキュメント", "政治・国会", "経済・市況", "海外・国際", "解説", "討論・会談", "報道特番", "ローカル・地域", "交通"),
    1 to listOf("スポーツニュース", "野球", "サッカー", "ゴルフ", "その他の球技", "相撲・格闘技", "オリンピック・国際大会", "マラソン・陸上・水泳", "モータースポーツ", "マリン・ウインタースポーツ", "競馬・公営競技"),
    2 to listOf("芸能・ワイドショー", "ファッション", "暮らし・住まい", "健康・医療", "ショッピング・通販", "グルメ・料理", "イベント", "番組紹介・お知らせ"),
    3 to listOf("国内ドラマ", "海外ドラマ", "時代劇"),
    4 to listOf("国内ロック・ポップス", "海外ロック・ポップス", "クラシック・オペラ", "ジャズ・フュージョン", "歌謡曲・演歌", "ライブ・コンサート", "ランキング・リクエスト", "カラオケ・のど自慢", "民謡・邦楽", "童謡・キッズ", "民族音楽・ワールドミュージック"),
    5 to listOf("クイズ", "ゲーム", "トークバラエティ", "お笑い・コメディ", "音楽バラエティ", "旅バラエティ", "料理バラエティ"),
    6 to listOf("洋画", "邦画", "アニメ"),
    7 to listOf("国内アニメ", "海外アニメ", "特撮"),
    8 to listOf("社会・時事", "歴史・紀行", "自然・動物・環境", "宇宙・科学・医学", "カルチャー・伝統文化", "文学・文芸", "スポーツ", "ドキュメンタリー全般", "インタビュー・討論"),
    9 to listOf("現代劇・新劇", "ミュージカル", "ダンス・バレエ", "落語・演芸", "歌舞伎・古典"),
    10 to listOf("旅・釣り・アウトドア", "園芸・ペット・手芸", "音楽・美術・工芸", "囲碁・将棋", "麻雀・パチンコ", "車・オートバイ", "コンピュータ・TVゲーム", "会話・語学", "幼児・小学生", "中学生・高校生", "大学生・受験", "生涯教育・資格", "教育問題"),
    11 to listOf("高齢者", "障害者", "社会福祉", "ボランティア", "手話", "文字(字幕)", "音声解説"),
).mapValues { (_, names) -> names.withIndex().associate { it.index to it.value } + (15 to "その他") }

/** 「アニメ／特撮 > 国内アニメ」。中分類が引けなければ大分類だけ、大分類も知らなければ空 (denpa の `genreLabel`) */
fun genreLabel(genre: Genre): String {
    val lv1 = GENRE_LV1[genre.lv1] ?: return ""
    val lv2 = GENRE_LV2[genre.lv1]?.get(genre.lv2) ?: return lv1
    return "$lv1 > $lv2"
}

/** 音声の構成 (component_type)。denpa の `AUDIO_TYPE` */
private val AUDIO_TYPE = mapOf(
    1 to "モノラル", 2 to "デュアルモノ", 3 to "ステレオ", 4 to "2/1", 5 to "3/0", 6 to "2/2", 7 to "3/1", 8 to "3/2", 9 to "5.1ch",
    10 to "3/3.1", 11 to "2/0/0-2/0/2-0.1", 12 to "5/2.1", 13 to "3/2/2.1", 14 to "2/0/0-3/0/2-0.1", 15 to "0/2/0-3/0/2-0.1",
    16 to "2/0/0-3/2/3-0.2", 17 to "3/3/3-5/2/3-3/0/0.2",
)

/** 言語 (ISO 639-2 のうち日本の放送で出てくるもの)。denpa の `LANGUAGE` */
private val LANGUAGE = mapOf(
    "jpn" to "日本語", "eng" to "英語", "deu" to "ドイツ語", "fra" to "フランス語", "ita" to "イタリア語", "rus" to "ロシア語",
    "zho" to "中国語", "kor" to "韓国語", "spa" to "スペイン語", "por" to "ポルトガル語", "tha" to "タイ語", "etc" to "その他",
)

/** 「主音声ステレオ (日本語)」「ステレオ (日本語)」。放送が名乗っていればその名前 (denpa の `audioLabel`) */
fun audioLabel(audio: ProgramAudio): String {
    val type = audio.text?.takeIf { it.isNotEmpty() } ?: AUDIO_TYPE[audio.componentType] ?: "種別${audio.componentType}"
    val langs = audio.langs.map { LANGUAGE[it] ?: it }
    return if (langs.isEmpty()) type else "$type (${langs.joinToString("/")})"
}

private val VIDEO_TYPE = mapOf("mpeg2" to "MPEG-2", "h.264" to "H.264", "h.265" to "H.265")

/** 「1080i MPEG-2」。どちらか片方しか無いこともある (denpa の `videoLabel`) */
fun videoLabel(resolution: String?, type: String?): String =
    listOfNotNull(resolution, type?.let { VIDEO_TYPE[it] ?: it }).filter { it.isNotEmpty() }.joinToString(" ")

/**
 * 詳しくの1行目の下に出す「局 ・ 10/6(火) 21:00〜21:54 (54分)」。ブラウザの denpa の詳細と同じ並び。
 * 終わりが分からなければ始まりだけ、局が分からなければ日時から
 */
fun programMeta(serviceName: String?, startAt: Long, endAt: Long?, zone: TimeZone = TimeZone.getDefault()): String {
    fun format(pattern: String, at: Long) = SimpleDateFormat(pattern, Locale.JAPAN).apply { timeZone = zone }.format(Date(at))
    val span = if (endAt != null && endAt > startAt) {
        "${format("M/d(E) HH:mm", startAt)}〜${format("HH:mm", endAt)} (${durationLabel(endAt - startAt)})"
    } else {
        format("M/d(E) HH:mm", startAt)
    }
    return listOfNotNull(serviceName?.takeIf { it.isNotBlank() }, span).joinToString(" ・ ")
}
