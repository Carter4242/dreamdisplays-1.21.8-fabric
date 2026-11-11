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
    fun `relay failures are told apart from capture and encoder failures`() {
        val refused = ScreenCapture.RelayFailure.REFUSED
        val unreachable = ScreenCapture.RelayFailure.UNREACHABLE
        // MediaMTX rejecting a publish key answers the RTMP publish with an error status
        assertEquals(refused, ScreenCapture.relayFailure("[rtmp @ 0x1] Server error: Authentication failed"))
        assertEquals(refused, ScreenCapture.relayFailure("[https @ 0x1] HTTP error 401 Unauthorized"))
        assertEquals(refused, ScreenCapture.relayFailure("[srt @ 0x1] Connection setup failure: connection rejected"))
        assertEquals(unreachable, ScreenCapture.relayFailure("[tcp @ 0x1] Connection to tcp://relay:1935 failed: Connection refused"))
        assertEquals(unreachable, ScreenCapture.relayFailure("[tls @ 0x1] Error in the pull function.\n<relay-url>: Input/output error"))
        assertEquals(unreachable, ScreenCapture.relayFailure("[out#0/flv @ 0x1] Error opening output <relay-url>: Connection timed out"))
        // Not the relay: a different plan may well work
        assertEquals(null, ScreenCapture.relayFailure("[Parsed_ddagrab_0 @ 0x1] DuplicateOutput failed: 80070005\nError opening output files: Input/output error"))
        assertEquals(null, ScreenCapture.relayFailure("[h264_amf @ 0x1] Failed to create derived AMF device context: No such device"))
        assertEquals(null, ScreenCapture.relayFailure("Conversion failed!"))
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

    private val window = ScreenCapture.Window(hwnd = 0x2A0F3CL, pid = 900, app = "Google Chrome", title = "Video - YouTube")
    private val base = 1_762_862_345_123_456L
    private val pipe = ShareAudio.Input.Pipe("\\\\.\\pipe\\dreamdisplays-share-x", ShareAudio.Format(48_000, 2, float = false))

    private fun List<String>.valuesOf(flag: String): List<String> =
        indices.filter { this[it] == flag && it + 1 < size }.map { this[it + 1] }

    @Test
    fun `a window is captured with windows graphics capture, scaled into its first size, keyframes by time`() {
        val a = ScreenCapture.args("FFMPEG", Source.GFXCAPTURE, window, Encoder.NVENC, "rtmp://h/live/x",
            windowCapture = ScreenCapture.WindowCapture(Source.GFXCAPTURE, dupFrames = true))
        val graph = a.valueOf("-filter_complex")!!
        assertTrue(graph.startsWith("gfxcapture=hwnd=${0x2A0F3C}:max_framerate=30:"), graph)
        assertTrue(":resize_mode=scale_aspect" in graph && ":display_border=0" in graph && ":dup_frames=1," in graph, graph)
        assertTrue(graph.endsWith("format=nv12[v]"), graph)
        assertEquals("[v]", a.valueOf("-map"))
        assertFalse("-i" in a)
        assertEquals("expr:gte(t,n_forced)", a.valueOf("-force_key_frames"))
        assertTrue("-an" in a)
        val older = ScreenCapture.args("FFMPEG", Source.GFXCAPTURE, window, Encoder.NVENC, "rtmp://h/live/x")
        assertFalse("dup_frames" in older.valueOf("-filter_complex")!!)
        assertFalse("-force_key_frames" in ScreenCapture.args("FFMPEG", Source.DDAGRAB, monitor, Encoder.NVENC, "rtmp://h/a"))
    }

    @Test
    fun `without gfxcapture a window falls back to gdi by handle`() {
        val a = ScreenCapture.args("FFMPEG", Source.GDIGRAB, window, Encoder.X264, "rtmp://h/live/x")
        assertEquals("gdigrab", a.valueOf("-f"))
        assertEquals("hwnd=${0x2A0F3C}", a.valueOf("-i"))
        assertFalse("-offset_x" in a)
        // A handle with the top bit set still reads as the unsigned number FFmpeg parses
        val high = ScreenCapture.Window(-2L, 1, "x", "y")
        assertEquals("hwnd=18446744073709551614", ScreenCapture.args("FFMPEG", Source.GDIGRAB, high, Encoder.X264, "rtmp://h/a").valueOf("-i"))
    }

    @Test
    fun `gfxcapture help decides the window source`() {
        val help = "Filter gfxcapture\ngfxcapture AVOptions:\n   hwnd   <uint64>\n   dup_frames <boolean>"
        assertEquals(ScreenCapture.WindowCapture(Source.GFXCAPTURE, true), ScreenCapture.parseWindowCapture(help))
        assertEquals(ScreenCapture.WindowCapture(Source.GFXCAPTURE, false), ScreenCapture.parseWindowCapture(help.substringBefore("   dup")))
        assertEquals(Source.GDIGRAB, ScreenCapture.parseWindowCapture("Unknown filter 'gfxcapture'.").source)
    }

    @Test
    fun `windows sound joins as a wav pipe, the picture stamped by the wall clock less the base`() {
        val a = ScreenCapture.args("FFMPEG", Source.DDAGRAB, monitor, Encoder.NVENC, "rtmp://h/live/x", audio = pipe, clockBaseMicros = base)
        assertFalse("-an" in a)
        val graph = a.valueOf("-filter_complex")!!
        assertTrue(graph.startsWith("ddagrab=output_idx=1:framerate=30:draw_mouse=1,setpts=(RTCTIME-$base)/(TB*1000000),hwdownload"), graph)
        assertFalse("-itsoffset" in a)
        // The only input is the sound: WAV in 10 ms packets, sample 0 at the base itself
        assertEquals(listOf(pipe.path), a.valuesOf("-i"))
        val pipeAt = a.indexOf(pipe.path)
        val soundIn = a.subList(0, pipeAt)
        assertEquals(listOf("wav"), soundIn.valuesOf("-f"))
        assertEquals("1", soundIn.valueOf("-ignore_length"))
        assertEquals("1920", soundIn.valueOf("-max_size"))
        assertEquals(listOf("[v]", "0:a:0"), a.valuesOf("-map"))
        assertTrue("-copyts" in a)
        assertEquals("1:1000", a.valueOf("-enc_time_base:v"))
        assertEquals("passthrough", a.valueOf("-fps_mode:v"))
        assertEquals("aac", a.valueOf("-c:a"))
        assertEquals("160k", a.valueOf("-b:a"))
        assertEquals(ScreenCapture.MAX_INTERLEAVE_MICROS, a.valueOf("-max_interleave_delta"))
        assertEquals("flv", a[a.size - 2])
        // Every output option comes after the input
        assertTrue(a.indexOf("-copyts") > pipeAt && a.indexOf("-c:v") > pipeAt && a.indexOf("-filter_complex") > pipeAt)
    }

    @Test
    fun `a window with sound and a float mix format`() {
        val float = ShareAudio.Input.Pipe("P", ShareAudio.Format(44_100, 6, float = true))
        val a = ScreenCapture.args("FFMPEG", Source.GFXCAPTURE, window, Encoder.AMF, "rtmp://h/live/x", audio = float, clockBaseMicros = base)
        assertTrue(",setpts=(RTCTIME-$base)/(TB*1000000),hwdownload" in a.valueOf("-filter_complex")!!)
        val pipeAt = a.indexOf("P")
        assertEquals("wav", a[a.subList(0, pipeAt).lastIndexOf("-f") + 1])
        assertEquals("10584", a.subList(0, pipeAt).valueOf("-max_size"))
        // Output: stereo 48 kHz whatever came in
        assertEquals("2", a.subList(pipeAt, a.size).valueOf("-ac"))
        assertEquals("48000", a.subList(pipeAt, a.size).valueOf("-ar"))
    }

    @Test
    fun `gdi capture with sound keeps its wall clock stamps less the base`() {
        val a = ScreenCapture.args("FFMPEG", Source.GDIGRAB, monitor, Encoder.X264, "rtmp://h/live/x", audio = pipe, clockBaseMicros = base)
        assertEquals(listOf("-1762862345.123456"), a.valuesOf("-itsoffset"))
        assertTrue(a.indexOf("-itsoffset") < a.indexOf("gdigrab"))
        assertEquals(listOf("desktop", pipe.path), a.valuesOf("-i"))
        assertEquals(listOf("0:v:0", "1:a:0"), a.valuesOf("-map"))
        assertTrue(a.indexOf("-vf") > a.indexOf(pipe.path))
    }

    @Test
    fun `linux sound comes from pulse on the default monitor, stamped by the wall clock like x11grab`() {
        val a = ScreenCapture.args("FFMPEG", Source.X11GRAB, monitor, Encoder.X264, publish,
            audio = ShareAudio.Input.Pulse(ShareAudio.PULSE_DEFAULT_MONITOR), clockBaseMicros = base)
        assertEquals(listOf("-1762862345.123456", "-1762862345.123456"), a.valuesOf("-itsoffset"))
        assertEquals(listOf("x11grab", "pulse", "mpegts"), a.valuesOf("-f"))
        assertEquals("@DEFAULT_MONITOR@", a.valuesOf("-i")[1])
        assertEquals("1920", a.valueOf("-fragment_size"))
        assertEquals(listOf("0:v:0", "1:a:0"), a.valuesOf("-map"))
    }

    @Test
    fun `clock base seconds keep every microsecond`() {
        assertEquals("1762862345.000001", ScreenCapture.seconds(1_762_862_345_000_001L))
        assertEquals("0.250000", ScreenCapture.seconds(250_000L))
    }

    @Test
    fun `window labels name the program, then the title`() {
        assertEquals("Google Chrome - Video - YouTube", ScreenCapture.label(window))
        assertEquals("Notepad", ScreenCapture.label(window.copy(app = "Notepad", title = "notepad")))
    }
}
