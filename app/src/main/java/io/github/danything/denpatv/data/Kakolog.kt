package io.github.danything.denpatv.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.URI

/**
 * **ニコニコ実況 過去ログ API から取得する** (テレビから直接。denpa のサーバは通らない。ブラウザの denpa の `kakolog.ts` と同じ)。
 *
 * - 運営者の負担を考え、細かく何度も取らない。録画は開いたときに1回で全区間を取り、10 分より新しい所は 5 分おきに取り足す (`JikkyoFeed`)
 * - 1回に 3 日まで。`endtime` は今より後にできない (誤りになる) ので切る
 * - 誤りも HTTP 200 で `{"error": …}` が返る。`{"packet": []}` は誤りではなく「コメントなし」
 * - **denpa のアクセストークンも Referer も付けない** (よそのサーバに渡すものではない。HttpURLConnection は Referer を付けない)
 */
object Kakolog {
    /** 過去ログに入るまでの遅れ。5 分おきに集め、集めるのに 3 分ほどかかる (案内ページ) のに余裕を足した */
    const val LAG_MS = 10 * 60_000L
    /** 1回に取ってよい長さ */
    private const val MAX_SPAN_MS = 3 * 24 * 60 * 60_000L

    /** 取った結果。`odd` は形式のずれた行の数 */
    class Result(val comments: List<JikkyoComment>, val odd: Int)

    /** `[from, to)` を取る (unix ms)。`to` は `now` で切る。IO の上で呼ぶ。読めなければ IOException */
    fun fetch(endpoints: JikkyoEndpoints, channel: String, from: Long, to: Long, now: Long = System.currentTimeMillis()): Result {
        val end = minOf(to, now)
        val comments = mutableListOf<JikkyoComment>()
        var odd = 0
        var start = from
        while (start < end) {
            val stop = minOf(start + MAX_SPAN_MS, end)
            // 今より後にはできない。秒に切り上げると今を越えることがあるので、今で頭打ち
            val url = URI(endpoints.kakolog(channel, start / 1000, minOf(ceilDiv(stop, 1000), now / 1000)))
            val res = Http.request(url)
            if (!res.ok) throw IOException("過去ログ API が答えません (HTTP ${res.code})")
            val body = runCatching { lenientJson.parseToJsonElement(res.text()) as? JsonObject }.getOrNull()
                ?: throw IOException("過去ログの返事を読めません")
            body["error"]?.let { throw IOException("過去ログ API: $it") }
            val packets = body["packet"] as? JsonArray ?: throw IOException("過去ログの返事の形式が違います")
            val (read, wrong) = readPackets(packets)
            comments += read.filter { it.at in start until stop }
            odd += wrong
            start += MAX_SPAN_MS
        }
        return Result(comments, odd)
    }

    private fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)
}
