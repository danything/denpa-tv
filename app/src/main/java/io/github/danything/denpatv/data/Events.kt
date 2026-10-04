package io.github.danything.denpatv.data

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.URI

/**
 * denpa の変化の知らせ (`GET api/events`、docs/api.md)。受け取ったら該当の一覧を読み直す。
 * 名前は `recordings` `services` `programs` `tuners` ほか。**知らない名前もそのまま渡す** (使う側が読み捨てる)
 */
sealed interface DenpaEvent {
    /**
     * 繋がった (繋ぎ直したときも)。**denpa は切れていた間の知らせを送り直さない** (Last-Event-ID を見ない) ので、
     * 受けたら読んである一覧を一度読み直す
     */
    data object Opened : DenpaEvent

    data class Changed(val name: String) : DenpaEvent

    /** 焼いている録画の進み (`percent` は 0..1) */
    data class Encode(val recordingId: Long, val percent: Float) : DenpaEvent
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * SSE の1件を読む。`ping` (繋ぎを保つだけ) は null。`encode` の中身が読めなければ `warn` に書いて null (止めない)
 */
fun denpaEvent(event: SseEvent, warn: (String) -> Unit = {}): DenpaEvent? = when (event.name) {
    "ping" -> null
    "encode" -> {
        val body = runCatching { json.decodeFromString(JsonObject.serializer(), event.data) }.getOrNull()
        val id = body?.get("recordingId")?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
        val percent = body?.get("percent")?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
        if (id == null || percent == null) {
            warn("encode の形が違います (denpa の版が違う?): ${event.data.take(200)}")
            null
        } else {
            // denpa は 0..1 で送る。docs/api.md の例は 42.5 (百分率) なので、1 を超えたら百分率と読む
            DenpaEvent.Encode(id, (if (percent > 1) percent / 100 else percent).toFloat().coerceIn(0f, 1f))
        }
    }
    else -> DenpaEvent.Changed(event.name)
}

/**
 * `api/events` に繋ぎっぱなしにする。切れたら (60 秒何も届かないのも) 待って繋ぎ直す。待ちは 1 秒から倍々で 30 秒まで、
 * 何か届いたら 1 秒に戻す。**401 (トークンが効かない) なら Unauthorized で戻る** (繋ぎ直さない。呼ぶ側が繋ぐ画面へ戻す)。
 * 呼ぶ側のコルーチンを止めるまで戻らない
 */
suspend fun followEvents(
    url: URI,
    token: String?,
    onEvent: (DenpaEvent) -> Unit,
    warn: (String) -> Unit = {},
    wait: suspend (Long) -> Unit = { delay(it) },
): Nothing {
    var backoff = FIRST_BACKOFF_MS
    while (true) {
        try {
            Sse.listen(url, token, onOpen = { onEvent(DenpaEvent.Opened) }) { event ->
                backoff = FIRST_BACKOFF_MS
                denpaEvent(event, warn)?.let(onEvent)
            }
        } catch (e: IOException) {
            if (e is Unauthorized) throw e
            warn("知らせが切れました。${backoff / 1000} 秒後に繋ぎ直します: ${e.message}")
        }
        wait(backoff)
        backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
    }
}

private const val FIRST_BACKOFF_MS = 1_000L
private const val MAX_BACKOFF_MS = 30_000L
