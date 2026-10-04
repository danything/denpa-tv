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
)

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
)

/** 録画の出せるファイル。`source` は encoded / alt / ts、`codec` は av1 / h264 / mpeg2 */
@Serializable
data class RecordingFile(
    val source: String,
    val codec: String,
    val url: String,
)
