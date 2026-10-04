package io.github.danything.denpatv.data

import kotlinx.serialization.Serializable

/**
 * denpa の `GET /api/services` の1件 (docs/api.md)。URL は denpa の根からの相対 (`api/…`)
 */
@Serializable
data class Service(
    val id: Long,
    val type: String,
    val name: String,
    val remoteControlKey: Int? = null,
    val logo: String? = null,
    val live: String,
    /** いま放送中の番組。denpa#390 を含む版から。古い denpa には無いので null として扱う */
    val now: NowProgram? = null,
)

/** 局のいま放送中の番組 (`now`)。時刻は UNIX ミリ秒 */
@Serializable
data class NowProgram(
    val title: String,
    val startAt: Long,
    val endAt: Long,
) {
    /** 進み具合 (0..1) */
    fun progress(at: Long): Float =
        if (endAt <= startAt) 0f else ((at - startAt).toFloat() / (endAt - startAt)).coerceIn(0f, 1f)

    /** 残り (分。切り上げ) */
    fun remainingMinutes(at: Long): Long = ((endAt - at).coerceAtLeast(0) + 59_999) / 60_000
}

/** `GET /api/recordings` の1件 */
@Serializable
data class Recording(
    val id: Long,
    val title: String,
    val name: String,
    val serviceId: Long? = null,
    val serviceName: String? = null,
    val startAt: Long,
    val endAt: Long,
    val durationMs: Long? = null,
    val poster: String? = null,
    val files: List<RecordingFile> = emptyList(),
    val audio: String? = null,
    /** 続きの位置 (ミリ秒)。denpa#390 を含む版から。無い・null なら頭から */
    val resumeMs: Long? = null,
)

/** 録画の出せるファイル。`source` は encoded / alt / ts、`codec` は av1 / h264 / mpeg2 */
@Serializable
data class RecordingFile(
    val source: String,
    val codec: String,
    val url: String,
)
