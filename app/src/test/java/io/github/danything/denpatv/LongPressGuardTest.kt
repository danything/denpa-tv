package io.github.danything.denpatv

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_ENTER
import io.github.danything.denpatv.data.LongPressGuard
import org.junit.Assert.assertEquals
import org.junit.Test

class LongPressGuardTest {
    private fun LongPressGuard.run(vararg events: Pair<Int, Int>, code: Int = KEYCODE_DPAD_CENTER) =
        events.map { (action, repeat) -> deliver(action, code, repeat) }

    /**
     * 本物のリモコンで長押しして帯 (詳しく) が開いたあと: 繰り返しの DOWN と離しの UP が開いたものに来る。
     * どれも届けない (帯の札・詳しくの「再生」が押されない)。押し直したら届く
     */
    @Test
    fun 長押しの続きと離しは開いたものに届けない() {
        val guard = LongPressGuard()
        assertEquals(listOf(false, false, false, false), guard.run(ACTION_DOWN to 2, ACTION_DOWN to 3, ACTION_DOWN to 4, ACTION_UP to 0))
        assertEquals(listOf(true, true), guard.run(ACTION_DOWN to 0, ACTION_UP to 0))
        // 押し直したあとは、長押しも届く (帯の中で長押ししたら札の長押し)
        assertEquals(listOf(true, true, true), guard.run(ACTION_DOWN to 0, ACTION_DOWN to 1, ACTION_UP to 0))
    }

    /** ENTER を送るリモコンも同じ。ほかのキー (左右で札を選ぶ) はすぐ届ける */
    @Test
    fun ENTERも同じでほかのキーはすぐ届ける() {
        assertEquals(listOf(false, false), LongPressGuard().run(ACTION_DOWN to 5, ACTION_UP to 0, code = KEYCODE_ENTER))
        assertEquals(listOf(true, true), LongPressGuard().run(ACTION_DOWN to 0, ACTION_UP to 0, code = KEYCODE_DPAD_RIGHT))
    }
}
