package io.github.danything.denpatv

import io.github.danything.denpatv.data.BaseUrl
import io.github.danything.denpatv.data.CancelResult
import io.github.danything.denpatv.data.DenpaApi
import io.github.danything.denpatv.data.GuideAction
import io.github.danything.denpatv.data.GuideLookup
import io.github.danything.denpatv.data.GuideMove
import io.github.danything.denpatv.data.GuideMoves
import io.github.danything.denpatv.data.GuidePage
import io.github.danything.denpatv.data.GuideProgram
import io.github.danything.denpatv.data.GuideService
import io.github.danything.denpatv.data.GuideSpot
import io.github.danything.denpatv.data.GuideWindows
import io.github.danything.denpatv.data.ReserveResult
import io.github.danything.denpatv.data.Reservation
import io.github.danything.denpatv.data.ReservationState
import io.github.danything.denpatv.data.broadcastDay
import io.github.danything.denpatv.data.dayHour
import io.github.danything.denpatv.data.guideActions
import io.github.danything.denpatv.data.guideLayout
import io.github.danything.denpatv.data.mergeGuide
import io.github.danything.denpatv.data.nextDay
import io.github.danything.denpatv.data.parseGuide
import io.github.danything.denpatv.ui.cancelNote
import io.github.danything.denpatv.ui.cellLines
import io.github.danything.denpatv.ui.reserveNote
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class GuideTest {
    private val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
    /** 2026-11-07 (土) 4:00 (日本時間) */
    private val day = 1_793_991_600_000L
    private val hour = 3_600_000L

    private fun program(id: Long, start: Long, end: Long, reservation: Reservation? = null, copyNever: Boolean = false, recordingId: Long? = null) =
        GuideProgram(id, "番組$id", start, end, reservation = reservation, copyNever = copyNever, recordingId = recordingId)

    @Test
    fun 放送日は4時から() {
        assertEquals(day, broadcastDay(day, tokyo))
        assertEquals(day, broadcastDay(day + 23 * hour + 59 * 60_000, tokyo))
        // 翌日の 3:59 までは同じ放送日、4:00 から次の日
        assertEquals(day, broadcastDay(day - 1 + 24 * hour, tokyo))
        assertEquals(day + 24 * hour, broadcastDay(day + 24 * hour, tokyo))
        assertEquals(day + 24 * hour, nextDay(day, tokyo))
        // 24 時は翌日の 0 時
        assertEquals(day + 20 * hour, dayHour(day, 24, tokyo))
        assertEquals(day + 2 * hour, dayHour(day, 6, tokyo))
    }

    /** 番組 1 件が読めないときは、その番組だけ捨てて warn に書く。知らない鍵は読み捨てる */
    @Test
    fun 番組表を読む() {
        val warnings = mutableListOf<String>()
        val page = parseGuide(
            """{"type":"GR","from":$day,"to":${day + 24 * hour},"until":${day + 48 * hour},"services":[
              {"id":3273601024,"name":"ＮＨＫ総合１・東京","remoteControlKey":1,"logo":"api/services/3273601024/logo","new":1,"programs":[
                {"id":2,"name":"後","startAt":${day + hour},"endAt":${day + 2 * hour},"description":"","genre":[{"lv1":7,"lv2":0}],"copyNever":0,"reservation":null,"recordingId":null},
                {"id":1,"name":"先","startAt":$day,"endAt":${day + hour},"description":"説明","genre":3,"copyNever":true,
                 "reservation":{"state":"conflict","reason":"チューナーが足りません","ruleId":5},"recordingId":12},
                {"id":3,"name":"壊れた","startAt":"x","endAt":1},
                {"id":4,"name":"知らない状態","startAt":${day + 2 * hour},"endAt":${day + 3 * hour},"reservation":{"state":"paused"}}
              ]},
              {"name":"id が無い局"}
            ]}""",
        ) { warnings += it }!!
        assertEquals("GR", page.type)
        assertEquals(day + 48 * hour, page.until)
        val service = page.services.single()
        assertEquals(1, service.remoteControlKey)
        assertEquals(listOf(1L, 2L, 4L), service.programs.map { it.id })
        val first = service.programs[0]
        assertEquals(Reservation("conflict", "チューナーが足りません", 5), first.reservation)
        assertEquals(12L, first.recordingId)
        assertTrue(first.copyNever)
        assertEquals(3, first.genre)
        assertEquals(7, service.programs[1].genre)
        assertEquals("paused", service.programs[2].reservation?.state)
        assertEquals("paused", ReservationState.label("paused"))
        assertEquals(2, warnings.size)
        assertNull(parseGuide("[]") {})
    }

    /** 同じ種別の窓をまとめる。同じ番組は新しい窓のもの、古い窓の番組でも新しい窓の時間に始まるものは捨てる */
    @Test
    fun 窓をまとめる() {
        val older = GuidePage("GR", day, day + 24 * hour, null, listOf(GuideService(1, "A", programs = listOf(program(10, day + 23 * hour, day + 25 * hour), program(11, day + 25 * hour, day + 26 * hour)))))
        val newer = GuidePage(
            "GR", day + 24 * hour, day + 48 * hour, null,
            listOf(GuideService(2, "B"), GuideService(1, "A", programs = listOf(program(10, day + 23 * hour, day + 25 * hour, Reservation("scheduled")), program(12, day + 25 * hour, day + 27 * hour)))),
        )
        val merged = mergeGuide(listOf(older, newer))
        assertEquals(listOf(2L, 1L), merged.map { it.id })
        val a = merged[1].programs
        // 11 は新しい窓の時間に始まるのに新しい窓に無い (取得し直して消えた) ので捨てる
        assertEquals(listOf(10L, 12L), a.map { it.id })
        assertEquals("scheduled", a[0].reservation?.state)
    }

    /** サブチャンネルの列 (`mainId`)。古い窓にしか無いサブも、本チャンネルのすぐ右へ寄せる。自分を指す mainId は本チャンネル */
    @Test
    fun サブチャンネルの列() {
        val page = parseGuide(
            """{"type":"GR","from":$day,"to":${day + 24 * hour},"services":[{"id":1,"name":"A","mainId":null},{"id":2,"name":"A2","mainId":1},{"id":3,"name":"B","mainId":3}]}""",
        ) {}!!
        assertEquals(listOf(null, 1L, null), page.services.map { it.mainId })
        val older = GuidePage("GR", day, day + 24 * hour, null, listOf(GuideService(1, "A"), GuideService(2, "A2", mainId = 1), GuideService(3, "B")))
        val newer = GuidePage("GR", day + 24 * hour, day + 48 * hour, null, listOf(GuideService(1, "A"), GuideService(3, "B")))
        assertEquals(listOf(1L, 2L, 3L), mergeGuide(listOf(older, newer)).map { it.id })
        // 本チャンネルが無いサブは元の位置のまま (ふつうの列として描く)
        val orphan = GuidePage("GR", day, day + 24 * hour, null, listOf(GuideService(3, "B"), GuideService(2, "A2", mainId = 1)))
        assertEquals(listOf(3L, 2L), mergeGuide(listOf(orphan)).map { it.id })
    }

    /**
     * マスを組む (denpa の `cellsOf` と同じ形)。分割放送の間だけサブの列が自分のマスを持ち、ほかの時間は本チャンネルのマスがサブの列まで伸びる。
     * フォーカスで辿る番組は、サブの列では見えている時間に縮め、本チャンネルの番組が2つに分かれる
     */
    @Test
    fun サブチャンネルのマス() {
        val main = GuideService(1, "A", programs = listOf(program(10, day, day + 2 * hour)))
        val sub = GuideService(2, "A2", mainId = 1, programs = listOf(program(20, day + hour, day + hour + 30 * 60_000)))
        val other = GuideService(3, "B", programs = listOf(program(30, day, day + hour)))
        val layout = guideLayout(listOf(main, sub, other))
        val shape = layout.cells.map { listOf(it.program.id, it.column.toLong(), it.span.toLong(), (it.startAt - day) / 60_000, (it.endAt - day) / 60_000) }
        assertEquals(
            listOf(
                listOf(10L, 0L, 2L, 0L, 60L),
                listOf(30L, 2L, 1L, 0L, 60L),
                listOf(10L, 0L, 1L, 60L, 90L),
                listOf(20L, 1L, 1L, 60L, 90L),
                listOf(10L, 0L, 2L, 90L, 120L),
            ),
            shape,
        )
        // 本チャンネルの列は番組1つ (縮めない)、サブの列は 10 (縮めたもの)・20・10 (縮めたもの)
        assertEquals(main.programs, layout.lanes[0])
        assertEquals(listOf(10L, 20L, 10L), layout.lanes[1].map { it.id })
        assertEquals(listOf(day, day + hour, day + hour + 30 * 60_000), layout.lanes[1].map { it.startAt })
        assertEquals(listOf(day + hour, day + hour + 30 * 60_000, day + 2 * hour), layout.lanes[1].map { it.endAt })
        assertEquals(other.programs, layout.lanes[2])
        // サブが2つ。本チャンネルの番組の途中で分割放送が始まって終わり、重なる間は本チャンネルの列だけ、空いた列には本チャンネルのマスが伸びる
        val minute = 60_000L
        val two = guideLayout(
            listOf(
                main,
                GuideService(4, "A2", mainId = 1, programs = listOf(program(40, day + 30 * minute, day + 60 * minute))),
                GuideService(5, "A3", mainId = 1, programs = listOf(program(50, day + 45 * minute, day + 90 * minute))),
            ),
        )
        assertEquals(
            listOf(listOf(0L, 3L, 0L, 30L), listOf(0L, 1L, 30L, 45L), listOf(2L, 1L, 30L, 45L), listOf(0L, 1L, 45L, 60L), listOf(0L, 2L, 60L, 90L), listOf(0L, 3L, 90L, 120L)),
            two.cells.filter { it.program.id == 10L }.map { listOf(it.column.toLong(), it.span.toLong(), (it.startAt - day) / minute, (it.endAt - day) / minute) },
        )
        fun lane(k: Int) = two.lanes[k].map { listOf(it.id, (it.startAt - day) / minute, (it.endAt - day) / minute) }
        assertEquals(listOf(listOf(10L, 0L, 120L)), lane(0))
        assertEquals(listOf(listOf(10L, 0L, 30L), listOf(40L, 30L, 60L), listOf(10L, 60L, 120L)), lane(1))
        assertEquals(listOf(listOf(10L, 0L, 45L), listOf(50L, 45L, 90L), listOf(10L, 90L, 120L)), lane(2))
        // サブの無い表は、番組がそのまま1マスずつ
        val plain = guideLayout(listOf(main, other))
        assertEquals(listOf(1, 1), plain.cells.map { it.span })
        assertEquals(listOf(main.programs, other.programs), plain.lanes)
    }

    /** 種別ごとに 2 つまで、合わせて 4 つまで。見ている窓は捨てない */
    @Test
    fun 手元に置く窓は種別ごとに2つ_合わせて4つ() {
        val windows = GuideWindows()
        fun key(type: String, n: Int) = GuideWindows.Key(type, day + n * 24 * hour)
        assertEquals(emptyList<GuideWindows.Key>(), windows.touch(key("GR", 0)))
        assertEquals(emptyList<GuideWindows.Key>(), windows.touch(key("GR", 1)))
        assertEquals(listOf(key("GR", 0)), windows.touch(key("GR", 2)))
        windows.touch(key("BS", 0))
        windows.touch(key("BS", 1))
        // 5 つめ。いちばん使っていない GR の 1 日目を捨てる
        assertEquals(listOf(key("GR", 1)), windows.touch(key("CS", 0)))
        assertEquals(4, windows.keys.size)
        // 見ている窓 (keep) は捨てない
        assertEquals(listOf(key("GR", 2)), windows.touch(key("CS", 1), keep = setOf(key("CS", 1), key("BS", 0))))
    }

    @Test
    fun 上下は同じ局の前と次_基準の時刻を移った先の始まりに() {
        val moves = moves()
        val down = moves.down(GuideSpot(0, 0, day + 30 * 60_000)) as GuideMove.To
        assertEquals(GuideSpot(0, 1, day + hour), down.spot)
        val up = moves.up(GuideSpot(0, 1, day + hour + 10)) as GuideMove.To
        assertEquals(GuideSpot(0, 0, day), up.spot)
        // 最初の番組で上は上の段へ
        assertEquals(GuideMove.OutUp, moves.up(GuideSpot(0, 0, day)))
    }

    @Test
    fun 左右は隣の局の基準の時刻の番組_基準の時刻は替えない() {
        val moves = moves()
        val right = moves.right(GuideSpot(0, 1, day + hour + 20 * 60_000)) as GuideMove.To
        // 局 B は 1:00 から 2:00 の番組 (id 21)。基準の時刻はそのまま
        assertEquals(GuideSpot(1, 1, day + hour + 20 * 60_000), right.spot)
        assertEquals(GuideMove.OutLeft, moves.left(GuideSpot(0, 0, day)))
        assertEquals(GuideMove.Stay, moves.right(GuideSpot(1, 0, day)))
    }

    @Test
    fun まだ取得していない時間へは取得してから() {
        // 取得してあるのは day の 24 時間だけ
        val columns = listOf(listOf(program(1, day, day + 2 * hour)), listOf(program(2, day + 22 * hour, day + 24 * hour)))
        val moves = GuideMoves(columns, day - 24 * hour, day + 8 * 24 * hour) { it >= day && it < day + 24 * hour }
        assertEquals(GuideMove.Load(day - 1), moves.up(GuideSpot(0, 0, day)))
        assertEquals(GuideMove.Load(day + 24 * hour), moves.down(GuideSpot(1, 0, day + 22 * hour)))
        // 取得してある時間の中で最初の番組なら上の段へ、表の始まりより前は取得しない
        assertEquals(GuideMove.OutUp, moves.up(GuideSpot(1, 0, day + 22 * hour)))
        assertEquals(GuideMove.OutUp, GuideMoves(columns, day, day + 8 * 24 * hour) { false }.up(GuideSpot(0, 0, day)))
    }

    @Test
    fun 列送りと時間送り() {
        val columns = (0 until 10).map { c -> listOf(program(c * 10L, day, day + 2 * hour), program(c * 10L + 1, day + 2 * hour, day + 5 * hour)) }
        val moves = GuideMoves(columns, day, day + 8 * 24 * hour) { true }
        assertEquals(GuideSpot(6, 0, day + hour), (moves.page(GuideSpot(0, 0, day + hour), 6) as GuideMove.To).spot)
        // 端で止まる
        assertEquals(GuideSpot(9, 0, day + hour), (moves.page(GuideSpot(6, 0, day + hour), 6) as GuideMove.To).spot)
        assertEquals(GuideMove.Stay, moves.page(GuideSpot(9, 0, day), 6))
        assertEquals(GuideSpot(0, 1, day + 4 * hour), (moves.shift(GuideSpot(0, 0, day + hour), 3 * hour) as GuideMove.To).spot)
        // 表の始まりより前へは送らない
        assertEquals(GuideMove.Stay, moves.shift(GuideSpot(0, 0, day), -3 * hour))
    }

    @Test
    fun 番組の無い時間は次の番組() {
        val programs = listOf(program(1, day, day + hour), program(2, day + 2 * hour, day + 3 * hour))
        assertEquals(1, GuideMoves.programAt(programs, day + hour + 1))
        assertEquals(1, GuideMoves.programAt(programs, day + 5 * hour))
        assertEquals(0, GuideMoves.programAt(programs, day - 1))
        assertNull(GuideMoves.programAt(emptyList(), day))
    }

    private fun moves() = GuideMoves(
        listOf(
            listOf(program(10, day, day + hour), program(11, day + hour, day + 2 * hour)),
            listOf(program(20, day, day + hour), program(21, day + hour, day + 2 * hour)),
        ),
        day,
        day + 8 * 24 * hour,
    ) { true }

    @Test
    fun 詳細のボタン() {
        val now = day + hour
        // まだ終わっていない・予約が無い・コピー禁止でない: 予約。いま放送していれば視聴
        assertEquals(listOf(GuideAction.Reserve, GuideAction.Watch), guideActions(program(1, day, day + 2 * hour), now, airing = true))
        assertEquals(listOf(GuideAction.Reserve), guideActions(program(1, day, day + 2 * hour), now, airing = false))
        // コピー禁止は予約を出さない
        assertEquals(emptyList<GuideAction>(), guideActions(program(1, day + 2 * hour, day + 3 * hour, copyNever = true), now, airing = true))
        // 終わった番組
        assertEquals(emptyList<GuideAction>(), guideActions(program(1, day - hour, day), now, airing = true))
        // 予約済み・競合・録画中は予約取消。録れたものがあれば再生
        for (state in listOf("scheduled", "conflict")) {
            assertEquals(listOf(GuideAction.Cancel), guideActions(program(1, day + 2 * hour, day + 3 * hour, Reservation(state)), now, airing = true))
        }
        assertEquals(
            listOf(GuideAction.Cancel, GuideAction.Watch, GuideAction.Play),
            guideActions(program(1, day, day + 2 * hour, Reservation("recording"), recordingId = 5), now, airing = true),
        )
        // 録画しない: 終わっていなければ予約取消、終わっていれば何も無い
        assertEquals(listOf(GuideAction.Cancel), guideActions(program(1, day + 2 * hour, day + 3 * hour, Reservation("skipped")), now, airing = true))
        assertEquals(emptyList<GuideAction>(), guideActions(program(1, day - hour, day, Reservation("skipped")), now, airing = true))
        // 録り終えた
        assertEquals(listOf(GuideAction.Play), guideActions(program(1, day - hour, day, Reservation("done"), recordingId = 5), now, airing = true))
    }

    @Test
    fun 押した結果の1行() {
        assertEquals("予約しました", reserveNote(ReserveResult.Reserved("scheduled", true, null)))
        assertEquals("予約しましたが、録画できません: チューナーが足りません", reserveNote(ReserveResult.Reserved("conflict", false, "チューナーが足りません")))
        assertEquals("録画を始めました", reserveNote(ReserveResult.Reserved("recording", true, null)))
        assertEquals("録画を止めました。ここまでの録画は残ります", cancelNote(stopped = true, rule = false))
        assertEquals("予約を取り消しました。この放送はルールでも録画しません", cancelNote(stopped = false, rule = true))
    }

    /** マスに入る行数は切り捨てで、入りきらない行は出さない。1 行も入らなくても 1 行 (1080p: 1 行 40px、上の余白 4px) */
    @Test
    fun マスに入る行数() {
        // 10 分のマス (48px からマスの間 4px を引いた 44px) はちょうど 1 行
        assertEquals(1, cellLines(44, 4f, 40f))
        // 18 分 (82px) でも 2 行目は途中までしか入らないので 1 行
        assertEquals(1, cellLines(82, 4f, 40f))
        assertEquals(2, cellLines(84, 4f, 40f))
        // 5 分のマス (フォーカスしないと字を出さない) でも 1 行
        assertEquals(1, cellLines(20, 4f, 40f))
    }

    private val denpa = FakeDenpa()
    private val api = DenpaApi(warn = {})

    @After fun stop() = denpa.close()

    /** 番組表を引く。古い denpa (404)・断られた (403)・届かないは分けて返す */
    @Test
    fun 番組表のエンドポイント() = runTest {
        val base = BaseUrl.normalize(denpa.url())!!
        denpa.enqueue("""{"type":"BS","from":$day,"to":${day + 24 * hour},"until":null,"services":[]}""")
        val found = api.guide(base, "BS", day, day + 24 * hour) as GuideLookup.Found
        assertEquals("BS", found.page.type)
        assertEquals("/api/programs?type=BS&from=$day&to=${day + 24 * hour}&subchannels=1", denpa.requests.take().target)
        denpa.enqueue("", code = 404)
        assertEquals(GuideLookup.TooOld, api.guide(base, "GR", day, day + hour))
        denpa.enqueue("", code = 403)
        assertEquals(GuideLookup.Forbidden, api.guide(base, "GR", day, day + hour))
        denpa.enqueue("", code = 500)
        assertEquals(GuideLookup.Offline, api.guide(base, "GR", day, day + hour))
        denpa.enqueue("<html>")
        assertEquals(GuideLookup.Unreadable, api.guide(base, "GR", day, day + hour))
    }

    /** 予約 (POST) と取消 (DELETE)。断られたら denpa の文言、予約が無ければ Missing */
    @Test
    fun 予約と取消() = runTest {
        val base = BaseUrl.normalize(denpa.url())!!
        denpa.enqueue("""{"recorded":"ニュース","programId":7,"reserved":false,"state":"conflict","reason":"チューナーが足りません"}""")
        assertEquals(ReserveResult.Reserved("conflict", false, "チューナーが足りません"), api.reserveProgram(base, 7))
        val request = denpa.requests.take()
        assertEquals("POST", request.method)
        assertEquals("/api/programs/7/record", request.target)
        denpa.enqueue("""{"message":"この番組は放送が終わっています"}""", code = 400)
        assertEquals(ReserveResult.Failed("この番組は放送が終わっています"), api.reserveProgram(base, 7))

        denpa.enqueue("""{"stopped":true}""")
        assertEquals(CancelResult.Canceled(stopped = true), api.cancelProgram(base, 7))
        assertEquals("DELETE", denpa.requests.drainToList().last().method)
        denpa.enqueue("""{"message":"予約がありません"}""", code = 404)
        assertEquals(CancelResult.Missing, api.cancelProgram(base, 7))
    }

    private fun <T> java.util.concurrent.BlockingQueue<T>.drainToList(): List<T> = mutableListOf<T>().also { drainTo(it) }
}
