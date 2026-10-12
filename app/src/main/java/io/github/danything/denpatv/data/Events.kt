package io.github.danything.denpatv.data

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.URI

/**
 * denpa の変化の通知 (`GET api/events`、denpa の docs/api.md「変化の通知」)。受け取ったら該当の一覧を読み直す。
 * 名前は `recordings` `services` `programs` `tuners` ほか。**知らない名前もそのまま渡す** (使う側が読み捨てる)
 */
sealed interface DenpaEvent {
    /**
     * 繋がった (繋ぎ直したときも)。**denpa は切れていた間の通知を送り直さない** (Last-Event-ID を見ない) ので、
     * 受けたら読んである一覧を一度読み直す
     */
    data object Opened : DenpaEvent

    data class Changed(val name: String) : DenpaEvent

    /** エンコード中の録画の進み (`percent` は 0..1) */
    data class Encode(val recordingId: Long, val percent: Float) : DenpaEvent

    /** 局 `serviceId` のライブが、チューナーを録画 `recording` に譲った (`YieldPlan`) */
    data class Yielded(val serviceId: Long, val recording: YieldedRecording) : DenpaEvent
}

/**
 * 通知 `name` で局の一覧 (`api/services`) が古くなるか。局・番組表のほか、**予約と録画も** — 局の `now` の
 * 録る予定 (`reserved`)・録画中 (`recording`) はライブの「録画」のボタンと局の列の印に出る。古いとみなしても、取り直すのは
 * ライブを開いたときの1回だけ (開いている間は、ライブの画面が通知を受けて自分で取り直す)
 */
fun staleServices(name: String): Boolean = name in SERVICE_EVENTS

private val SERVICE_EVENTS = setOf("services", "programs", "reservations", "recordings")

/** `yielded` の中身 (denpa の docs/api.md) */
@Serializable
private data class YieldedBody(val serviceId: Long, val recording: YieldedRecording)

/**
 * SSE の1件を読む。`ping` (繋ぎを保つだけ) は null。`encode` の中身が読めなければ `warn` に書いて null (止めない)
 */
fun denpaEvent(event: SseEvent, warn: (String) -> Unit = {}): DenpaEvent? = when (event.name) {
    "ping" -> null
    "encode" -> {
        val body = runCatching { lenientJson.decodeFromString(JsonObject.serializer(), event.data) }.getOrNull()
        val id = body?.get("recordingId")?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
        val percent = body?.get("percent")?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
        if (id == null || percent == null) {
            warn("encode の形式が違います (denpa の版が違う?): ${event.data.take(200)}")
            null
        } else {
            // 0..1 (名前に反して百分率ではない。denpa の docs/api.md)
            DenpaEvent.Encode(id, percent.toFloat().coerceIn(0f, 1f))
        }
    }
    "yielded" -> runCatching { lenientJson.decodeFromString(YieldedBody.serializer(), event.data) }.fold(
        onSuccess = { DenpaEvent.Yielded(it.serviceId, it.recording) },
        onFailure = {
            // 読めなくても止めない (譲ったときに追っかけへ切り替わらず、リクエストし直し続けるだけ)
            warn("yielded の形式が違います (denpa の版が違う?): ${event.data.take(200)}")
            null
        },
    )
    else -> DenpaEvent.Changed(event.name)
}

/**
 * `api/events` に繋ぎっぱなしにする。切れたら (60 秒何も届かないのも) 待って繋ぎ直す。待ちは 1 秒から倍々で 30 秒まで、
 * 10 秒以上保った繋ぎの後は 1 秒に戻す (繋いですぐ切られるのを 1 秒おきに繰り返さない)。**401 (アクセストークンが効かない) なら Unauthorized で戻る** (繋ぎ直さない。呼ぶ側が繋ぐ画面へ戻す)。
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
        var openedAt: Long? = null
        try {
            Sse.listen(url, token, onOpen = { openedAt = System.nanoTime(); onEvent(DenpaEvent.Opened) }) { event ->
                denpaEvent(event, warn)?.let(onEvent)
            }
        } catch (e: IOException) {
            if (e is Unauthorized) throw e
            openedAt?.let { if (System.nanoTime() - it >= HELD_NS) backoff = FIRST_BACKOFF_MS }
            warn("通知が切れました。${backoff / 1000} 秒後に繋ぎ直します: ${e.message}")
        }
        wait(backoff)
        backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
    }
}

private const val FIRST_BACKOFF_MS = 1_000L
private const val MAX_BACKOFF_MS = 30_000L
private const val HELD_NS = 10_000_000_000L
