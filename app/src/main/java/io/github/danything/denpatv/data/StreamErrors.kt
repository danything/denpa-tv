package io.github.danything.denpatv.data

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * **denpa が 200 を返したのに何も送らずに閉じた。** ライブ・追っかけの口 (`live` / `chase`) は、流しはじめてから
 * 選局や焼き手の立ち上げに失敗すると、200 のまま空で閉じる (チューナーの空きが無い・放送休止・ffmpeg が降りた。
 * denpa の `sessionStream`)。Media3 はこれを「どの読み手も読めない」(`ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`)
 * と言うだけで、空だったのか中身が違ったのかが分からないので、ここで見分けて名前を付ける。
 *
 * `ParserException` にするのは、Media3 に中で読み直させないため (読み直しても同じ。繋ぎ直しは `Recovery` が決める)。
 * 番号は前と同じ `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` になる
 */
@OptIn(UnstableApi::class)
class EmptyStreamException(uri: Uri?) :
    ParserException("denpa が何も送らずに閉じました: $uri", null, false, C.DATA_TYPE_MEDIA)

/**
 * 空の流れの見張り (`EmptyGuardDataSource` の中身)。**長さの分からない流れ (ライブ・追っかけ) を頭から頼んで、
 * 1バイトも来ないうちに終わった**ときだけ空とみなす。長さの分かるもの (録画のファイル) や、途中から頼んだもの
 * (尻に飛んで 0 バイトなのは正しい) は見ない
 */
class EmptyWatch {
    private var watching = false
    private var received = 0L

    /** 開いた。`length` は開いて分かった長さ (分からなければ `C.LENGTH_UNSET`)、`position` は頼んだ位置 */
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

/** 空の流れを `EmptyStreamException` にする口。ほかは `upstream` にそのまま渡す */
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
 * 映像として受け取る Content-Type か。**denpa の映像の口はどれも `video/…` か `audio/…`** (docs/api.md)。
 * 文字 (`text/html` のログイン画面・`application/json` のエラー) が 200 で来たら、前段の何か (認証の門・
 * 別のサーバ) が denpa の代わりに答えている。Media3 に読ませると「形が分からない」になるので、先に断る
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
        httpStatus == 403 -> "denpa に断られました (HTTP 403)"
        httpStatus != null && httpStatus >= 500 -> "denpa が答えられません (HTTP $httpStatus)。denpa が動いているか確かめてください"
        httpStatus != null -> "denpa の答えが HTTP $httpStatus"
        errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
            "denpa でないものが答えました${contentType?.let { " ($it)" } ?: ""}。繋ぐ先の URL を確かめてください"
        errorCode in 2000..2999 || errorCode == PlaybackException.ERROR_CODE_TIMEOUT ->
            "denpa との繋がりが切れました。denpa とネットワークを確かめてください"
        errorCode in 3000..3999 -> "届いたものを映像として読めませんでした"
        errorCode in 4000..4999 -> "この端末では解けませんでした"
        errorCode in 5000..5999 -> "音を出せませんでした"
        else -> null
    }
    return what?.let { "$it ($codeName)" } ?: codeName
}

/** denpa が映像を送らずに閉じた (`EmptyStreamException`・映る前に流れが終わった) ときに出す文 */
const val EMPTY_STREAM_MESSAGE =
    "denpa が映像を送らずに閉じました (チューナーの空きが無い・放送休止・焼けなかったなど)。少し待つか、局か画質を替えてください"
