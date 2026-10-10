package io.github.danything.denpatv.smoke

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * **画面の絵を撮る** (README・docs/ と PR に貼る絵)。CI の Screenshots の流れ (`.github/workflows/screenshots.yml`) だけが
 * `-e shots 1` を付けて走らせる。付けなければ何もしない (smoke では飛ばす)。
 *
 * 作り物の録画・局・番組 (`Showcase`) を返す偽の denpa に繋ぎ、docs/images/ の絵を全部 `/data/local/tmp/shots/<名前>.png` に撮る
 * (CI が adb pull で取ってくる)。繋ぐ画面 → 録画の一覧・詳しく → 左のメニューからライブ (局送り・選局の間・メニュー・局の列・番組の詳しく)
 * → 設定 → 録画の再生 (字幕・操作の列・詳しく・最後まで観たとき) → 追っかけ、の順。README の頭の動く絵 (navigation) は
 * `navigation/<番号>-<見せるミリ秒>.png` のこまに撮り、CI が1つの webp に繋ぐ。
 * 終わりに一覧を上下に送り、こまの描き時間を測れるようにする (前と後の見比べ用。読むのは scripts/shots-run.sh)
 */
class Screenshots {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(900)

    private var frames = 0

    @Test
    fun shoot() {
        assumeTrue("撮るときだけ (-e shots 1)", InstrumentationRegistry.getArguments().getString("shots") != null)
        FakeDenpa(instrumentation.context.assets, showcase = true).use { denpa ->
            shell("pm clear $APP")
            shell("rm -rf $DIR")
            shell("mkdir -p $DIR/navigation")
            val launch = (instrumentation.context.packageManager.getLeanbackLaunchIntentForPackage(APP) ?: error("$APP が入っていません"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            instrumentation.context.startActivity(launch)
            await("繋ぐ画面が出ません") { "繋ぐ" in texts() }
            SystemClock.sleep(SETTLE_MS)
            shot("setup")
            val field = node { it.isEditable } ?: error("URL を入れる欄がありません: ${texts()}")
            val text = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, denpa.url) }
            assertTrue("URL を入れられません", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text))
            click("繋ぐ")

            recordings()
            live(denpa)
            settings()
            recording()
            chase()

            // 軽さ: 一覧で上下に送るあいだのこまを数える (読むのは scripts/shots-run.sh の dumpsys gfxinfo)
            await("録画の一覧に戻りません") { texts().any { "名城" in it } }
            SystemClock.sleep(SETTLE_MS)
            shell("dumpsys gfxinfo $APP reset")
            repeat(2) {
                for (key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                    repeat(MOVES) {
                        press(key)
                        SystemClock.sleep(MOVE_GAP_MS)
                    }
                }
            }
            SystemClock.sleep(SETTLE_MS)
        }
    }

    /** 録画の一覧 (下の端・頭・長い題)・録画の詳しく。終わりは長い題 (行の左の端) に合わせておく (続けて左でメニューを開く) */
    private fun recordings() {
        // 開くと一番下 (いちばん古い録画) に合う
        await("録画の一覧が出ません") { texts().any { "名城" in it } }
        SystemClock.sleep(SETTLE_MS)
        shot("recordings-bottom")

        // 一覧の頭 (録っている最中の録画。その下の段に焼いている最中の録画)
        focusCard("辺境に追放された薬師令嬢", KeyEvent.KEYCODE_DPAD_UP)
        SystemClock.sleep(SETTLE_MS)
        shot("recordings")

        // 長押しで詳しく (続きのある録画。放送の詳細が揃っている)
        focusCard("星読みの薬師", KeyEvent.KEYCODE_DPAD_DOWN)
        SystemClock.sleep(SETTLE_MS)
        longPress(KeyEvent.KEYCODE_DPAD_CENTER)
        await("録画の詳しくが開きません") { windowTexts().any { it.startsWith("続きから再生") } }
        SystemClock.sleep(SETTLE_MS)
        shot("detail")
        press(KeyEvent.KEYCODE_BACK)
        await("録画の詳しくが閉じません") { windowTexts().let { it.isNotEmpty() && "閉じる" !in it } }
        // 閉じると一覧は一番下に合わせ直すことがある。落ち着くのを待ち、居る所から長い題 (10/5) のほうへ送る
        await("録画の一覧に戻りません") { texts().any { "録画" == it } }
        SystemClock.sleep(SETTLE_MS)
        val bottom = texts().any { "名城" in it }

        // 長い題 (話数を残して途中を切る)
        focusCard("病弱令嬢", if (bottom) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
        SystemClock.sleep(SETTLE_MS)
        shot("long-title")
    }

    /**
     * 左のメニューからライブへ (ここまでが README の動く絵) → 局送り → 選局の間 → メニュー → 局の列 → 番組の詳しく。
     * 選局の間 (1.5 秒たつと回るものが出る) を撮るときだけ、偽の denpa の選局を [SLOW_TUNE_MS] に延ばす。
     * ほかは速く映す (替えたときの局と番組の知らせは、頼んでから 4 秒で消える)
     */
    private fun live(denpa: FakeDenpa) {
        frame(1800)
        for (i in 0 until 6) {
            if ("設定" in texts()) break
            press(KeyEvent.KEYCODE_DPAD_LEFT)
            SystemClock.sleep(KEY_GAP_MS)
        }
        await("左でメニューが開きません") { "設定" in texts() }
        SystemClock.sleep(SETTLE_MS)
        shot("drawer")
        frame(1500)
        // メニューは「録画」に合って開く。下で「ライブ」へ、決定で映す
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        SystemClock.sleep(FOCUS_MS)
        frame(900)
        // 入ってすぐは局と番組とキーの手引き。選局の間は回るもの
        denpa.tuneMs = SLOW_TUNE_MS
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(BUSY_MS)
        frame(1500)
        awaitTuned(denpa)
        frame(1500)

        // 上で局の列 (いま映している局に合う)、右で隣の局、決定で替える (替える間は前の局の絵のまま回るもの)
        press(KeyEvent.KEYCODE_DPAD_UP)
        await("上で局の列が開きません") { "地上波" in texts() }
        SystemClock.sleep(FOCUS_MS)
        frame(1500)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        SystemClock.sleep(FOCUS_MS)
        frame(900)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(BUSY_MS)
        frame(1200)
        awaitTuned(denpa)
        frame(1200)

        // 右で次の局へ。押すとすぐ行き先の局と番組が出て、映ってからもしばらく出ている
        denpa.tuneMs = FAST_TUNE_MS
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        SystemClock.sleep(KEY_GAP_MS)
        frame(900)
        awaitTuned(denpa)
        shot("live")
        frame(1800)

        // もう一度右。選局に時間がかかっている間 (前の局の絵のまま回るもの)
        denpa.tuneMs = SLOW_TUNE_MS
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        SystemClock.sleep(BUSY_MS)
        shot("live-tuning")
        awaitTuned(denpa)
        denpa.tuneMs = FAST_TUNE_MS
        // 替えたときの局と番組の知らせが消えるのを待つ
        SystemClock.sleep(NOTICE_MS)

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        await("下でメニューが開きません") { "地上波" in texts() }
        SystemClock.sleep(SETTLE_MS)
        shot("live-menu")
        press(KeyEvent.KEYCODE_BACK)
        await("戻るでメニューが閉じません") { "地上波" !in texts() }

        press(KeyEvent.KEYCODE_DPAD_UP)
        await("上で局の列が開きません") { "地上波" in texts() }
        SystemClock.sleep(FOCUS_MS)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        SystemClock.sleep(SETTLE_MS)
        shot("live-channels")
        press(KeyEvent.KEYCODE_BACK)
        await("戻るで局の列が閉じません") { "地上波" !in texts() }

        longPress(KeyEvent.KEYCODE_DPAD_CENTER)
        await("決定の長押しで番組の詳しくが開きません") { windowTexts().let { "閉じる" in it && it.any { t -> "月曜九時のドラマ" in t } } }
        SystemClock.sleep(SETTLE_MS)
        shot("live-detail")
        press(KeyEvent.KEYCODE_BACK)
        // 映像だけのライブの窓には字が無いので、空でも閉じたとみなす
        await("戻るで番組の詳しくが閉じません") { "閉じる" !in windowTexts() }
        SystemClock.sleep(FOCUS_MS)
        // もう一度の戻るで、いちばん上のメニューへ (「ライブ」に合う)
        press(KeyEvent.KEYCODE_BACK)
        await("ライブから戻りません") { "設定" in texts() }
    }

    /** 設定。バージョンを押してアップデートを確かめた後 (「最新です」) */
    private fun settings() {
        click("設定")
        await("設定が出ません") { texts().any { it.startsWith("繋ぐ先") } }
        // メニューを閉じて右の画面へ。繋ぐ先 (押すと外れる) からバージョンへ下りて押す
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        SystemClock.sleep(FOCUS_MS)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        SystemClock.sleep(FOCUS_MS)
        click("バージョン", prefix = true)
        await("アップデートを確かめ終えません") { texts().none { it == "確認中…" || it == "押すとアップデートを確認" } }
        SystemClock.sleep(SETTLE_MS)
        shot("settings")
    }

    /** 録画の再生。字幕 (作り物の文字の配置。`FakeCaptions.showcase`)・下キーの操作の列・決定の長押しの詳しく・最後まで観たとき */
    private fun recording() {
        open("denpa://recording/${Showcase.CAPTION_ID}")
        await("字幕が出ません") { nodes().any { it.contentDescription?.contains("字幕を文字で描く") == true } }
        // 開いてすぐの番組名の知らせが消えるのを待つ (出ている間は字幕がその上へ逃げている)
        await("番組名の知らせが消えません") { texts().none { it.startsWith("下でシークバー") } }
        SystemClock.sleep(CAPTION_SETTLE_MS)
        shot("captions")

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        await("下で操作の列が開きません") { barOpen() }
        SystemClock.sleep(FOCUS_MS)
        shot("player-bar")
        press(KeyEvent.KEYCODE_BACK)
        await("戻るで操作の列が閉じません") { !barOpen() }

        longPress(KeyEvent.KEYCODE_DPAD_CENTER)
        await("決定の長押しで詳しくが開きません") { windowTexts().let { "閉じる" in it && it.any { t -> "荷物持ち" in t } } }
        SystemClock.sleep(SETTLE_MS)
        shot("player-detail")
        press(KeyEvent.KEYCODE_BACK)
        await("戻るで詳しくが閉じません") { windowTexts().let { it.isNotEmpty() && "閉じる" !in it } }

        // 止まっていれば動かして、最後まで観る (作り物の録画は 30 秒)
        if (texts().any { it.startsWith("一時停止  ") }) press(KeyEvent.KEYCODE_DPAD_CENTER)
        await("最後まで観ました が出ません", ENDED_WAIT_MS) { texts().any { it.startsWith("最後まで観ました") } }
        SystemClock.sleep(SETTLE_MS)
        shot("ended")
        press(KeyEvent.KEYCODE_BACK)
    }

    /** 追っかけ再生 (録っている最中の録画)。下キーの操作の列 */
    private fun chase() {
        open("denpa://recording/${Showcase.CHASE_ID}")
        SystemClock.sleep(SETTLE_MS)
        await("追っかけが始まりません") { texts().none { it == "読み込んでいます" || it == "映像を待っています" } }
        // 開いてすぐの番組名の知らせが消えるのを待つ
        SystemClock.sleep(NOTICE_MS)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        await("下で操作の列が開きません") { "最新" in texts() }
        SystemClock.sleep(FOCUS_MS)
        shot("chase")
        press(KeyEvent.KEYCODE_BACK)
        SystemClock.sleep(KEY_GAP_MS)
        press(KeyEvent.KEYCODE_BACK)
    }

    /**
     * 映るのを待つ。選局を延ばしているときは回るものが出ているので、それが消えるまで。速いときは回るものが出ないので、
     * 偽の denpa が答えて映りはじめるくらい待つ
     */
    private fun awaitTuned(denpa: FakeDenpa) {
        if (denpa.tuneMs >= SLOW_TUNE_MS) {
            await("映りません") { texts().none { it == "選局しています" || it == "映像を待っています" } }
        } else {
            SystemClock.sleep(FAST_SETTLE_MS)
        }
        SystemClock.sleep(FIRST_FRAME_MS)
    }

    /** 録画の帯 (操作の列) が開いている */
    private fun barOpen() = texts().any { it.startsWith("CM 飛ばし") }

    /**
     * `title` を含む録画のカードに合わせる。まだ組まれていなければ `key` で送ってから探し直す。
     * 合ったかは上の段 (合わせている録画の番組名) に同じ題が出たかで見る (Compose の入力の合いは木では古いまま返ることがある。SmokeTest)
     */
    private fun focusCard(title: String, key: Int) {
        val seen = mutableSetOf<String>()
        fun matching() = nodes().filter { n -> n.text?.toString()?.contains(title) == true }.also { list -> list.forEach { seen += it.text.toString() } }
        for (i in 0 until 16) {
            if (matching().size >= 2) return
            // カードの中の題から、合わせられるもの (カード) をたどる
            val card = matching().firstNotNullOfOrNull { generateSequence(it) { n -> n.parent }.firstOrNull { n -> n.isFocusable } }
            if (card != null && card.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                SystemClock.sleep(KEY_GAP_MS)
                if (matching().size >= 2) return
            }
            press(key)
            SystemClock.sleep(KEY_GAP_MS)
        }
        error("「$title」に合いません (見えた題: $seen): ${texts()}")
    }

    /** `label` の札に合わせて押す (合わせるのは、撮る絵で合っているように) */
    private fun click(label: String, prefix: Boolean = false) {
        val target = node { it.text?.toString()?.let { t -> if (prefix) t.startsWith(label) else t == label } == true }?.let(::clickable)
            ?: error("「$label」がありません: ${texts()}")
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        assertTrue("「$label」を押せません", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun open(link: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(APP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.context.startActivity(intent)
    }

    private fun shot(name: String) {
        shell("screencap -p $DIR/$name.png")
    }

    /** README の動く絵の1こま。`ms` はそのこまを見せる長さ (CI が名前から読む) */
    private fun frame(ms: Int) {
        frames++
        shot("navigation/%02d-%d".format(frames, ms))
    }

    private fun press(code: Int) {
        awaitAppFocus()
        val at = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(at, SystemClock.uptimeMillis(), action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            instrumentation.uiAutomation.injectInputEvent(event, true)
        }
    }

    /** 長押し: 押して、長押しの印つきの繰り返しを送ってから離す (SmokeTest と同じ) */
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

    /** キーを送る前に、アプリの窓 (ダイアログも) に入力の合いが来ているのを待つ (SmokeTest の awaitAppFocus) */
    private fun awaitAppFocus() {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val focused = Regex("mCurrentFocus=Window\\{\\S+ u\\d+ ([^ /}]+)").find(shell("dumpsys window"))?.groupValues?.get(1)
            if (focused == APP) return
            SystemClock.sleep(POLL_MS)
        }
    }

    private fun await(message: String, ms: Long = WAIT_MS, done: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < deadline) {
            if (done()) return
            SystemClock.sleep(POLL_MS)
        }
        assertTrue("$message: ${windowTexts()}", done())
    }

    /** 読むたびにアクセシビリティのキャッシュを捨てる (SmokeTest の freshAutomation と同じ) */
    private fun automation() = instrumentation.uiAutomation.also { automation ->
        val info = automation.serviceInfo
        if (info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
        } else {
            automation.clearCache()
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            out += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        automation().rootInActiveWindow?.takeIf { it.packageName?.toString() == APP }?.let(::walk)
        return out
    }

    private fun node(match: (AccessibilityNodeInfo) -> Boolean) = nodes().firstOrNull(match)

    private fun texts(): List<String> = nodes().mapNotNull { it.text?.toString() }

    /** アプリの窓 (ダイアログも) すべての文字 */
    private fun windowTexts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo) {
            node.text?.let { out += it.toString() }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        automation().windows.mapNotNull { it.root }.filter { it.packageName?.toString() == APP }.forEach(::walk)
        return out
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        generateSequence(node) { it.parent }.firstOrNull { it.isClickable }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private companion object {
        const val APP = "io.github.danything.denpatv"
        const val DIR = "/data/local/tmp/shots"
        val instrumentation get() = InstrumentationRegistry.getInstrumentation()

        /** 合わせてから撮るまで (上の段の絵を替える・ポスターを読む・膨らむのを待つ) */
        const val SETTLE_MS = 3_000L
        /** キーで合いを移してから撮るまで */
        const val FOCUS_MS = 1_000L
        /** 選局の間を撮るときの偽の denpa の選局の長さと、ふだんの長さ */
        const val SLOW_TUNE_MS = 4_500L
        const val FAST_TUNE_MS = 300L
        /**
         * 局を替えてから、回るものを撮るまで。左右の局送りは離して 0.5 秒たってから頼み、頼んで 1.5 秒たつと回るものが出る。
         * 偽の denpa が答える ([SLOW_TUNE_MS]) より前
         */
        const val BUSY_MS = 2_800L
        /** 速く映すとき、局を替えてから映りはじめるまで (離して 0.5 秒 + 選局 + 最初のこま) */
        const val FAST_SETTLE_MS = 1_200L
        /** 回るものが消えてから、映像のこまが出るまで */
        const val FIRST_FRAME_MS = 500L
        /** 局を替えたときの局と番組の知らせが消えるまで */
        const val NOTICE_MS = 5_000L
        /** 知らせが消えてから撮るまで (字幕が下りきる) */
        const val CAPTION_SETTLE_MS = 1_000L
        const val KEY_GAP_MS = 400L
        const val LONG_PRESS_MS = 700L

        /** 測るときに続けて送る数と間 (リモコンを続けて押す速さ) */
        const val MOVES = 10
        const val MOVE_GAP_MS = 250L
        const val WAIT_MS = 30_000L
        /** 作り物の録画 (30 秒) を最後まで観るまで */
        const val ENDED_WAIT_MS = 60_000L
        const val POLL_MS = 500L
    }
}
