package com.dreamdisplays.media.player.process

import com.dreamdisplays.api.media.source.LiveStreamUrls
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
        val graph = a.valueOf("-filter_complex")!!
        assertTrue(graph.startsWith("ddagrab=output_idx=1:framerate=30:draw_mouse=1,hwdownload,format=bgra,scale="), graph)
        assertTrue(graph.endsWith("format=nv12[v]"), graph)
        assertEquals("[v]", a.valueOf("-map"))
        assertFalse("-i" in a, "ddagrab as an -i lavfi input ignores the chosen adapter")
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
        assertTrue(a.indexOf("-init_hw_device") < a.indexOf("-filter_complex"))
        assertTrue(a.valueOf("-filter_complex")!!.startsWith("ddagrab=output_idx=0:framerate=30:draw_mouse=1,"))
        val byVendor = screen.copy(vendorId = 0x1002, byVendor = true)
        assertEquals("d3d11va=dd:,vendor_id=0x1002",
            ScreenCapture.args("FFMPEG", Source.DDAGRAB, byVendor, Encoder.AMF, publish).valueOf("-init_hw_device"))
        assertEquals("0", a.valueOf("-omit_video_pes_length"))
        assertEquals("Screen 2 1920x1080", ScreenCapture.label(screen))
        assertFalse("-init_hw_device" in ScreenCapture.args("FFMPEG", Source.DDAGRAB, monitor, Encoder.AMF, publish))
        assertFalse("-omit_video_pes_length" in ScreenCapture.args("FFMPEG", Source.DDAGRAB, monitor, Encoder.AMF, "rtmp://h/a"))
    }

    @Test
    fun `an rtmps template with credentials in the query publishes flv to exactly that url`() {
        val template = "rtmps://live.example.org:443/{id}?user=publish&pass=s3cr3t"
        val url = LiveStreamUrls.fromTemplate(template, "ddabc123")
        assertEquals("rtmps://live.example.org:443/ddabc123?user=publish&pass=s3cr3t", url)
        val a = ScreenCapture.args("FFMPEG", Source.DDAGRAB, monitor, Encoder.X264, url)
        assertEquals("flv", a[a.size - 2])
        assertEquals(url, a.last(), "TLS, SNI host, port and the query reach FFmpeg untouched")
        assertFalse("-omit_video_pes_length" in a)
        // The same share on plain RTMP is a config change only
        val plain = LiveStreamUrls.fromTemplate("rtmp://live.example.org:1935/{id}?user=publish&pass=s3cr3t", "ddabc123")
        assertEquals(plain, ScreenCapture.args("FFMPEG", Source.DDAGRAB, monitor, Encoder.X264, plain).last())
        assertEquals(
            "rtmps://live.example.org:443/ddabc123?user=<redacted>&pass=<redacted>",
            LiveStreamUrls.redactCredentials(url),
        )
    }

    @Test
    fun `software fallback is x264 ultrafast zerolatency at a constant bitrate`() {
        val a = ScreenCapture.args("FFMPEG", Source.DDAGRAB, monitor, Encoder.X264, publish)
        assertEquals("libx264", a.valueOf("-c:v"))
        assertEquals("ultrafast", a.valueOf("-preset"))
        assertEquals("zerolatency", a.valueOf("-tune"))
        assertEquals("4000k", a.valueOf("-b:v"))
        assertEquals("4000k", a.valueOf("-maxrate"))
        assertTrue(a.valueOf("-filter_complex")!!.endsWith("format=yuv420p[v]"))
    }

    @Test
    fun `the picture is held to 1080p`() {
        val vf = ScreenCapture.args("FFMPEG", Source.DDAGRAB, monitor, Encoder.X264, publish).valueOf("-filter_complex")!!
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
    fun `gdi capture copies the screen's desktop rectangle`() {
        val left = ScreenCapture.Monitor(0, "Screen 1", -1920, 0, 1920, 1080, adapter = 1, deviceName = "\\\\.\\DISPLAY2")
        val a = ScreenCapture.args("FFMPEG", Source.GDIGRAB, left, Encoder.X264, publish)
        assertEquals("gdigrab", a.valueOf("-f"))
        assertEquals("-1920", a.valueOf("-offset_x"))
        assertEquals("0", a.valueOf("-offset_y"))
        assertEquals("1920x1080", a.valueOf("-video_size"))
        assertEquals("desktop", a.valueOf("-i"))
        assertFalse("-init_hw_device" in a)
    }

    @Test
    fun `server limits lower the frame rate, the picture and the bitrate`() {
        val s = ScreenCapture.Settings().limitedTo(maxFps = 15, maxHeight = 720)
        assertEquals(15, s.fps)
        assertEquals(720, s.maxHeight)
        assertEquals(1280, s.maxWidth)
        assertTrue(s.bitrateKbps in 500..2_000, "${s.bitrateKbps}")
        assertEquals(ScreenCapture.Settings(), ScreenCapture.Settings().limitedTo(0, 0))
        assertEquals(30, ScreenCapture.Settings().limitedTo(60, 2160).fps)
    }

    @Test
    fun `the safe plan drops to x264, and to gdi unless only the encoder failed`() {
        val screen = ScreenCapture.Monitor(0, "Screen 1", 0, 0, 1920, 1080, adapter = 0, deviceName = "\\\\.\\DISPLAY6")
        val amf = ScreenCapture.Plan(Source.DDAGRAB, Encoder.AMF)
        assertEquals(ScreenCapture.Plan(Source.DDAGRAB, Encoder.X264), ScreenCapture.safePlan(amf, screen, encoderFailed = true))
        assertEquals(ScreenCapture.Plan(Source.GDIGRAB, Encoder.X264), ScreenCapture.safePlan(amf, screen, encoderFailed = false))
        assertEquals(ScreenCapture.Plan(Source.GDIGRAB, Encoder.X264),
            ScreenCapture.safePlan(ScreenCapture.Plan(Source.DDAGRAB, Encoder.X264), screen, encoderFailed = false))
        assertEquals(null, ScreenCapture.safePlan(ScreenCapture.Plan(Source.GDIGRAB, Encoder.X264), screen, encoderFailed = false))
    }

    @Test
    fun `encoder open failures are told apart from capture failures`() {
        assertTrue(ScreenCapture.isEncoderOpenFailure("[h264_amf @ 0x1] Failed to create derived AMF device context: No such device"))
        assertTrue(ScreenCapture.isEncoderOpenFailure("[h264_nvenc @ 0x1] Driver does not support the required nvenc API version. Required: 13.1 Found: 13.0"))
        assertTrue(ScreenCapture.isEncoderOpenFailure("[vost#0:0/h264_qsv] [enc:h264_qsv] Error while opening encoder - maybe incorrect parameters"))
        assertFalse(ScreenCapture.isEncoderOpenFailure("[Parsed_ddagrab_0 @ 0x1] Failed to enumerate DXGI output 1"))
        assertFalse(ScreenCapture.isEncoderOpenFailure("[tcp @ 0x1] Connection to tcp://relay:1935 failed: Connection refused"))
    }

    @Test
    fun `screens are named by their place on the desktop`() {
        fun m(x: Int, dev: String) = ScreenCapture.Monitor(0, "", x, 0, 1920, 1080, adapter = 0, deviceName = dev)
        val named = ScreenCapture.named([m(1920, "B"), m(0, "A"), m(-1920, "C")])
        assertEquals(["C", "A", "B"], named.map { it.deviceName })
        assertEquals(["Screen 1 (left)", "Screen 2 (middle, main)", "Screen 3 (right)"], named.map { it.name })
        assertEquals("Screen 2 (middle, main) 1920x1080", ScreenCapture.label(named[1]))
        assertEquals(["Screen 1"], ScreenCapture.named([m(0, "A")]).map { it.name })
    }

    @Test
    fun `a monitor label stays short`() {
        assertEquals("2: DELL U2720Q 3840x2160", ScreenCapture.label(monitor))
        assertEquals("1: Generic PnP Mon… 1920x1080", ScreenCapture.label(ScreenCapture.Monitor(0, "Generic PnP Monitor", 0, 0, 1920, 1080)))
    }
}
