package io.github.danything.denpatv

import io.github.danything.denpatv.data.DenpaVersion
import io.github.danything.denpatv.data.MIN_DENPA
import io.github.danything.denpatv.data.denpaTooOld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DenpaVersionTest {
    @Test
    fun タグを読む() {
        assertEquals(DenpaVersion(1, 44, 0), DenpaVersion.parse("v1.44.0"))
        // 昔のタグには v が無いものもある (1.23.1)
        assertEquals(DenpaVersion(1, 23, 1), DenpaVersion.parse("1.23.1"))
        assertEquals(DenpaVersion(2, 0, 0), DenpaVersion.parse("v2.0.0-rc.1"))
        assertNull(DenpaVersion.parse("dev"))
        assertNull(DenpaVersion.parse("main"))
        assertNull(DenpaVersion.parse("v1.44"))
    }

    @Test
    fun 数で比べる() {
        assertTrue(DenpaVersion(1, 10, 0) > DenpaVersion(1, 9, 9))
        assertTrue(DenpaVersion(2, 0, 0) > DenpaVersion(1, 99, 99))
        assertTrue(DenpaVersion(1, 40, 1) > DenpaVersion(1, 40, 0))
        assertEquals("v1.50.0", MIN_DENPA.toString())
    }

    @Test
    fun 古すぎれば要る版と今の版を言う() {
        assertEquals("denpa v1.50.0 以上が要ります (いまは v1.49.0)", denpaTooOld("v1.49.0"))
        assertEquals("denpa v1.50.0 以上が要ります (いまは v1.23.1)", denpaTooOld("1.23.1"))
        // 版を返さないとても古い denpa
        assertEquals("denpa v1.50.0 以上が要ります", denpaTooOld(null))
        assertNull(denpaTooOld("v1.50.0"))
        assertNull(denpaTooOld("v1.51.2"))
        assertNull(denpaTooOld("v2.0.0"))
        // 手元・develop の dev や読めない札は分からないので言わない
        assertNull(denpaTooOld("dev"))
        assertNull(denpaTooOld("something"))
    }
}
