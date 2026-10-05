package io.github.danything.denpatv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI

/** Server-Sent Events の1件。`name` は `event:` (無ければ `message`)、`data` は `data:` の行を改行で繋いだもの */
data class SseEvent(val name: String, val data: String)

/**
 * `text/event-stream` を読む ([HTML の仕様](https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation) の決まり)。
 * 届いた文字を `feed` に流すと、空行で区切れた1件ずつを返す。
 *
 * - 行の終わりは CRLF / LF / CR のどれでもよい (CR と LF が別々に届いても1つと数える)
 * - `:` で始まる行は書き添え (denpa が頭に送る `: connected` など。読み捨てる)。`id:` と `retry:` も使わないので捨てる
 * - `data:` が無いまま空行が来たら何も返さない
 */
class SseParser {
    private val line = StringBuilder()
    private var name = ""
    private val data = StringBuilder()
    private var hasData = false
    /** 直前が CR だった (続く LF は同じ行の終わり) */
    private var afterCr = false
    private var first = true

    fun feed(text: String): List<SseEvent> {
        val out = mutableListOf<SseEvent>()
        for (c in text) {
            if (first) {
                first = false
                // 頭の BOM は読み捨てる
                if (c.code == 0xFEFF) continue
            }
            when {
                c == '\n' && afterCr -> afterCr = false
                c == '\r' || c == '\n' -> {
                    afterCr = c == '\r'
                    endLine()?.let(out::add)
                }
                else -> {
                    afterCr = false
                    line.append(c)
                }
            }
        }
        return out
    }

    private fun endLine(): SseEvent? {
        val text = line.toString()
        line.setLength(0)
        if (text.isEmpty()) return dispatch()
        if (text.startsWith(":")) return null
        val colon = text.indexOf(':')
        val field = if (colon < 0) text else text.substring(0, colon)
        val value = if (colon < 0) "" else text.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> name = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    private fun dispatch(): SseEvent? {
        val event = if (hasData) SseEvent(name.ifEmpty { "message" }, data.toString()) else null
        name = ""
        data.setLength(0)
        hasData = false
        return event
    }
}

object Sse {
    /**
     * 何も届かずにこれだけ経ったら死んだ繋ぎと見なす。denpa は 25 秒おきに `ping` を送る (denpa の docs/api.md)。
     * 読みの時間切れで見るので、番犬は要らない
     */
    const val SILENCE_MS = 60_000

    /**
     * `url` に繋いで、届いた1件ずつを `onEvent` に渡す。切れたら (時間切れも) IOException、401 なら Unauthorized で戻る。
     * 繋がった (200 が返った) ら `onOpen`。**呼ぶ側のコルーチンを止めると、繋ぎを閉じて戻る** (読みの待ちは止められないので、閉じて起こす)
     */
    suspend fun listen(url: URI, token: String?, onOpen: () -> Unit = {}, onEvent: (SseEvent) -> Unit) = coroutineScope {
        val connection = Http.connection(url, token, mapOf("Accept" to "text/event-stream"), SILENCE_MS)
        val closer = launch {
            try {
                awaitCancellation()
            } finally {
                connection.disconnect()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                val code = connection.responseCode
                if (code == 401) throw Unauthorized(url)
                if (code !in 200..299) throw IOException("$code $url")
                onOpen()
                val parser = SseParser()
                val buffer = CharArray(4096)
                connection.inputStream.reader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) throw IOException("切れました $url")
                        parser.feed(String(buffer, 0, read)).forEach(onEvent)
                    }
                }
            }
        } finally {
            closer.cancel()
        }
    }
}
