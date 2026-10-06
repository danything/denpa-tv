package io.github.danything.denpatv.data

import kotlinx.serialization.Serializable

/**
 * denpa の `GET /api/services` の1件 (denpa の docs/api.md)。URL は denpa の根からの相対 (`api/…`)
 */
@Serializable
data class Service(
    val id: Long,
    val type: String,
    val name: String,
    val remoteControlKey: Int? = null,
    val logo: String? = null,
    val live: String,
    /** いま放送中の番組。denpa 1.30.0 から。古い denpa には無いので null として扱う */
    val now: NowProgram? = null,
)

/**
 * テレビに出ている番号。地上波はリモコン番号、BS/CS は3桁の番号 (BS朝日1 = 151)。
 * denpa の画面と同じ決め方 (`format.ts` の `channelNumber`)。`id` は `ネットワーク × 100000 + サービス ID`
 */
val Service.number: Int?
    get() = remoteControlKey ?: if (type == "GR") null else (id % 100_000).toInt()

/**
 * いま放送しているか。**ブラウザの denpa (`airing`) と同じく、名前のある番組をいま放送している局だけ。**
 * 本放送と同じものを流しているサブチャンネル (NHK総合2、Eテレ2・3 など) は番組名が空で来る
 */
val Service.airing: Boolean get() = now?.title?.isNotBlank() == true

/** 放送している局だけ。番組表がまだ空などで1つも無ければ、全部 (何も選べなくならないように) */
fun airing(services: List<Service>): List<Service> = services.filter { it.airing }.ifEmpty { services }

/**
 * 隣の局 (`step` は -1 で前、1 で次。端は反対の端へ回る)。**放送していない局 (同じものを流しているサブチャンネル) は飛ばす。**
 * いまの局が放送していなくても、その位置から隣の放送している局へ。いまの局が一覧から消えていたら頭から。
 * 一覧が空 (スキャンし直している最中など) なら null — 呼ぶ側は映しているものを続ける
 */
fun neighbor(services: List<Service>, currentId: Long, step: Int): Service? {
    if (services.isEmpty()) return null
    val candidates = airing(services).map { it.id }.toSet()
    val at = services.indexOfFirst { it.id == currentId }
    if (at < 0) return services.first { it.id in candidates }
    return (1..services.size).asSequence()
        .map { services[Math.floorMod(at + step * it, services.size)] }
        .first { it.id in candidates }
}

/** 種別の並びと名前 (denpa の番組表・ライブと同じ) */
val SERVICE_TYPES = listOf("GR" to "地上波", "BS" to "BS", "CS" to "CS")

/** 局のいま放送中の番組 (`now`)。時刻は UNIX ミリ秒 */
@Serializable
data class NowProgram(
    val title: String,
    val startAt: Long,
    val endAt: Long,
    /** 選べる音声 (デュアルモノの主・副を見分ける・焼くものを選ぶ。`DenpaAudio`)。古い denpa には無いので空 */
    val audios: List<DenpaAudio> = emptyList(),
    /**
     * 録る予定か (予約が入っていて、競合で弾かれていない) と、いま録っている最中か。ライブの「録画」の札の印。
     * denpa が `POST api/services/<id>/record` を持つ版から (古い denpa には無いので false)
     */
    val reserved: Boolean = false,
    val recording: Boolean = false,
) {
    /** 進み具合 (0..1) */
    fun progress(at: Long): Float =
        if (endAt <= startAt) 0f else ((at - startAt).toFloat() / (endAt - startAt)).coerceIn(0f, 1f)

    /** 残り (分。切り上げ) */
    fun remainingMinutes(at: Long): Long = ((endAt - at).coerceAtLeast(0) + 59_999) / 60_000
}

/** `GET /api/recordings` の1件。使う鍵だけ (ほかは読み捨てる) */
@Serializable
data class Recording(
    val id: Long,
    val title: String,
    val serviceName: String? = null,
    val startAt: Long,
    val durationMs: Long? = null,
    val poster: String? = null,
    val files: List<RecordingFile> = emptyList(),
    /** 続きの位置 (ミリ秒)。denpa 1.30.0 から。無い・null なら頭から */
    val resumeMs: Long? = null,
    /** 予定の終わり (UNIX ミリ秒)。追っかけで観た位置を預けるときの尺に使う */
    val endAt: Long? = null,
    /** いま録っている (denpa 1.33.0 から)。`files` は伸びている生TSだけなので、`chase` で観る */
    val recording: Boolean = false,
    /** 追っかけ再生の口 (`api/recordings/<id>/chase`)。生TSがある間だけ。denpa 1.33.0 から */
    val chase: String? = null,
    /** CM 飛ばしを観はじめに入れてよいか (ロゴでの判定に失敗した録画は false)。無ければ入れてよい */
    val cmReliable: Boolean = true,
    /** 選べる音声 (デュアルモノの主・副を見分ける・焼くものを選ぶ。`DenpaAudio`)。古い denpa には無いので空 */
    val audios: List<DenpaAudio> = emptyList(),
)

/** 追っかけで観るか (録画中で、追っかけの口がある) */
val Recording.chasing: Boolean get() = recording && chase != null

/** 観た割合 (0..1)。続きの位置か長さが分からなければ null */
val Recording.watched: Float?
    get() {
        val at = resumeMs ?: return null
        val length = durationMs?.takeIf { it > 0 } ?: return null
        return (at.toFloat() / length).coerceIn(0f, 1f)
    }

/** 札にする形の名前 (AV1 / H.264 / 生TS)。並びは軽いものから */
val Recording.codecLabels: List<String>
    get() = listOf("av1" to "AV1", "h264" to "H.264", "mpeg2" to "生TS")
        .filter { (codec, _) -> files.any { it.codec == codec } }
        .map { it.second }

/** 長さ (30分、1時間30分) */
fun durationLabel(ms: Long): String {
    val minutes = (ms + 30_000) / 60_000
    return if (minutes < 60) "${minutes}分" else "${minutes / 60}時間" + if (minutes % 60 == 0L) "" else "${minutes % 60}分"
}

/**
 * 番組の中身 (`GET api/recordings/<id>/detail`、1.32.0 より後の denpa (danything/denpa#400) から)。説明と、放送の詳細 (見出し → 本文)。
 * 古い denpa には無い (404) ので、呼ぶ側は出さないだけ
 */
@Serializable
data class RecordingDetail(
    val description: String = "",
    val extended: Map<String, String> = emptyMap(),
)

/** 録画の出せるファイル。`source` は encoded / alt / ts、`codec` は av1 / h264 / mpeg2 */
@Serializable
data class RecordingFile(
    val source: String,
    val codec: String,
    val url: String,
)
