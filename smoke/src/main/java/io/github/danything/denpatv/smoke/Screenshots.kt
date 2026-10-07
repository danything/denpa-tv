package io.github.danything.denpatv.smoke

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
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
 * **画面の絵を撮る** (README・PR に貼る絵)。CI の Screenshots の流れ (`.github/workflows/screenshots.yml`) だけが
 * `-e shots 1` を付けて走らせる。付けなければ何もしない (smoke では飛ばす)。
 *
 * 作り物の録画 (`Showcase`) を返す偽の denpa に繋ぎ、録画の一覧 (下の端・頭・長い題)・開いたメニュー・設定を
 * `/data/local/tmp/shots/<名前>.png` に撮る (CI が adb pull で取ってくる)。終わりに一覧を上下に送り、こまの描き時間を測れるようにする
 * (前と後の見比べ用。読むのは scripts/shots-run.sh)
 */
class Screenshots {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(600)

    @Test
    fun shoot() {
        assumeTrue("撮るときだけ (-e shots 1)", InstrumentationRegistry.getArguments().getString("shots") != null)
        FakeDenpa(instrumentation.context.assets, showcase = true).use { denpa ->
            shell("pm clear $APP")
            shell("rm -rf $DIR")
            shell("mkdir -p $DIR")
            val launch = (instrumentation.context.packageManager.getLeanbackLaunchIntentForPackage(APP) ?: error("$APP が入っていません"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            instrumentation.context.startActivity(launch)
            await("繋ぐ画面が出ません") { "繋ぐ" in texts() }
            val field = node { it.isEditable } ?: error("URL を入れる欄がありません: ${texts()}")
            val text = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, denpa.url) }
            assertTrue("URL を入れられません", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text))
            val connect = node { it.text?.toString() == "繋ぐ" }?.let(::clickable) ?: error("「繋ぐ」がありません")
            assertTrue("「繋ぐ」を押せません", connect.performAction(AccessibilityNodeInfo.ACTION_CLICK))

            // 開くと一番下 (いちばん古い録画) に合う
            await("録画の一覧が出ません") { texts().any { "名城" in it } }
            SystemClock.sleep(SETTLE_MS)
            shot("recordings-bottom")

            // 一覧の頭の段 (焼いている最中の録画)
            focusCard("まち歩き紀行", KeyEvent.KEYCODE_DPAD_UP)
            SystemClock.sleep(SETTLE_MS)
            shot("recordings")

            // 長い題 (話数を残して途中を切る)。行の左の端なので、続けて左でメニューを開ける
            focusCard("凶乱令嬢", KeyEvent.KEYCODE_DPAD_DOWN)
            SystemClock.sleep(SETTLE_MS)
            shot("long-title")

            for (i in 0 until 6) {
                if ("設定" in texts()) break
                press(KeyEvent.KEYCODE_DPAD_LEFT)
                SystemClock.sleep(KEY_GAP_MS)
            }
            await("左でメニューが開きません") { "設定" in texts() }
            SystemClock.sleep(SETTLE_MS)
            shot("drawer")

            val settings = node { it.text?.toString() == "設定" }?.let(::clickable) ?: error("「設定」がありません: ${texts()}")
            assertTrue("「設定」を押せません", settings.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("設定が出ません") { texts().any { it.startsWith("繋ぐ先") } }
            // メニューを閉じて右の画面へ
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            SystemClock.sleep(SETTLE_MS)
            shot("settings")

            // 軽さ: 一覧に戻り、上下に送るあいだのこまを数える (読むのは scripts/shots-run.sh の dumpsys gfxinfo)
            for (i in 0 until 6) {
                if ("録画" in texts()) break
                press(KeyEvent.KEYCODE_DPAD_LEFT)
                SystemClock.sleep(KEY_GAP_MS)
            }
            val recordings = node { it.text?.toString() == "録画" }?.let(::clickable) ?: error("「録画」がありません: ${texts()}")
            assertTrue("「録画」を押せません", recordings.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("録画の一覧が出ません") { texts().any { "名城" in it } }
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
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

    /** `title` を含む録画のカードに合わせる。まだ組まれていなければ `key` で送ってから探し直す */
    private fun focusCard(title: String, key: Int) {
        for (i in 0 until 12) {
            // 上の段の番組名にも同じ題があるので、合わせられるもの (カード) の中のものを探す
            val card = nodes().filter { n -> n.text?.toString()?.contains(title) == true }
                .firstNotNullOfOrNull { generateSequence(it) { n -> n.parent }.firstOrNull { n -> n.isFocusable } }
            // 合ったかは木では確かめない (Compose の入力の合いは古いまま返ることがある。SmokeTest)
            if (card != null && card.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return
            press(key)
            SystemClock.sleep(KEY_GAP_MS)
        }
        error("「$title」が見つかりません: ${texts()}")
    }

    private fun shot(name: String) {
        shell("screencap -p $DIR/$name.png")
    }

    private fun press(code: Int) {
        val at = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(at, SystemClock.uptimeMillis(), action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            instrumentation.uiAutomation.injectInputEvent(event, true)
        }
    }

    private fun await(message: String, done: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (done()) return
            SystemClock.sleep(POLL_MS)
        }
        assertTrue("$message: ${texts()}", done())
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
        const val KEY_GAP_MS = 400L

        /** 測るときに続けて送る数と間 (リモコンを続けて押す速さ) */
        const val MOVES = 10
        const val MOVE_GAP_MS = 250L
        const val WAIT_MS = 30_000L
        const val POLL_MS = 500L
    }
}
