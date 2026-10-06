package io.github.danything.denpatv

import io.github.danything.denpatv.data.boxBlur
import io.github.danything.denpatv.data.cacheBytes
import io.github.danything.denpatv.data.decodeScale
import io.github.danything.denpatv.data.sampleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** 2 の冪で縮めたあと、出す大きさを覆うところまでさらに縮める (下回らない) */
    @Test
    fun 出す大きさちょうどに縮める() {
        // 640x360 のポスターを 370x208 のカードに: 約 0.58 倍
        val (from, to) = decodeScale(640, 360, 370, 208)!!
        val width = 640 * to / from
        val height = 360 * to / from
        assertTrue("$width x $height", width in 370..372 && height >= 208)
        // 1 割も大きくない・もう小さいなら縮めない
        assertNull(decodeScale(400, 225, 370, 208))
        assertNull(decodeScale(64, 36, 370, 208))
    }

    /** 覚えておく量はメモリの級の 1/8 (8〜64MB) */
    @Test
    fun 覚えておく量() {
        assertEquals(24 * 1024 * 1024, cacheBytes(192))
        assertEquals(8 * 1024 * 1024, cacheBytes(32))
        assertEquals(64 * 1024 * 1024, cacheBytes(1024))
    }

    /** 箱のぼかしは、1点だけ明るい絵を周りへ広げ、色の和をほぼ保つ */
    @Test
    fun 箱でぼかす() {
        val width = 9
        val height = 9
        val pixels = IntArray(width * height) { 0xFF000000.toInt() }
        pixels[4 * width + 4] = 0xFFFFFFFF.toInt()
        boxBlur(pixels, width, height, 1)
        val center = pixels[4 * width + 4] and 0xFF
        val near = pixels[3 * width + 3] and 0xFF
        assertTrue("$center $near", center in 20..40 && near in 20..40)
        assertEquals(0, pixels[0] and 0xFF)
        assertEquals(0xFF, pixels[0] ushr 24)
    }
}
