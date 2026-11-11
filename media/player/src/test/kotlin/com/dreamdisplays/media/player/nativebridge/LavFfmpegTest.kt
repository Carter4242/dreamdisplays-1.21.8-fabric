package com.dreamdisplays.media.player.nativebridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LavFfmpegTest {
    /** `bin/` of FFmpeg-Builds' win64 lgpl-shared 9.0 and 8.1 archives (2026-11-11). */
    private val win90 = listOf("swresample-7.dll", "swscale-10.dll", "avutil-61.dll", "avfilter-12.dll", "avcodec-63.dll", "avformat-63.dll", "avdevice-63.dll")
    private val win81 = listOf("swresample-6.dll", "avfilter-11.dll", "avutil-60.dll", "avformat-62.dll", "swscale-9.dll", "avdevice-62.dll", "avcodec-62.dll")

    @Test
    fun `the 9 set is installed on every platform's naming`() {
        assertTrue(LavFfmpeg.installed(win90 + "dreamdisplays_lav.dll"))
        val linux = listOf("libavutil.so.61.1.102", "libavcodec.so.63.1.102", "libavformat.so.63.1.102", "libswscale.so.10.1.102", "libswresample.so.7.1.102")
        assertTrue(LavFfmpeg.installed(linux))
        assertTrue(LavFfmpeg.installed(listOf("libavutil.61.dylib", "libavcodec.63.dylib", "libavformat.63.dylib", "libswscale.10.dylib", "libswresample.7.dylib")))
    }

    @Test
    fun `an older FFmpeg's set left by a previous version is not installed`() {
        assertFalse(LavFfmpeg.installed(win81 + "dreamdisplays_lav.dll"))
        assertTrue(LavFfmpeg.installed(win81 + win90))
    }

    @Test
    fun `a partial unpack is not installed`() {
        for (missing in win90.filter { "avfilter" !in it && "avdevice" !in it }) {
            assertFalse(LavFfmpeg.installed(win90 - missing), "installed without $missing")
        }
        assertTrue(LavFfmpeg.installed(win90 - "avfilter-12.dll" - "avdevice-63.dll"))
    }

    @Test
    fun `library majors parse and other files do not`() {
        assertEquals(63, LavFfmpeg.libraryMajor("avcodec-63.dll", "avcodec"))
        assertEquals(63, LavFfmpeg.libraryMajor("libavcodec.so.63", "avcodec"))
        assertEquals(7, LavFfmpeg.libraryMajor("libswresample.so.7.1.102", "swresample"))
        assertNull(LavFfmpeg.libraryMajor("avcodec-63.dll.part", "avcodec"))
        assertNull(LavFfmpeg.libraryMajor("libavcodec.so", "avcodec"))
        assertNull(LavFfmpeg.libraryMajor("avformat-63.dll", "avcodec"))
    }
}
