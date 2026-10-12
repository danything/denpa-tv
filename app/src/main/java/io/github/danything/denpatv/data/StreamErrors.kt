package io.github.danything.denpatv.data

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * **denpa が 200 を返したのに何も送らずに閉じた。** ライブ・追っかけのエンドポイント (`live` / `chase`) は、流しはじめてから
 * 選局やエンコーダの立ち上げに失敗すると、200 のまま空で閉じる (チューナーの空きが無い・放送休止・ffmpeg が降りた。
 * denpa の `sessionStream`)。Media3 はこれを「どの読み手も読めない」(`ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`)
 * と言うだけで、空だったのか中身が違ったのかが分からないので、ここで見分けて名前を付ける。
 *
 * `ParserException` にするのは、Media3 に中で読み直させないため (読み直しても同じ。繋ぎ直しは `Recovery` が決める)。
 * 番号は `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` になる
 */
@OptIn(UnstableApi::class)
class EmptyStreamException(uri: Uri?) :
    ParserException("denpa が何も送らずに閉じました: $uri", null, false, C.DATA_TYPE_MEDIA)

/**
 * 空の流れの見張り (`EmptyGuardDataSource` の中身)。**長さの分からない流れ (ライブ・追っかけ) を頭からリクエストして、
 * 1バイトも来ないうちに終わった**ときだけ空とみなす。長さの分かるもの (録画のファイル) や、途中からリクエストしたもの
 * (尻に飛んで 0 バイトなのは正しい) は見ない
 */
class EmptyWatch {
    private var watching = false
    private var received = 0L

    /** 開いた。`length` は開いて分かった長さ (分からなければ `C.LENGTH_UNSET`)、`position` はリクエストした位置 */
    fun opened(length: Long, position: Long) {
        watching = length == C.LENGTH_UNSET.toLong() && position == 0L
        received = 0
    }

    /** 読んだ (`result` は `DataSource.read` の答え)。空のまま終わったなら true */
    @OptIn(UnstableApi::class)
    fun read(result: Int): Boolean {
        if (result == C.RESULT_END_OF_INPUT) return watching && received == 0L
        received += result
        return false
    }
}

/** 空の流れを `EmptyStreamException` にする部分。ほかは `upstream` にそのまま渡す */
@OptIn(UnstableApi::class)
class EmptyGuardDataSource(private val upstream: DataSource) : DataSource {
    private val watch = EmptyWatch()

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long = upstream.open(dataSpec).also { watch.opened(it, dataSpec.position) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val result = upstream.read(buffer, offset, length)
        if (watch.read(result)) throw EmptyStreamException(upstream.uri)
        return result
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = EmptyGuardDataSource(upstream.createDataSource())
    }
}

/**
 * 映像として受け取る Content-Type か。**denpa の映像のエンドポイントはどれも `video/…` か `audio/…`** (docs/api.md)。
 * 文字 (`text/html` のログイン画面・`application/json` のエラー) が 200 で来たら、前段の何か (認証の門・
 * 別のサーバ) が denpa の代わりに答えている。Media3 に読ませると「形式が分からない」になるので、先に断る
 * (`ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE`)。書いていなければ通す
 */
fun isMediaContentType(contentType: String?): Boolean {
    val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return !type.startsWith("text/") && "json" !in type && "html" !in type && "xml" !in type
}

/**
 * 諦めたときに出す文 (頭の「再生できません: 」は呼ぶ側が付ける)。**何が起きて、何をすればよいか**を1行で。
 * 終わりに Media3 の番号の名前を残す (報告を受けたときに追えるように)。
 *
 * - `httpStatus` … denpa の答えの番号 (2xx 以外が来たとき)
 * - `contentType` … 映像でない Content-Type で断ったとき、その型
 */
fun playbackMessage(errorCode: Int, codeName: String, httpStatus: Int?, contentType: String?): String {
    val what = when {
        httpStatus == 404 -> "denpa に見つかりません (HTTP 404)。一覧を開き直してください"
        httpStatus == 403 -> "denpa にアクセスを拒否されました (HTTP 403)"
        httpStatus != null && httpStatus >= 500 -> "denpa でエラーが起きました (HTTP $httpStatus)。denpa が動いているか確かめてください"
        httpStatus != null -> "denpa が HTTP $httpStatus を返しました"
        errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
            "denpa 以外のサーバーが応答しました${contentType?.let { " ($it)" } ?: ""}。接続先の URL を確かめてください"
        errorCode in 2000..2999 || errorCode == PlaybackException.ERROR_CODE_TIMEOUT ->
            "denpa との繋がりが切れました。denpa とネットワークを確かめてください"
        errorCode in 3000..3999 -> "受け取ったデータを映像として読み取れませんでした"
        errorCode in 4000..4999 -> "この端末では再生できない形式です"
        errorCode in 5000..5999 -> "音声を再生できませんでした"
        else -> null
    }
    return what?.let { "$it ($codeName)" } ?: codeName
}

/** denpa が映像を送らずに閉じた (`EmptyStreamException`・映る前に流れが終わった) ときに出す文 */
const val EMPTY_STREAM_MESSAGE =
    "denpa が映像を送らずに閉じました (チューナーの空きが無い・放送休止・変換できなかったなど)。少し待つか、局か画質を替えてください"

/** denpa が「その場で変換する本数の枠が埋まっている」と断ったときの言い分 (503 の `message`。denpa の docs/api.md「その場で変換する本数」) */
const val BUSY_MESSAGE = "サーバが混んでいます"

/**
 * denpa が**変換の枠が埋まって断った** 503 か。denpa の入れ替えの最中に前段 (リバースプロキシなど) が返す 503 とは、
 * 本文の言い分 (`{"message":"サーバが混んでいます"}`) で見分ける (ブラウザの denpa の `busyAnswer` と同じ)
 */
fun isBusyAnswer(httpStatus: Int?, body: ByteArray?): Boolean {
    if (httpStatus != 503 || body == null || body.isEmpty()) return false
    val json = runCatching { lenientJson.parseToJsonElement(body.decodeToString()).jsonObject }.getOrNull() ?: return false
    return runCatching { json["message"]?.jsonPrimitive?.contentOrNull }.getOrNull() == BUSY_MESSAGE
}

/**
 * **変換の枠が埋まって断られた 503 は、Media3 の中で読み直さない** (`isBusyAnswer`)。Media3 は 503 を何度か (1・2・3 秒待って) 読み直してから
 * エラーにするが、枠はすぐには空かない。繋ぎ直しは `Recovery` が `Reconnect.Verdict.Busy` として何度かだけ決める。ほかは Media3 の既定のまま
 */
@OptIn(UnstableApi::class)
class BusyLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val http = loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException
        if (http != null && isBusyAnswer(http.responseCode, http.responseBody)) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}

/**
 * 画面に出すエラー。混んでいて断られた (`BUSY_MESSAGE`) なら、変換の要らない MPEG-2 を勧める — いま変換をリクエストしていて (MPEG-2 でない)、
 * この端末が MPEG-2 をハードでデコードできる (`LiveQuality.available` に入る) ときだけ。ほかはそのまま
 */
fun withBusyAdvice(error: String?, quality: LiveQuality, decoders: Decoders): String? =
    if (error == BUSY_MESSAGE && quality != LiveQuality.Raw && LiveQuality.Raw in LiveQuality.available(decoders)) {
        "$BUSY_MESSAGE。画質を MPEG-2 にしてください"
    } else {
        error
    }
