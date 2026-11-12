package io.doany.denpatv

import io.doany.denpatv.data.ChatRead
import io.doany.denpatv.data.CommentStore
import io.doany.denpatv.data.CommentStyle
import io.doany.denpatv.data.JikkyoComment
import io.doany.denpatv.data.KEEP_SPAN_MS
import io.doany.denpatv.data.lenientJson
import io.doany.denpatv.data.readChat
import io.doany.denpatv.data.readMail
import io.doany.denpatv.data.readPackets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 実況のコメントのコマンド (色・位置・大きさ)・本文と時刻だけにする・控え。例は denpa の `command.test.ts`・`comments.test.ts` と同じ
 * (コメントは自分で作った偽物)
 */
class JikkyoCommentTest {
    private val t = 1_793_188_800_000L // 2026-10-28 12:00:00 UTC

    private fun json(text: String): JsonElement = lenientJson.parseToJsonElement(text)
    private fun chat(text: String) = readChat(json(text))
    private fun ok(at: Long, text: String, style: CommentStyle? = null) = ChatRead.Ok(JikkyoComment(at, text, style))

    @Test
    fun 色_位置_大きさを読み_184_などほかのコマンドは無視する() {
        assertEquals(CommentStyle("#ff0000", "ue", "big"), readMail("184 red ue big"))
        assertEquals(CommentStyle(place = "shita", size = "small"), readMail("shita small"))
    }

    @Test
    fun 既定と読めないものは持たない() {
        assertNull(readMail("184 white naka medium"))
        assertNull(readMail("184 docomo invisible"))
        assertNull(readMail(""))
        assertNull(readMail(null))
        // 名前に見えても色の表に無いもの
        assertNull(readMail("constructor toString"))
    }

    @Test
    fun プレミアム会員の色の名前と_16_進の色も読む_大文字でもよい() {
        assertEquals("#cccc99", readMail("niconicowhite")?.color)
        assertEquals("#3399ff", readMail("MarineBlue")?.color)
        assertEquals("#1a2b3c", readMail("#1A2b3C")?.color)
        // 16 進でも白なら替えない。桁の違うものは読まない
        assertNull(readMail("#FFFFFF"))
        assertNull(readMail("#fff"))
    }

    @Test
    fun 同じ種類が_2_つあれば後ろのもの() {
        assertEquals(CommentStyle(color = "#0000ff", place = "shita"), readMail("red blue ue shita"))
        assertNull(readMail("ue naka"))
    }

    @Test
    fun WebSocket_の数でも過去ログの文字列でも読む_date_usec_はミリ秒に切り捨てる() {
        assertEquals(ok(t + 123, "わこつ"), chat("""{"date":${t / 1000},"date_usec":123999,"content":"わこつ"}"""))
        assertEquals(ok(t + 5, "888"), chat("""{"date":"${t / 1000}","date_usec":"5000","content":"888"}"""))
    }

    @Test
    fun 本文と時刻と流し方だけを持つ() {
        assertEquals(
            ok(t, "テスト"),
            chat("""{"date":${t / 1000},"content":"テスト","user_id":"1234567","mail":"184","premium":1,"no":10}"""),
        )
    }

    @Test
    fun mail_の色_位置_大きさは流し方として持つ() {
        assertEquals(ok(t, "赤", CommentStyle("#ff0000", "ue", "big")), chat("""{"date":${t / 1000},"content":"赤","mail":"184 red ue big"}"""))
        val (comments, _) = readPackets(json("""[{"chat":{"date":"${t / 1000}","content":"下","mail":"shita"}}]""") as JsonArray)
        assertEquals(listOf(JikkyoComment(t, "下", CommentStyle(place = "shita"))), comments)
    }

    @Test
    fun 本文が空_運営のコメントは流さない() {
        assertEquals(ChatRead.Skip, chat("""{"date":${t / 1000},"content":"  "}"""))
        assertEquals(ChatRead.Skip, chat("""{"date":${t / 1000},"content":"/nicoad {}"}"""))
    }

    @Test
    fun 形式がずれていれば_Odd() {
        assertEquals(ChatRead.Odd, chat("""{"content":"時刻が無い"}"""))
        assertEquals(ChatRead.Odd, chat("""{"date":"x","content":"a"}"""))
        assertEquals(ChatRead.Odd, chat("""{"date":${t / 1000},"content":5}"""))
        assertEquals(ChatRead.Odd, chat("null"))
    }

    @Test
    fun 過去ログの_packet_は_chat_の行だけ読み_ずれた行を数える() {
        val (comments, odd) = readPackets(
            json("""[{"chat":{"date":"${t / 1000}","content":"a"}},{"thread":{}},{"chat":{"content":"b"}}]""") as JsonArray,
        )
        assertEquals(listOf(JikkyoComment(t, "a")), comments)
        assertEquals(1, odd)
    }

    @Test
    fun 時刻の順に並べ_同じ時刻_同じ本文は_1_つにする() {
        val store = CommentStore()
        store.add(listOf(JikkyoComment(t + 2000, "b"), JikkyoComment(t, "a")))
        val added = store.add(listOf(JikkyoComment(t, "a"), JikkyoComment(t + 1000, "c")))
        assertEquals(listOf(JikkyoComment(t + 1000, "c")), added)
        assertEquals(listOf("a", "c", "b"), store.between(t - 1, t + 2000).map { it.text })
        // 前は含まず、後ろは含む
        assertEquals(listOf("c"), store.between(t, t + 1000).map { it.text })
    }

    @Test
    fun 埋めた区間をつなぎ_まだ埋めていないところを返す() {
        val store = CommentStore()
        store.add(emptyList(), 0L to 10L)
        store.add(emptyList(), 20L to 30L)
        assertEquals(listOf(10L to 20L), store.missing(5, 25))
        store.add(emptyList(), 10L to 20L)
        assertEquals(listOf(30L to 40L), store.missing(0, 40))
    }

    @Test
    fun 前後あわせて_2_時間を超えたら_いまから遠いほうを捨てる() {
        val store = CommentStore()
        val hour = 60 * 60_000L
        store.add((0..3).map { JikkyoComment(t + it * hour, it.toString()) }, t to t + 3 * hour)
        store.trim(t + 3 * hour)
        assertEquals(listOf("1", "2", "3"), store.between(t - 1, t + 4 * hour).map { it.text })
        assertEquals(listOf(t to t + 3 * hour - KEEP_SPAN_MS), store.missing(t, t + 3 * hour))
        // 捨てたものは、また来れば足せる
        assertEquals(1, store.add(listOf(JikkyoComment(t, "0"))).size)
    }

    @Test
    fun W_までに書かれたものの新しいほうから件数ぶん_時刻の順で返す() {
        val store = CommentStore()
        store.add((1L..5L).map { JikkyoComment(it, "c$it") })
        assertEquals(listOf(3L, 4L), store.latest(0, 4, 2).map { it.at })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), store.latest(0, 5, 10).map { it.at })
        assertEquals(listOf(3L, 4L, 5L), store.latest(2, 5, 10).map { it.at })
    }

    @Test
    fun 外すもの_NG_ワードを飛ばして件数をそろえる() {
        val store = CommentStore()
        store.add(listOf(JikkyoComment(1, "c1"), JikkyoComment(2, "NG"), JikkyoComment(3, "c3"), JikkyoComment(4, "NG")))
        assertEquals(listOf(1L, 3L), store.latest(0, 10, 2) { it.text != "NG" }.map { it.at })
    }

    @Test
    fun 勢いは_W_までの区間の数() {
        val store = CommentStore()
        store.add((1L..3L).map { JikkyoComment(it, "c$it") })
        assertEquals(2, store.count(1, 3))
        assertEquals(0, store.count(5, 9))
    }
}
