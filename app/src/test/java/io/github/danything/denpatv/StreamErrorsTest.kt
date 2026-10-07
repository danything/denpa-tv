package io.github.danything.denpatv

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import io.github.danything.denpatv.data.EmptyWatch
import io.github.danything.denpatv.data.isMediaContentType
import io.github.danything.denpatv.data.playbackMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamErrorsTest {
    private val unset = C.LENGTH_UNSET.toLong()

    @Test
    fun 長さの分からない流れが空で終わったら空とみなす() {
        // denpa のライブ・追っかけが選局・焼くのに失敗して、200 のまま空で閉じた
        val watch = EmptyWatch()
        watch.opened(unset, 0)
        assertTrue(watch.read(C.RESULT_END_OF_INPUT))
    }

    @Test
    fun 何か届いてから終わったのは空ではない() {
        val watch = EmptyWatch()
        watch.opened(unset, 0)
        assertFalse(watch.read(188))
        assertFalse(watch.read(C.RESULT_END_OF_INPUT))
        // 0 バイトの読み (頼んだ長さが 0) は届いたうちに数えないが、終わりでもない
        watch.opened(unset, 0)
        assertFalse(watch.read(0))
        assertTrue(watch.read(C.RESULT_END_OF_INPUT))
    }

    @Test
    fun 長さの分かるものと途中から頼んだものは見ない() {
        val watch = EmptyWatch()
        // 録画のファイル (Content-Length がある)
        watch.opened(0, 0)
        assertFalse(watch.read(C.RESULT_END_OF_INPUT))
        // 尻に飛んだ (途中から頼んで何も無いのは正しい)
        watch.opened(unset, 1_000)
        assertFalse(watch.read(C.RESULT_END_OF_INPUT))
    }

    @Test
    fun 開き直したら数え直す() {
        val watch = EmptyWatch()
        watch.opened(unset, 0)
        watch.read(1_000)
        watch.read(C.RESULT_END_OF_INPUT)
        // Media3 が中で頭から読み直した (ライブ)。今度は空
        watch.opened(unset, 0)
        assertTrue(watch.read(C.RESULT_END_OF_INPUT))
    }

    @Test
    fun 映像の型だけを受け取る() {
        // denpa の映像の口 (docs/api.md)
        assertTrue(isMediaContentType("video/mp4"))
        assertTrue(isMediaContentType("video/mp2t"))
        assertTrue(isMediaContentType("video/x-matroska"))
        assertTrue(isMediaContentType("audio/mp4"))
        assertTrue(isMediaContentType("application/octet-stream"))
        // 書いていない
        assertTrue(isMediaContentType(null))
        assertTrue(isMediaContentType(""))
        // 前段の認証の門のログイン画面・エラーの JSON
        assertFalse(isMediaContentType("text/html; charset=utf-8"))
        assertFalse(isMediaContentType("Text/Plain"))
        assertFalse(isMediaContentType("application/json"))
        assertFalse(isMediaContentType("application/problem+json"))
        assertFalse(isMediaContentType("application/xhtml+xml"))
    }

    @Test
    fun 諦めたときの文() {
        fun message(code: Int, status: Int? = null, type: String? = null) =
            playbackMessage(code, PlaybackException.getErrorCodeName(code), status, type)
        val badStatus = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertEquals(
            "denpa に見つかりません (HTTP 404)。一覧を開き直してください (ERROR_CODE_IO_BAD_HTTP_STATUS)",
            message(badStatus, 404),
        )
        assertEquals(
            "denpa が答えられません (HTTP 503)。denpa が動いているか確かめてください (ERROR_CODE_IO_BAD_HTTP_STATUS)",
            message(badStatus, 503),
        )
        assertEquals("denpa の答えが HTTP 400 (ERROR_CODE_IO_BAD_HTTP_STATUS)", message(badStatus, 400))
        assertEquals(
            "denpa でないものが答えました (text/html)。繋ぐ先の URL を確かめてください (ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE)",
            message(PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, type = "text/html"),
        )
        // 回線 (切れた・途中で終わった・HttpEngine の中の失敗)
        assertEquals(
            "denpa との繋がりが切れました。denpa とネットワークを確かめてください (ERROR_CODE_IO_UNSPECIFIED)",
            message(PlaybackException.ERROR_CODE_IO_UNSPECIFIED),
        )
        assertTrue(message(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED).startsWith("denpa との繋がりが切れました"))
        assertEquals(
            "届いたものを映像として読めませんでした (ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)",
            message(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED),
        )
        assertEquals("この端末では解けませんでした (ERROR_CODE_DECODER_INIT_FAILED)", message(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        // 当てはまらないものは番号の名前だけ
        assertEquals("ERROR_CODE_UNSPECIFIED", message(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }
}
