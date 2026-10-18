package io.github.danything.denpatv

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import io.github.danything.denpatv.data.BUSY_MESSAGE
import io.github.danything.denpatv.data.Decoders
import io.github.danything.denpatv.data.EmptyWatch
import io.github.danything.denpatv.data.LiveQuality
import io.github.danything.denpatv.data.isBusyAnswer
import io.github.danything.denpatv.data.isMediaContentType
import io.github.danything.denpatv.data.playbackMessage
import io.github.danything.denpatv.data.withBusyAdvice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamErrorsTest {
    private val unset = C.LENGTH_UNSET.toLong()

    @Test
    fun 長さの分からない流れが空で終わったら空とみなす() {
        // denpa のライブ・追っかけが選局・エンコードに失敗して、200 のまま空で閉じた
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
        // 0 バイトの読み (要求した長さが 0) は届いたうちに数えないが、終わりでもない
        watch.opened(unset, 0)
        assertFalse(watch.read(0))
        assertTrue(watch.read(C.RESULT_END_OF_INPUT))
    }

    @Test
    fun 長さの分かるものと途中からリクエストしたものは見ない() {
        val watch = EmptyWatch()
        // 録画のファイル (Content-Length がある)
        watch.opened(0, 0)
        assertFalse(watch.read(C.RESULT_END_OF_INPUT))
        // 尻に飛んだ (途中からリクエストして何も無いのは正しい)
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
        // denpa の映像のエンドポイント (docs/api.md)
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
            "denpa でエラーが起きました (HTTP 503)。denpa が動いているか確かめてください (ERROR_CODE_IO_BAD_HTTP_STATUS)",
            message(badStatus, 503),
        )
        assertEquals("denpa が HTTP 400 を返しました (ERROR_CODE_IO_BAD_HTTP_STATUS)", message(badStatus, 400))
        assertEquals(
            "denpa 以外のサーバーが応答しました (text/html)。接続先の URL を確かめてください (ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE)",
            message(PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, type = "text/html"),
        )
        // 回線 (切れた・途中で終わった・HttpEngine の中の失敗)
        assertEquals(
            "denpa との繋がりが切れました。denpa とネットワークを確かめてください (ERROR_CODE_IO_UNSPECIFIED)",
            message(PlaybackException.ERROR_CODE_IO_UNSPECIFIED),
        )
        assertTrue(message(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED).startsWith("denpa との繋がりが切れました"))
        assertEquals(
            "受け取ったデータを映像として読み取れませんでした (ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)",
            message(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED),
        )
        assertEquals("再生できない形式です (ERROR_CODE_DECODER_INIT_FAILED)", message(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        // 当てはまらないものは番号の名前だけ
        assertEquals("ERROR_CODE_UNSPECIFIED", message(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    /** 変換の枠が埋まって断った 503 は、本文の言い分で見分ける (denpa の入れ替えの最中に前段が返す 503 は別) */
    @Test
    fun 混んでいて断られたのを見分ける() {
        val busy = """{"message":"サーバが混んでいます"}""".toByteArray()
        assertTrue(isBusyAnswer(503, busy))
        // 知らない鍵が足されても読む
        assertTrue(isBusyAnswer(503, """{"message":"サーバが混んでいます","retry":1}""".toByteArray()))
        // 前段の 503 (HTML・文字)・別の言い分・本文が無い
        assertFalse(isBusyAnswer(503, "<html>Service Unavailable</html>".toByteArray()))
        assertFalse(isBusyAnswer(503, "restarting".toByteArray()))
        assertFalse(isBusyAnswer(503, """{"message":"Service Unavailable"}""".toByteArray()))
        assertFalse(isBusyAnswer(503, ByteArray(0)))
        assertFalse(isBusyAnswer(503, null))
        // 503 でなければ言い分が同じでも違う
        assertFalse(isBusyAnswer(500, busy))
        assertFalse(isBusyAnswer(null, busy))
    }

    /** 混んでいたら、変換をリクエストしていて MPEG-2 をハードでデコードできるときだけ MPEG-2 を勧める */
    @Test
    fun 混んでいたら_MPEG_2_を勧める() {
        val tv = Decoders(av1 = true, h264 = true, mpeg2 = true, av1Hardware = true, mpeg2Hardware = true)
        val noMpeg2 = Decoders(av1 = false, h264 = true, mpeg2 = true, mpeg2Hardware = false)
        assertEquals("サーバが混んでいます。画質を MPEG-2 にしてください", withBusyAdvice(BUSY_MESSAGE, LiveQuality.H264, tv))
        assertEquals("サーバが混んでいます。画質を MPEG-2 にしてください", withBusyAdvice(BUSY_MESSAGE, LiveQuality.Av1, tv))
        // MPEG-2 をハードでデコードできない・もう MPEG-2 なら勧めない
        assertEquals(BUSY_MESSAGE, withBusyAdvice(BUSY_MESSAGE, LiveQuality.H264, noMpeg2))
        assertEquals(BUSY_MESSAGE, withBusyAdvice(BUSY_MESSAGE, LiveQuality.Raw, tv))
        // ほかのエラー・エラーが無いはそのまま
        assertEquals("再生できません: x", withBusyAdvice("再生できません: x", LiveQuality.H264, tv))
        assertNull(withBusyAdvice(null, LiveQuality.H264, tv))
    }
}
