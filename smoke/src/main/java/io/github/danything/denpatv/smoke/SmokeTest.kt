package io.github.danything.denpatv.smoke

import android.app.Instrumentation
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
import org.junit.Test
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
    @Test
    fun live() = watching {
        open("denpa://live/${FakeDenpa.SERVICE_ID}")
        awaitVideo(LIVE_COLOR)
        assertRequested("GET /api/services/${FakeDenpa.SERVICE_ID}/live")
    }

    @Test
    fun recording() = watching {
        open("denpa://recording/${FakeDenpa.RECORDING_ID}")
        awaitVideo(RECORDING_COLOR)
        assertRequested("GET /api/recordings/${FakeDenpa.RECORDING_ID}/file")
    }

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

    private fun assertRequested(request: String) =
        assertTrue("$request が来ていません: ${denpa.requests.distinct()}", request in denpa.requests)

    private fun press(code: Int) {
        val at = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(at, SystemClock.uptimeMillis(), action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            instrumentation.uiAutomation.injectInputEvent(event, true)
        }
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
            val launch = instrumentation.context.packageManager.getLeanbackLaunchIntentForPackage(APP)
                ?: error("$APP が入っていません")
            instrumentation.context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitText { it == "繋ぐ" }
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

        private fun activeWindow(): AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow

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

        /** いちばん前のアプリの窓の文字 */
        private fun texts(): List<String> = nodes().mapNotNull { it.text?.toString() }

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
        private const val VIDEO_SHARE = 0.3f
        private const val GRID_X = 32
        private const val GRID_Y = 18

        // CI のエミュレータ (KVM はあるが GPU は無い) は遅いので長めに待つ
        private const val VIDEO_TIMEOUT_MS = 60_000L
        private const val TEXT_TIMEOUT_MS = 30_000L
        private const val SETTLE_MS = 3_000L
        private const val POLL_MS = 500L
        private const val MENU_TRIES = 4
        private const val MENU_WAIT_MS = 3_000L
    }
}
