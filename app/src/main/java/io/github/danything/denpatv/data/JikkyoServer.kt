package io.github.danything.denpatv.data

import java.io.IOException
import java.net.URI
import java.net.URLEncoder

/**
 * **denpa のサーバの実況の受け口** (`POST api/jikkyo/feed`・`GET api/jikkyo/kakolog`。denpa の docs/api.md の「実況のコメント」)。
 * コメントは denpa のサーバが denpa-hub から受けたもの (連携した世帯のライブでは、公式のニコニコ実況のコメントも)。
 * **テレビは外 (NX-Jikkyo・過去ログ API・ニコニコ) へ繋がない。**
 *
 * どれも IO の上で呼ぶ。届かなければ IOException、401 は `Unauthorized`。単体テストでは偽物と差し替える
 */
interface JikkyoServer {
    /** `api/jikkyo/feed` に `json` を POST する。`abort` が呼ばれたら繋ぎを切る (IOException になる) */
    fun feed(json: String, abort: Abort): Http.Response

    /** `api/jikkyo/kakolog?channel=&from=&to=` を GET する (unix ms) */
    fun kakolog(channel: String, from: Long, to: Long): Http.Response
}

/** 返事を待っている HTTP を切る (局を替えた・画面を閉じた)。切ったあとに渡された繋ぎも、すぐ切る */
class Abort {
    private var close: (() -> Unit)? = null
    private var aborted = false

    /** いまの繋ぎを切るもの */
    @Synchronized
    fun on(close: () -> Unit) {
        if (aborted) close() else this.close = close
    }

    @Synchronized
    fun abort() {
        aborted = true
        close?.let { runCatching { it() } }
        close = null
    }
}

/** 繋いでいる denpa の受け口。アクセストークンがあれば `Authorization: Bearer` を付ける (ほかの API と同じ) */
class DenpaJikkyoServer(private val base: URI, private val token: () -> String?) : JikkyoServer {
    override fun feed(json: String, abort: Abort): Http.Response = call("api/jikkyo/feed", "POST", json, abort)

    override fun kakolog(channel: String, from: Long, to: Long): Http.Response =
        call("api/jikkyo/kakolog?channel=${URLEncoder.encode(channel, "UTF-8")}&from=$from&to=$to", "GET", null, null)

    private fun call(path: String, method: String, json: String?, abort: Abort?): Http.Response {
        val url = BaseUrl.resolve(base, path) ?: throw IOException("URL を作れません: $path")
        val res = Http.request(
            url,
            method,
            json,
            token(),
            headers = mapOf("Accept" to "application/json"),
            opened = { connection -> abort?.on { connection.disconnect() } },
        )
        if (res.code == 401) throw Unauthorized(url)
        return res
    }
}

/**
 * 繋いだ denpa に実況の受け口が無い (v2.64.0 より前。`MIN_JIKKYO`)。SvelteKit は無いパスに 404 を返す。
 * 呼ぶ側はコメントを流さずに、操作の列の「実況」に要るバージョンを添える
 */
class JikkyoTooOld : IOException("denpa に実況の受け口がありません")

/** 受け口が無いという返事か (無いパスは 404。メソッドの合わないものは 405) */
internal fun missingEndpoint(code: Int): Boolean = code == 404 || code == 405
