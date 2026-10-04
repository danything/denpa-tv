package io.github.danything.denpatv

import io.github.danything.denpatv.data.sampleSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ImagesTest {
    /** 出す大きさを下回らない範囲で 2 の冪に縮める */
    @Test
    fun 出す大きさに合わせて縮める() {
        // 1920x1080 のポスターを 560x315 に出すなら 1/2 (1/4 だと 480x270 で足りない)
        assertEquals(2, sampleSize(1920, 1080, 560, 315))
        assertEquals(4, sampleSize(1920, 1080, 400, 225))
        // もともと小さいロゴは縮めない
        assertEquals(1, sampleSize(64, 36, 352, 112))
        assertEquals(1, sampleSize(0, 0, 100, 100))
    }
}
