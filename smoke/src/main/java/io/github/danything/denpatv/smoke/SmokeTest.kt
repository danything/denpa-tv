package io.github.danything.denpatv.smoke

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runners.MethodSorters
import kotlin.math.abs

/**
 * 縮めた APK (:app の minified。R8 は release と同じ) をエミュレータで開き、ライブ・録画を映して、
 * **落ちずに映像が出るか**を見る (CI の emulator の列)。denpa-tv#24 (Android TV 12 で再生を始めた瞬間に
 * NoClassDefFoundError) は R8 を通した APK を古い Android で動かしたときだけ出たので、その組み合わせを API ごとに確かめる。
 *
 * アプリには外からだけ触る (別のプロセス。smoke/build.gradle.kts): 起動とリンク (`denpa://…`) は Intent、見るのは
 * 画面の文字 (アクセシビリティの木。Compose の Text も出る) と画面の絵、キーは UiAutomation で送る。
 * 偽の denpa (`FakeDenpa`) はこのプロセスで 127.0.0.1 に立て、繋ぐ画面の欄に URL を入れて繋ぐ。
 *
 * **落ちたかは logcat の crash の記録と、プロセスの id で見る** (落ちてもこのテストは生き残る)。
 * 落ちていれば、その記録 (例外とスタック。R8 の名前のまま。CI は mapping.txt も残す) を失敗の文に入れる
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SmokeTest {
    /**
     * **1件ごとの上限。** 固まったら (画面の木を取りに行ったまま戻らないなど) その1件を落として次へ進む。
     * CI の遅いエミュレータでも、ふつうは1件 1 分もかからない
     */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(TEST_TIMEOUT_S)

    @Test
    fun live() = watching {
        open("denpa://live/${FakeDenpa.SERVICE_ID}")
        awaitVideo(LIVE_COLOR)
        assertRequested("GET /api/services/${FakeDenpa.SERVICE_ID}/live")
    }

    /**
     * ライブのキーとメニュー。**左右で局を送る** (右で次の局を、左で元の局を denpa に頼む)。**決定の長押しで番組の詳しく**
     * (`api/programs/<now.id>` の説明が出る。メニューは開かない。離しても決定にならない)。**繰り返しを送らずに押したままでも
     * 長押しになる** (押した・離したの2つだけを送るリモコン)。戻るで詳しくだけが閉じる。**上で、局の列のいま映している局に合わせてメニューが開く。**
     * 局の列から上キーを押し続けても操作の列で止まり (閉じない)、**操作の列でもう一度上を押すと閉じて映像に戻る**。決定で操作の列と局の列が開き、「録画」で denpa にいまの番組を予約する。**戻るでメニューだけが閉じ**
     * (画面ごと戻らない。Android 12 以前では戻るキーが合いを外へ出すのに使われ、閉じずに合いだけが抜けていた)、
     * もう一度の戻るでいちばん上のメニューへ (「ライブ」に合う)。流している間は画面を点けたまま (スクリーンセーバーを出さない)、
     * ホームに出たら流れを閉じ、戻ったら頼み直す
     */
    @Test
    fun liveMenu() = watching {
        open("denpa://live/${FakeDenpa.SERVICE_ID}")
        awaitVideo(LIVE_COLOR)
        assertTrue("流している間に画面を点けたままにしていません", poll(TEXT_TIMEOUT_MS) { keepsScreenOn() })

        // 右で次の局へ。押すとすぐ行き先の局名が出て、離して少したってから頼む
        val next = "GET /api/services/${FakeDenpa.NEXT_SERVICE_ID}/live"
        pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, "右で次の局へ送りません") { next in denpa.requests || texts().any { FakeDenpa.NEXT_SERVICE_NAME in it } }
        assertTrue("右で次の局を頼みません: ${denpa.requests.distinct()}", poll(VIDEO_TIMEOUT_MS) { next in denpa.requests })
        awaitVideo(LIVE_COLOR)
        // 左で元の局へ戻る
        val before = denpa.requests.count { it == LIVE_REQUEST }
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("左で前の局を頼みません: ${denpa.requests.filter { it.endsWith("/live") }}", poll(VIDEO_TIMEOUT_MS) { denpa.requests.count { it == LIVE_REQUEST } > before })
        awaitVideo(LIVE_COLOR)

        // 決定の長押しで番組の詳しく (ダイアログ。別の窓なので windowTexts で見る)。メニューは開かず、離しても決定にならない
        longPress(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("決定の長押しで番組の詳しくが開きません: ${windowTexts()}", poll(TEXT_TIMEOUT_MS) { liveDetailOpen() })
        assertRequested("GET /api/programs/${FakeDenpa.PROGRAM_ID}")
        SystemClock.sleep(MENU_WAIT_MS)
        assertTrue("決定の長押しでメニューが開きました: ${windowTexts()}", "地上波" !in windowTexts())
        closeLiveDetail()
        // 繰り返しを送らずに押したまま (押した・離したの2つだけ) でも長押し
        holdWithoutRepeat(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("繰り返し無しの長押しで番組の詳しくが開きません: ${windowTexts()}", poll(TEXT_TIMEOUT_MS) { liveDetailOpen() })
        assertTrue("繰り返し無しの長押しでメニューが開きました: ${windowTexts()}", "地上波" !in windowTexts())
        closeLiveDetail()

        // 上で、局の列のいま映している局に合わせて開く。戻るで閉じる
        pressUntil(KeyEvent.KEYCODE_DPAD_UP, "上でメニューが開きません") { "地上波" in texts() }
        assertTrue(
            "上で開いたメニューが、いま映している局に合っていません: ${focusedTexts()}",
            poll(TEXT_TIMEOUT_MS) { focusedTexts().any { FakeDenpa.SERVICE_NAME in it } },
        )
        /*
         * 局の列から上キーを押し続けて操作の列へ上がっても閉じない (操作の列に届くのは繰り返しと離しだけ。押しはじめを見ていない)。
         * 合いが移ったかは画面の木では見ない (Compose の入力の合いは、局の札から札へ移っても古いまま返ることがある)。
         * 続けて上をもう1回押して閉じれば、操作の列 (いちばん上) に居たことになる (局の列のままなら、上がるだけで閉じない)。
         * メニューは 8 秒触らなければ閉じるので、待つのは MENU_WAIT_MS まで (勝手に閉じたのを上キーで閉じたと取り違えない)
         */
        holdKey(KeyEvent.KEYCODE_DPAD_UP, HOLD_REPEATS)
        SystemClock.sleep(FOCUS_SETTLE_MS)
        assertTrue("局の列から上キーを押し続けたら、メニューが閉じました: ${texts()}", "地上波" in texts())
        // 操作の列でもう一度上を押すと閉じて、映像に戻る (閉じたあとの映像が上キーでメニューを開き直さない)
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("操作の列で上を押してもメニューが閉じません: ${texts()}", poll(MENU_WAIT_MS) { "地上波" !in texts() })
        awaitVideo(LIVE_COLOR)
        SystemClock.sleep(MENU_WAIT_MS)
        assertTrue("上で閉じたメニューが開き直しました: ${texts()}", "地上波" !in texts())

        openMenu()
        val record = node { it.text?.toString() == "録画" }?.let(::clickable) ?: throw AssertionError("「録画」がありません: ${texts()}")
        assertTrue("「録画」を押せません", record.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        /*
         * 届いたかは偽の denpa の記録で見る。「録画を始めます」の1行は 4 秒で消えるので、遅いエミュレータでは
         * 画面の木を読み終える前に消えていることがある (CI の API 36 で、出たのに見落として落ちた)
         */
        val recordRequest = "POST /api/services/${FakeDenpa.SERVICE_ID}/record"
        assertTrue("$recordRequest が来ていません: ${denpa.requests.distinct()}", poll(TEXT_TIMEOUT_MS) { recordRequest in denpa.requests })
        // 「録画」はメニューを閉じる。閉じ終えるのを待ってから次の決定を送る (閉じている途中の決定は消える札に届いて捨てられる)
        assertTrue("「録画」でメニューが閉じません: ${texts()}", poll(TEXT_TIMEOUT_MS) { "地上波" !in texts() })

        openMenu()
        press(KeyEvent.KEYCODE_BACK)
        assertTrue("戻るでメニューが閉じません: ${texts()}", poll(TEXT_TIMEOUT_MS) { "地上波" !in texts() })
        awaitVideo(LIVE_COLOR)

        // ホームに出ると流れを閉じ、戻ると同じ局を頼み直す
        val asked = denpa.requests.count { it == LIVE_REQUEST }
        press(KeyEvent.KEYCODE_HOME)
        assertTrue("ホームに出ません", poll(TEXT_TIMEOUT_MS) { activeWindow()?.packageName?.toString() != APP })
        // 裏に回った (onStop) のを待つ。すぐ開き直すと、止まる前に前へ戻るだけになる
        SystemClock.sleep(STOP_WAIT_MS)
        assertTrue("ホームに出ても画面を点けたままです", poll(TEXT_TIMEOUT_MS) { !keepsScreenOn() })
        relaunch()
        assertTrue("戻ってもライブを頼み直しません", poll(VIDEO_TIMEOUT_MS) { denpa.requests.count { it == LIVE_REQUEST } > asked })
        awaitVideo(LIVE_COLOR)

        press(KeyEvent.KEYCODE_BACK)
        awaitText { it == "ライブ" }
        assertTrue("ライブを離れても画面を点けたままです", poll(TEXT_TIMEOUT_MS) { !keepsScreenOn() })
    }

    /**
     * **切れたら繋ぎ直す。** 偽の denpa のライブは 10 秒で流れを閉じる (denpa が番組の境目で焼き直した・入れ替わったのと同じ)。
     * アプリは同じ局を頼み直す。流れが閉じずに黙ったら (チューナーのドライバが止まった)、10 秒進まないのに気付いて頼み直す。そこで 503 (入れ替わりの最中) が続いても、Media3 が中で読み直し尽くしたあと、アプリが待って
     * 頼み直し続け、denpa が戻ればまた映す。その間「再生できません」は出さない (`watching`)。繋ぎ直した理由は logcat に1行ずつ (タグ denpa)
     */
    @Test
    fun liveReconnect() = watching {
        shell("logcat -c")
        open("denpa://live/${FakeDenpa.SERVICE_ID}")
        awaitVideo(LIVE_COLOR)
        // 流れが閉じたら頼み直す。次の流れは途中で黙る (閉じない) ので、10 秒進まないのに気付いてまた頼み直す
        denpa.stallLive.set(1)
        assertTrue("流れが閉じても頼み直しません: ${appLog()}", poll(VIDEO_TIMEOUT_MS) { "ended" in appLog() })
        // 黙ったのに気付いたら、その頼み直しからは 503 (denpa の入れ替わりの最中) を返し続ける
        denpa.failLive.set(Int.MAX_VALUE)
        assertTrue("流れが黙っても頼み直しません: ${appLog()}", poll(VIDEO_TIMEOUT_MS) { "stall" in appLog() })
        // 503 が続いて Media3 が諦めても、アプリが頼み直す
        assertTrue("503 が続くと頼み直しません: ${appLog()}", poll(VIDEO_TIMEOUT_MS) { "HTTP 503" in appLog() })
        // 繋ぎ直している間は、前の絵の上に回るものと「繋ぎ直しています」
        assertTrue("「繋ぎ直しています」が出ません: ${texts()}", poll(TEXT_TIMEOUT_MS) { "繋ぎ直しています" in texts() })
        texts().firstOrNull { it.startsWith("再生できません") }?.let { fail(it) }
        // denpa が戻ったら、また映す
        val asked = denpa.requests.count { it == LIVE_REQUEST }
        denpa.failLive.set(0)
        assertTrue("denpa が戻っても頼み直しません", poll(VIDEO_TIMEOUT_MS) { denpa.requests.count { it == LIVE_REQUEST } > asked })
        // 映ったら幕を下ろす (地の色は前の絵でも同じなので、映ったかは幕で見る)
        assertTrue("映り直しても「繋ぎ直しています」が消えません", poll(VIDEO_TIMEOUT_MS) { "繋ぎ直しています" !in texts() })
        awaitVideo(LIVE_COLOR)
    }

    /**
     * **denpa が 200 のまま何も送らずに閉じたら** (選局・焼くのに失敗した)、何度か頼み直してから「映像を送らずに閉じました」と言う
     * (Media3 の `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` をそのまま出さない)。denpa が戻ってから局を替えれば、また映る。
     * 次の局を頼むので、`liveMenu` (まだ次の局を頼んでいないのを確かめてから送る) より後に走る名前にしておく
     */
    @Test
    fun liveRefused() = watching {
        shell("logcat -c")
        denpa.emptyLive.set(Int.MAX_VALUE)
        open("denpa://live/${FakeDenpa.SERVICE_ID}")
        awaitText { it.startsWith("denpa が映像を送らずに閉じました") }
        // 縮めた APK では例外の名前が変わるので、文で見る (`EmptyStreamException`)
        assertTrue("空だったのが logcat にありません: ${appLog()}", "denpa が何も送らずに閉じました" in appLog())
        denpa.emptyLive.set(0)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitVideo(LIVE_COLOR)
        assertTrue("映っても文が消えません: ${texts()}", poll(TEXT_TIMEOUT_MS) { texts().none { it.startsWith("denpa が映像を送らずに") } })
    }

    /**
     * 追っかけも、録り終える前に流れが閉じたら (denpa の入れ替え) 居た場所から頼み直す。録り終えたのかは denpa に聞いて決めるので、
     * 録画の一覧を読み直してから頼み直す。「最後まで観ました」にはしない
     */
    @Test
    fun chaseReconnect() = watching {
        shell("logcat -c")
        open("denpa://recording/${FakeDenpa.CHASE_ID}")
        awaitVideo(LIVE_COLOR)
        val chase = "GET /api/recordings/${FakeDenpa.CHASE_ID}/chase"
        val asked = denpa.requests.count { it == chase }
        assertTrue("追っかけの流れが閉じても頼み直しません: ${appLog()}", poll(VIDEO_TIMEOUT_MS) { denpa.requests.count { it == chase } > asked })
        val log = appLog()
        assertTrue("追っかけの繋ぎ直しが logcat にありません: $log", "chase" in log && "ended" in log)
        assertTrue("録っている最中なのに「最後まで観ました」になりました", texts().none { it.startsWith("最後まで観ました") })
        awaitVideo(LIVE_COLOR)
        press(KeyEvent.KEYCODE_BACK)
        awaitText { it == FakeDenpa.RECORDING_TITLE }
    }

    /** アプリが logcat に出した繋ぎ直しの記録 (タグ denpa) */
    private fun appLog(): String = shell("logcat -d -s denpa:I")

    /**
     * 録画を映す。字幕 (文字の配置の `captions.json`。`FakeCaptions`) を描く。**決定で止めると帯が操作の列の「再生」に合って開き**、上でシークバーへ、シークバー (いちばん上の段) でもう一度上を押すと閉じる。
     * 下で開いたシークバーからも上で閉じる。偽の録画は 10 秒しかないので、映ったらすぐ止めてから見る
     * (帯は止めている間も 5 秒触らなければ閉じる。キーを押すたびに数え直すので、続けて押している間は閉じない)
     * **決定の長押しで番組の詳しいところ** (一覧のカードの長押しと同じ) が開き、戻るで閉じて映像に戻る (止めたまま。長押しの離しで動き出さない)
     */
    @Test
    fun recording() = watching {
        open("denpa://recording/${FakeDenpa.RECORDING_ID}")
        awaitVideo(RECORDING_COLOR)
        assertRequested("GET /api/recordings/${FakeDenpa.RECORDING_ID}/file")

        // 字幕 (文字の配置の captions.json) を描く。出たかは読み上げの文で、塗ったかは背景の青の点で見る
        assertRequested("GET /api/recordings/${FakeDenpa.RECORDING_ID}/captions.json")
        assertTrue("字幕が出ません: ${descriptions()}", poll(TEXT_TIMEOUT_MS) { FakeCaptions.TEXT in descriptions() })
        var painted = 0
        assertTrue(
            "字幕の背景が塗られていません (青の点 $painted)",
            poll(TEXT_TIMEOUT_MS) { painted = screenshot()?.let { count(it, FakeCaptions.BACKGROUND) } ?: 0; painted >= CAPTION_DOTS },
        )
        // 字は denpa から取りに行く (偽の denpa は返さないので、端末の字で描いている)
        assertTrue("字を取りに行きません: ${denpa.requests.distinct()}", poll(TEXT_TIMEOUT_MS) { "GET /api/font" in denpa.requests })

        // 止めると操作の列が開いて「再生」に合う (決定でそのまま動かせる)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("決定で止めても操作の列が「再生」に合って開きません: ${focusedTexts()}", poll(TEXT_TIMEOUT_MS) { barOpen() && "再生" in focusedTexts() })
        // 操作の列から上でシークバーへ (閉じない)。シークバーに居たかは、次の上で閉じることで見る (画面の木の合いは古いことがある)
        press(KeyEvent.KEYCODE_DPAD_UP)
        SystemClock.sleep(FOCUS_SETTLE_MS)
        assertTrue("操作の列から上で帯が閉じました: ${texts()}", barOpen())
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("シークバーで上を押しても帯が閉じません: ${texts()}", poll(TEXT_TIMEOUT_MS) { !barOpen() })
        assertTrue("帯を閉じたら止めた位置の帯が出ていません (画面ごと戻った?): ${texts()}", paused())

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("下でシークバーが開きません: ${texts()}", poll(TEXT_TIMEOUT_MS) { barOpen() })
        SystemClock.sleep(FOCUS_SETTLE_MS)
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("下で開いたシークバーで上を押しても帯が閉じません: ${texts()}", poll(TEXT_TIMEOUT_MS) { !barOpen() })
        assertTrue("帯を閉じたら止めた位置の帯が出ていません: ${texts()}", paused())

        // 詳しくはダイアログ (別の窓)。古い Android (API 24・28) では、閉じたあと rootInActiveWindow が空のまま返るので、アプリの窓を全部見る
        longPress(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("決定の長押しで詳しくが開きません: ${windowTexts()}", poll(TEXT_TIMEOUT_MS) { windowTexts().let { "閉じる" in it && FakeDenpa.RECORDING_DESCRIPTION in it } })
        press(KeyEvent.KEYCODE_BACK)
        // 窓が取れずに空なのを「閉じた」と取り違えない
        assertTrue("戻るで詳しくが閉じません: ${windowTexts()}", poll(TEXT_TIMEOUT_MS) { windowTexts().let { it.isNotEmpty() && "閉じる" !in it } })
        assertTrue(
            "詳しくを閉じたら止めた位置の帯が出ていません (動き出した・画面ごと戻った?): ${windowTexts()}",
            poll(TEXT_TIMEOUT_MS) { windowTexts().any { it.startsWith("一時停止  ") } },
        )
    }

    /** ライブの詳しくが開いている (番組の説明と「閉じる」) */
    private fun liveDetailOpen() = windowTexts().let { "閉じる" in it && FakeDenpa.PROGRAM_DESCRIPTION in it }

    /**
     * ライブの詳しくを戻るで閉じて、映像に戻ったのを見る。映像だけのライブの窓には字が無い (知らせが消えていれば空) ので、
     * 閉じたかは映像の色で見る (詳しくが開いたままなら画面の多くを覆っている。画面ごと戻ったならライブの色は出ない)
     */
    private fun closeLiveDetail() {
        press(KeyEvent.KEYCODE_BACK)
        assertTrue("戻るで番組の詳しくが閉じません: ${windowTexts()}", poll(TEXT_TIMEOUT_MS) { "閉じる" !in windowTexts() })
        awaitVideo(LIVE_COLOR)
    }

    /** 録画を止めている (止めた位置の帯が出ている) */
    private fun paused() = texts().any { it.startsWith("一時停止  ") }

    /** 録画の帯 (操作の列) が開いている */
    private fun barOpen() = texts().any { it.startsWith("CM 飛ばし") }

    /** 録画の一覧から、左のメニュー (ナビゲーション ドロワー) で設定を開く */
    @Test
    fun recordingsAndSettings() = watching {
        open("denpa://recordings")
        awaitText { it == FakeDenpa.RECORDING_TITLE }
        // 左キーでメニューを開く (どこにも合っていなければ、1回目は一覧に合うだけ)。開くと行き先の名前が出る。
        // 遅いエミュレータでは開くのに間がかかるので、押すたびにしばらく待つ
        for (i in 0 until MENU_TRIES) {
            press(KeyEvent.KEYCODE_DPAD_LEFT)
            if (poll(MENU_WAIT_MS) { "設定" in texts() }) break
        }
        // ライブは選ぶとすぐ映すので、キーで下りず、設定を直に押す
        val settings = node { it.text?.toString() == "設定" }?.let(::clickable) ?: throw AssertionError("メニューが開きません: ${texts()}")
        assertTrue("「設定」を押せません", settings.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        awaitText { it.startsWith("繋ぐ先: ${denpa.url}") }
    }

    /**
     * `block` の間アプリが落ちず、終えてから数秒置いても同じプロセスのまま、いちばん前がアプリで、再生のエラーも出ていない
     */
    private fun watching(block: () -> Unit) {
        // 前のテストで落ちた記録は消す (それぞれのテストが自分の分だけ見る)
        shell("logcat -b crash -c")
        // 前から取る (block の間に落ちて起き直しても見逃さない)。アプリは connect() から動いている
        val pid = pid()
        assertTrue("アプリのプロセスがありません", pid.isNotEmpty())
        block()
        SystemClock.sleep(SETTLE_MS)
        assertNoCrash()
        assertEquals("アプリのプロセスが替わりました (落ちて起き直した?)", pid, pid())
        assertEquals(APP, activeWindow()?.packageName?.toString())
        texts().firstOrNull { it.startsWith("再生できません") }?.let { fail(it) }
    }

    /**
     * 映像が出るまで待つ。画面の絵を撮って、映像の地の色 (`scripts/smoke-media.sh`) の点が `VIDEO_SHARE` 以上あれば出ている。
     * 落ちた・再生の画面が「再生できません」を出したら、待たずに失敗にする
     */
    private fun awaitVideo(color: Int) {
        val deadline = SystemClock.uptimeMillis() + VIDEO_TIMEOUT_MS
        var last = 0f
        while (SystemClock.uptimeMillis() < deadline) {
            assertNoCrash()
            texts().firstOrNull { it.startsWith("再生できません") || it.startsWith("この端末で再生できる") }?.let { fail(it) }
            val shot = screenshot()
            last = shot?.let { share(it, color) } ?: 0f
            if (last >= VIDEO_SHARE) return
            SystemClock.sleep(POLL_MS)
        }
        fail("映像が出ません (地の色の点 ${(last * 100).toInt()}%)。画面の文字: ${texts()}")
    }

    /**
     * 決定でライブのメニューを開く (局の列の「地上波」が出る)。リンクで開き直した直後は前の画面と入れ替わっている最中で
     * キーが届かないことがあるので、出るまで何度か押す
     */
    private fun openMenu() = pressUntil(KeyEvent.KEYCODE_DPAD_CENTER, "決定でメニューが開きません") { "地上波" in texts() }

    /**
     * `done` になるまで `code` を押す (届かなかったときだけ押し直す)。ならなければ `message` で失敗にする。
     * **押す前に `done` でないのを確かめる** — 前の操作で閉じるはずのもの (「録画」で閉じるメニュー) は次のこまで消えるので、
     * 押してすぐ読むと閉じかけのものを「開いた」と取り違え、キーが届いていない (閉じかけの札に届いて捨てられた) のに先へ進んでしまう
     */
    private fun pressUntil(code: Int, message: String, done: () -> Boolean) {
        assertTrue("キーを送る前から待つ先の様子のままです ($message): ${texts()}", poll(TEXT_TIMEOUT_MS) { !done() })
        for (i in 0 until MENU_TRIES) {
            press(code)
            if (poll(MENU_WAIT_MS, done)) return
        }
        fail("$message: ${texts()}")
    }

    /** 合っているもの (入力の合い) とその中の文字。Compose の札は中の Text を子に持つ */
    private fun focusedTexts(): List<String> {
        val focused = activeWindow()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return emptyList()
        val out = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo) {
            node.text?.let { out += it.toString() }
            node.contentDescription?.let { out += it.toString() }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        walk(focused)
        return out
    }

    /** アプリの窓が画面を点けたままにする印 (FLAG_KEEP_SCREEN_ON) を持っているか。古い Android は印を16進で出す */
    private fun keepsScreenOn(): Boolean {
        val window = shell("dumpsys window windows").split("Window #").firstOrNull { "$APP/" in it.substringBefore('\n') } ?: return false
        if ("KEEP_SCREEN_ON" in window) return true
        val flags = Regex("fl=#([0-9a-fA-F]+)").find(window)?.groupValues?.get(1) ?: return false
        return flags.toLong(16) and FLAG_KEEP_SCREEN_ON != 0L
    }

    private fun assertRequested(request: String) =
        assertTrue("$request が来ていません: ${denpa.requests.distinct()}", request in denpa.requests)

    private fun press(code: Int) {
        awaitAppFocus()
        val at = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(at, SystemClock.uptimeMillis(), action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            instrumentation.uiAutomation.injectInputEvent(event, true)
        }
    }

    /** 押し続ける: 押して、繰り返しを `repeats` 回送ってから離す (リモコンで押し続けたときと同じ並び) */
    private fun holdKey(code: Int, repeats: Int) {
        awaitAppFocus()
        val at = SystemClock.uptimeMillis()
        fun send(action: Int, repeat: Int) {
            val event = KeyEvent(at, SystemClock.uptimeMillis(), action, code, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            instrumentation.uiAutomation.injectInputEvent(event, true)
        }
        send(KeyEvent.ACTION_DOWN, 0)
        for (repeat in 1..repeats) {
            SystemClock.sleep(REPEAT_MS)
            send(KeyEvent.ACTION_DOWN, repeat)
        }
        send(KeyEvent.ACTION_UP, 0)
    }

    /** 繰り返しを送らずに押し続ける: 押して、`HELD_MS` たってから離す (繰り返しを送らないリモコンと同じ並び) */
    private fun holdWithoutRepeat(code: Int) {
        awaitAppFocus()
        val at = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val event = KeyEvent(at, SystemClock.uptimeMillis(), action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            instrumentation.uiAutomation.injectInputEvent(event, true)
        }
        send(KeyEvent.ACTION_DOWN)
        SystemClock.sleep(HELD_MS)
        send(KeyEvent.ACTION_UP)
    }

    /** 長押し: 押して、長押しの印つきの繰り返しを送ってから離す (リモコンで押し続けたときと同じ並び) */
    private fun longPress(code: Int) {
        awaitAppFocus()
        val at = SystemClock.uptimeMillis()
        fun send(action: Int, repeat: Int, flags: Int) {
            val event = KeyEvent(at, SystemClock.uptimeMillis(), action, code, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD)
            instrumentation.uiAutomation.injectInputEvent(event, true)
        }
        send(KeyEvent.ACTION_DOWN, 0, 0)
        SystemClock.sleep(LONG_PRESS_MS)
        send(KeyEvent.ACTION_DOWN, 1, KeyEvent.FLAG_LONG_PRESS)
        send(KeyEvent.ACTION_UP, 0, 0)
    }

    private fun screenshot(): Bitmap? {
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && shot.config == Bitmap.Config.HARDWARE) {
            shot.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            shot
        }
    }

    /** 画面を格子に区切った点のうち、`color` に近いものの割合 */
    private fun share(bitmap: Bitmap, color: Int): Float {
        var near = 0
        for (gx in 0 until GRID_X) for (gy in 0 until GRID_Y) {
            val p = bitmap.getPixel(bitmap.width * (2 * gx + 1) / (2 * GRID_X), bitmap.height * (2 * gy + 1) / (2 * GRID_Y))
            if (abs(Color.red(p) - Color.red(color)) < COLOR_TOLERANCE &&
                abs(Color.green(p) - Color.green(color)) < COLOR_TOLERANCE &&
                abs(Color.blue(p) - Color.blue(color)) < COLOR_TOLERANCE
            ) {
                near++
            }
        }
        return near.toFloat() / (GRID_X * GRID_Y)
    }

    /** 画面を `CAPTION_STEP` 画素おきに見て、`color` に近い点の数 (字幕は小さいので、格子の `share` では粗すぎる) */
    private fun count(bitmap: Bitmap, color: Int): Int {
        var near = 0
        for (y in 0 until bitmap.height step CAPTION_STEP) for (x in 0 until bitmap.width step CAPTION_STEP) {
            val p = bitmap.getPixel(x, y)
            if (abs(Color.red(p) - Color.red(color)) < COLOR_TOLERANCE &&
                abs(Color.green(p) - Color.green(color)) < COLOR_TOLERANCE &&
                abs(Color.blue(p) - Color.blue(color)) < COLOR_TOLERANCE
            ) {
                near++
            }
        }
        return near
    }

    companion object {
        private const val APP = "io.github.danything.denpatv"
        private val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
        private lateinit var denpa: FakeDenpa

        /** 偽の denpa を立て、まっさらのアプリを起動して、繋ぐ画面の欄から繋ぐ */
        @BeforeClass
        @JvmStatic
        fun connect() {
            denpa = FakeDenpa(instrumentation.context.assets)
            shell("pm clear $APP")
            shell("logcat -b crash -c")
            val launch = (instrumentation.context.packageManager.getLeanbackLaunchIntentForPackage(APP) ?: error("$APP が入っていません"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            /*
             * pm clear で前の画面ごと消えると、ランチャーが前に出て、あとから自分の知らせの画面 (Google TV の ShowDialogsActivity)
             * をアプリの上に重ねることがある (手元の API 36 で、開いたアプリが隠れたまま「繋ぐ」を待ち切った)。
             * アプリでない窓に合いがあるまま `RELAUNCH_MS` たったら、もう一度前に出す
             */
            val deadline = SystemClock.uptimeMillis() + TEXT_TIMEOUT_MS
            var started = SystemClock.uptimeMillis()
            instrumentation.context.startActivity(launch)
            while (texts().none { it == "繋ぐ" }) {
                assertNoCrash()
                val now = SystemClock.uptimeMillis()
                if (now > deadline) fail("繋ぐ画面が出ません。合いのある窓: ${focusedWindow()}、画面の文字: ${texts()}")
                if (now - started > RELAUNCH_MS && focusedWindow().let { it != null && it != APP }) {
                    instrumentation.context.startActivity(launch)
                    started = now
                }
                SystemClock.sleep(POLL_MS)
            }
            val field = node { it.isEditable } ?: error("URL を入れる欄がありません: ${texts()}")
            val text = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, denpa.url) }
            assertTrue("URL を入れられません", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text))
            val button = node { it.text?.toString() == "繋ぐ" }?.let(::clickable) ?: error("「繋ぐ」がありません")
            assertTrue("「繋ぐ」を押せません", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            // 繋がると録画の一覧が開き、知らせ (SSE) にも繋ぐ
            awaitText { it == FakeDenpa.RECORDING_TITLE }
            assertTrue("api/events に繋ぎません: ${denpa.requests.distinct()}", poll(TEXT_TIMEOUT_MS) { "GET /api/events" in denpa.requests })
        }

        @AfterClass
        @JvmStatic
        fun stop() = denpa.close()

        /**
         * ホームから戻る (ランチャーから開き直すのと同じ。いまの画面のまま前に出る)。シェルから開く — 新しい Android は、
         * 前に出ていないテストのプロセスからの起動を止めることがある (裏からの起動の制限)
         */
        private fun relaunch() {
            val launch = instrumentation.context.packageManager.getLeanbackLaunchIntentForPackage(APP) ?: error("$APP が入っていません")
            shell("am start -n ${launch.component!!.flattenToShortString()}")
        }

        private fun open(link: String) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(APP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            instrumentation.context.startActivity(intent)
        }

        /** `done` になるまで最大 `ms` 待つ。なったら true */
        private fun poll(ms: Long, done: () -> Boolean): Boolean {
            val deadline = SystemClock.uptimeMillis() + ms
            while (SystemClock.uptimeMillis() < deadline) {
                if (done()) return true
                SystemClock.sleep(POLL_MS)
            }
            return done()
        }

        private fun awaitText(match: (String) -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + TEXT_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                assertNoCrash()
                if (texts().any(match)) return
                SystemClock.sleep(POLL_MS)
            }
            fail("出るはずの文字がありません。画面の文字: ${texts()}")
        }

        /** アプリが落ちていれば、その記録で失敗にする */
        private fun assertNoCrash() {
            val log = shell("logcat -b crash -d")
            // Java の例外は「Process: <パッケージ>, PID: …」、ネイティブ (tombstone) は「>>> <パッケージ> <<<」
            if ("Process: $APP," in log || ">>> $APP <<<" in log) fail("アプリが落ちました:\n$log")
        }

        private fun pid(): String = shell("pidof $APP").trim()

        /**
         * **キーを送る前に、アプリの窓 (ダイアログも) に入力の合いが来ているのを待つ。** キーは合いのある窓に届くので、
         * 開き直した直後 (ホームから戻した・リンクで開き直した) にまだ前の窓に合いがあると、送ったキーはそちらへ行って消える
         * (映像は合いより先に映るので、映ったのを見てからでも早すぎることがある)
         */
        private fun awaitAppFocus() {
            assertTrue("アプリの窓に入力の合いが来ません: ${focusedWindow()}", poll(TEXT_TIMEOUT_MS) { focusedWindow() == APP })
        }

        /** 入力の合いがある窓のパッケージ (WindowManager の mCurrentFocus。ダイアログは題が無いのでパッケージ名だけで出る) */
        private fun focusedWindow(): String? =
            Regex("mCurrentFocus=Window\\{\\S+ u\\d+ ([^ /}]+)").find(shell("dumpsys window"))?.groupValues?.get(1)

        private fun activeWindow(): AccessibilityNodeInfo? = freshAutomation().rootInActiveWindow

        /**
         * **画面の木を読む前に、毎回アクセシビリティのキャッシュを捨てる。** UiAutomation は読んだ部品をキャッシュし、アプリからの
         * 「中身が替わった」の知らせで捨てる。Compose の画面では知らせで捨てられない枝が残り、**閉じた帯・ダイアログを何十秒も返し続ける**
         * ことがある (CI の API 31 で、上キーで閉じた帯が 30 秒たっても木に残り、ダイアログを閉じたあとはアプリの窓が1つも取れなかった。
         * 手元の API 31 でも、古い木が返っている間に部品ごとに取り直す (refresh) と閉じていて、キャッシュを捨てると読み直せた)。キャッシュは API 34 からは clearCache() で、
         * それより前は setServiceInfo (中で捨ててから設定する) で捨てる。ダイアログも読むので、アプリの窓を全部取る印も一緒に付ける
         */
        private fun freshAutomation(): UiAutomation {
            val automation = instrumentation.uiAutomation
            // 印は UiAutomation の側で持つので、毎回確かめる (こちらで覚えておくと、作り直されたときに食い違う)
            val info = automation.serviceInfo
            if (info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                automation.serviceInfo = info
            } else {
                automation.clearCache()
            }
            return automation
        }

        private fun nodes(): List<AccessibilityNodeInfo> {
            val out = mutableListOf<AccessibilityNodeInfo>()
            fun walk(node: AccessibilityNodeInfo) {
                out += node
                for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
            }
            activeWindow()?.takeIf { it.packageName?.toString() == APP }?.let(::walk)
            return out
        }

        private fun node(match: (AccessibilityNodeInfo) -> Boolean) = nodes().firstOrNull(match)

        /** アプリの窓 (ダイアログも) すべての文字 */
        private fun windowTexts(): List<String> {
            val automation = freshAutomation()
            val out = mutableListOf<String>()
            fun walk(node: AccessibilityNodeInfo) {
                node.text?.let { out += it.toString() }
                for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
            }
            automation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == APP }.forEach(::walk)
            return out
        }

        /** いちばん前のアプリの窓の文字 */
        private fun texts(): List<String> = nodes().mapNotNull { it.text?.toString() }

        /** いちばん前のアプリの窓の読み上げの文 (字幕の層が持つ) */
        private fun descriptions(): List<String> = nodes().mapNotNull { it.contentDescription?.toString() }

        /** 押せるのは文字を包む部品 (Button) のほう */
        private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
            generateSequence(node) { it.parent }.firstOrNull { it.isClickable }

        /** シェルの権限で走らせて、出力を返す */
        private fun shell(command: String): String =
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .use { it.readBytes().toString(Charsets.UTF_8) }

        // 映像の地の色 (scripts/smoke-media.sh)
        private const val LIVE_COLOR = 0xFF20C040.toInt()
        private const val RECORDING_COLOR = 0xFFC02080.toInt()
        private const val COLOR_TOLERANCE = 48
        private val LIVE_REQUEST = "GET /api/services/${FakeDenpa.SERVICE_ID}/live"
        /** WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON */
        private const val FLAG_KEEP_SCREEN_ON = 0x80L
        private const val VIDEO_SHARE = 0.3f
        private const val GRID_X = 32
        private const val GRID_Y = 18
        /**
         * 字幕の背景を探す刻みと、塗られたとみなす点の数。字幕の背景は 1080p で 320x120 ほど (4 字 × 区画 40x60 を 2 倍)。
         * 8 画素おきなら 600 点ほどのうち、字の掛かっていないところ
         */
        private const val CAPTION_STEP = 8
        private const val CAPTION_DOTS = 100

        // CI のエミュレータ (KVM はあるが GPU は無い) は遅いので長めに待つ
        private const val VIDEO_TIMEOUT_MS = 60_000L
        private const val TEXT_TIMEOUT_MS = 30_000L
        private const val SETTLE_MS = 3_000L
        private const val POLL_MS = 500L
        private const val MENU_TRIES = 4
        private const val STOP_WAIT_MS = 3_000L
        /** 開いたアプリがほかの画面に隠れたままなら、開き直すまで */
        private const val RELAUNCH_MS = 5_000L
        private const val TEST_TIMEOUT_S = 150L
        private const val MENU_WAIT_MS = 3_000L
        /** 繰り返し無しで押し続ける長さ (アプリが長押しとみなす 0.7 秒より十分長く) */
        private const val HELD_MS = 1_500L
        private const val LONG_PRESS_MS = 700L
        /** 押し続けたときの繰り返しの数と間 (リモコンの繰り返しはおよそ 50 ミリ秒ごと) */
        private const val HOLD_REPEATS = 6
        private const val REPEAT_MS = 50L
        /** キーで合いを移してから次のキーを送るまで (合わせる先が出来て合いが移り終えるのを待つ) */
        private const val FOCUS_SETTLE_MS = 1_000L
    }
}
