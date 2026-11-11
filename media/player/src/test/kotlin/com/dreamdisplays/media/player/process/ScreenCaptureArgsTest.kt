package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.player.process.ScreenCapture.Encoder
import com.dreamdisplays.media.player.process.ScreenCapture.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenCaptureArgsTest {
    private val monitor = ScreenCapture.Monitor(1, "DELL U2720Q", 2560, 0, 3840, 2160)
    private val publish = "srt://relay.example.com:8890?streamid=publish:ddabc"

    private fun List<String>.valueOf(flag: String): String? =
        indexOf(flag).takeIf { it >= 0 && it + 1 < size }?.let { this[it + 1] }

    @Test
    fun `windows captures the monitor with desktop duplication and publishes mpegts over srt`() {
        val a = ScreenCapture.args("FFMPEG", Source.DDAGRAB, monitor, Encoder.NVENC, publish)
        assertEquals("lavfi", a.valueOf("-f"))
        assertEquals("ddagrab=output_idx=1:framerate=30:draw_mouse=1", a.valueOf("-i"))
        assertTrue(a.valueOf("-vf")!!.startsWith("hwdownload,format=bgra,scale="))
        assertTrue(a.valueOf("-vf")!!.endsWith("format=nv12"))
        assertEquals("h264_nvenc", a.valueOf("-c:v"))
        assertEquals("ull", a.valueOf("-tune"))
        assertEquals("0", a.valueOf("-bf"))
        assertEquals("30", a.valueOf("-g"))
        assertTrue("-an" in a)
        assertEquals("mpegts", a[a.size - 2])
        assertEquals("$publish&latency=60000&transtype=live&pkt_size=1316", a.last())
    }

    @Test
    fun `a screen on another graphics adapter names it, mpegts sends each frame whole at once`() {
        val screen = ScreenCapture.Monitor(0, "Screen 2", 0, 0, 1920, 1080, adapter = 1)
        val a = ScreenCapture.args("FFMPEG", Source.DDAGRAB, screen, Encoder.AMF, publish)
        assertEquals("d3d11va=dd:1", a.valueOf("-init_hw_device"))
        assertEquals("dd", a.valueOf("-filter_hw_device"))
        assertTrue(a.indexOf("-init_hw_device") < a.indexOf("-i"))
        assertEquals("ddagrab=output_idx=0:framerate=30:draw_mouse=1", a.valueOf("-i"))
        assertEquals("0", a.valueOf("-omit_video_pes_length"))
        assertEquals("Screen 2 1920x1080", ScreenCapture.label(screen))
        assertFalse("-init_hw_device" in ScreenCapture.args("FFMPEG", Source.DDAGRAB, monitor, Encoder.AMF, publish))
        assertFalse("-omit_video_pes_length" in ScreenCapture.args("FFMPEG", Source.DDAGRAB, monitor, Encoder.AMF, "rtmp://h/a"))
    }

    @Test
    fun `software fallback is x264 ultrafast zerolatency at a constant bitrate`() {
        val a = ScreenCapture.args("FFMPEG", Source.DDAGRAB, monitor, Encoder.X264, publish)
        assertEquals("libx264", a.valueOf("-c:v"))
        assertEquals("ultrafast", a.valueOf("-preset"))
        assertEquals("zerolatency", a.valueOf("-tune"))
        assertEquals("4000k", a.valueOf("-b:v"))
        assertEquals("4000k", a.valueOf("-maxrate"))
        assertTrue(a.valueOf("-vf")!!.endsWith("format=yuv420p"))
    }

    @Test
    fun `the picture is held to 1080p`() {
        val vf = ScreenCapture.args("FFMPEG", Source.DDAGRAB, monitor, Encoder.X264, publish).valueOf("-vf")!!
        assertTrue("min(1920,iw)" in vf && "min(1080,ih)" in vf && "force_original_aspect_ratio=decrease" in vf, vf)
    }

    @Test
    fun `rtmp publishes flv and keeps its url`() {
        val a = ScreenCapture.args("FFMPEG", Source.DDAGRAB, monitor, Encoder.X264, "rtmp://relay.example.com/live/ddabc")
        assertEquals("flv", a[a.size - 2])
        assertEquals("rtmp://relay.example.com/live/ddabc", a.last())
    }

    @Test
    fun `mac and linux capture sources address the monitor`() {
        val mac = ScreenCapture.args("FFMPEG", Source.AVFOUNDATION, monitor, Encoder.VIDEOTOOLBOX, publish)
        assertEquals("Capture screen 1:none", mac.valueOf("-i"))
        assertEquals("1", mac.valueOf("-realtime"))
        val x11 = ScreenCapture.args("FFMPEG", Source.X11GRAB, monitor, Encoder.X264, publish)
        assertEquals("3840x2160", x11.valueOf("-video_size"))
        assertTrue(x11.valueOf("-i")!!.endsWith("+2560,0"))
    }

    @Test
    fun `every encoder runs at a constant bitrate with no lookahead options that older builds lack`() {
        for (e in Encoder.entries) {
            val a = ScreenCapture.encoderArgs(e, ScreenCapture.Settings())
            assertEquals(e.ffmpegName, a.valueOf("-c:v"))
            assertEquals("4000k", a.valueOf("-maxrate"))
            assertFalse("-look_ahead" in a)
        }
    }

    @Test
    fun `a monitor label stays short`() {
        assertEquals("2: DELL U2720Q 3840x2160", ScreenCapture.label(monitor))
        assertEquals("1: Generic PnP Mon… 1920x1080", ScreenCapture.label(ScreenCapture.Monitor(0, "Generic PnP Monitor", 0, 0, 1920, 1080)))
    }
}
