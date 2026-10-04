package io.github.danything.denpatv

import io.github.danything.denpatv.data.Decoders
import io.github.danything.denpatv.data.RecordingFile
import io.github.danything.denpatv.data.liveCodec
import io.github.danything.denpatv.data.pickFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecsTest {
    private val files = listOf(
        RecordingFile("encoded", "av1", "api/recordings/1/file?source=encoded"),
        RecordingFile("alt", "h264", "api/recordings/1/file?source=alt"),
        RecordingFile("ts", "mpeg2", "api/recordings/1/file?source=ts"),
    )

    @Test
    fun 解ける中で軽いものを選ぶ() {
        assertEquals("av1", pickFile(files, Decoders(av1 = true, h264 = true, mpeg2 = true))?.codec)
        assertEquals("h264", pickFile(files, Decoders(av1 = false, h264 = true, mpeg2 = true))?.codec)
        assertEquals("mpeg2", pickFile(files.drop(1).takeLast(1), Decoders(av1 = true, h264 = true, mpeg2 = true))?.codec)
    }

    @Test
    fun 解けるものが無ければ_null() {
        assertNull(pickFile(files.take(1), Decoders(av1 = false, h264 = true, mpeg2 = true)))
    }

    @Test
    fun ライブは_AV1_を解ければ_AV1() {
        assertEquals("av1", liveCodec(Decoders(av1 = true, h264 = true, mpeg2 = false)))
        assertEquals("h264", liveCodec(Decoders(av1 = false, h264 = true, mpeg2 = false)))
    }
}
