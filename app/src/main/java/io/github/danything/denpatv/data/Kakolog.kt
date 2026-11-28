package io.github.danything.denpatv.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

/**
 * **録画の実況のコメント (過去ログ) を denpa のサーバから取得する** (`GET api/jikkyo/kakolog`。ブラウザの denpa の `kakolog.ts` と同じ)。
 * サーバは denpa-hub に渡し、hub がニコニコ実況 過去ログ API から取得してまとめて持つ。hub に書かれたコメントも同じ返事に入る。
 * テレビは外へ繋がない。
 *
 * - 運営者の負担を考え、細かく何度も取らない。録画は開いたときに1回で全区間を取り、録画中の追っかけは 5 分おきに新しい所だけ取り足す (`JikkyoFeed`)
 * - 1回に 3 日まで (過去ログ API の決まり)。`to` は今で切る
 * - 返事の `complete` が false なら、hub が過去ログ API から取得しきれなかった所がある。取れた分は流し、埋めたことにはしない (60 秒後に取り直す)
 */
object Kakolog {
    /** 過去ログに入るまでの遅れ。5 分おきに集め、集めるのに 3 分ほどかかる (案内ページ) のに余裕を足した */
    const val LAG_MS = 10 * 60_000L
    /** 1回に取ってよい長さ */
    private const val MAX_SPAN_MS = 3 * 24 * 60 * 60_000L

    /** 取った結果。`odd` は形式のずれた行の数、`complete` は hub が取得しきれたか */
    class Result(val comments: List<JikkyoComment>, val odd: Int, val complete: Boolean = true)

    /**
     * `[from, to)` を取る (unix ms)。`to` は `now` で切る。IO の上で呼ぶ。
     * 読めなければ IOException、受け口が無い (古い denpa) なら `JikkyoTooOld`、401 は `Unauthorized`
     */
    fun fetch(server: JikkyoServer, channel: String, from: Long, to: Long, now: Long = System.currentTimeMillis()): Result {
        val end = minOf(to, now)
        val comments = mutableListOf<JikkyoComment>()
        var odd = 0
        var complete = true
        var start = from
        while (start < end) {
            val stop = minOf(start + MAX_SPAN_MS, end)
            val res = server.kakolog(channel, start, stop)
            if (missingEndpoint(res.code)) throw JikkyoTooOld()
            if (!res.ok) throw IOException("過去ログを読めません (HTTP ${res.code})")
            val body = runCatching { lenientJson.parseToJsonElement(res.text()) as? JsonObject }.getOrNull()
                ?: throw IOException("過去ログの返事を読めません")
            val list = body["comments"] as? JsonArray ?: throw IOException("過去ログの返事の形式が違います")
            if ((body["complete"] as? JsonPrimitive)?.takeUnless { it.isString }?.content != "true") complete = false
            val (read, wrong) = readComments(list)
            comments += read.map { it.comment }.filter { it.at in start until stop }
            odd += wrong
            start += MAX_SPAN_MS
        }
        return Result(comments, odd, complete)
    }
}
