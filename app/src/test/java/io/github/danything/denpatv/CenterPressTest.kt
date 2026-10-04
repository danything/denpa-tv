package io.github.danything.denpatv

import io.github.danything.denpatv.data.CenterPress
import io.github.danything.denpatv.data.CenterPress.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CenterPressTest {
    @Test
    fun 短く押して離すと短押し() {
        val press = CenterPress()
        assertNull(press.down(0, false))
        assertEquals(Action.Short, press.up())
    }

    /** 押し続けると repeat が何度も来る。長押しは1度だけで、離しても短押しにしない */
    @Test
    fun 押し続けると長押しを1度だけ() {
        val press = CenterPress()
        assertNull(press.down(0, false))
        assertEquals(Action.Long, press.down(1, true))
        assertNull(press.down(2, false))
        assertNull(press.down(3, false))
        assertNull(press.up())
        // 次は普通に短押し
        assertNull(press.down(0, false))
        assertEquals(Action.Short, press.up())
    }

    @Test
    fun 長押しの印だけでも長押し() {
        val press = CenterPress()
        press.down(0, false)
        assertEquals(Action.Long, press.down(0, true))
    }

    /** 一覧で押した決定の離しが再生の画面に届いても、何もしない */
    @Test
    fun 押したのを見ていない離しは捨てる() {
        val press = CenterPress()
        assertNull(press.up())
        assertNull(press.down(5, true))
        press.down(0, false)
        press.reset()
        assertNull(press.up())
    }
}
