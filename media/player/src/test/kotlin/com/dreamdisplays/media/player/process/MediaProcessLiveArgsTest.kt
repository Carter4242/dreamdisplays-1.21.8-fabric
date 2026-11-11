package com.dreamdisplays.media.player.process

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaProcessLiveArgsTest {
    private val srt = "srt://203.0.113.5:8890?streamid=read:room1&latency=80000&transtype=live"

    private fun List<String>.valueOf(flag: String): String? =
        indexOf(flag).takeIf { it >= 0 && it + 1 < size }?.let { this[it + 1] }

    private fun input(url: String, hw: HwAccelBackend = HwAccelBackend.NONE) =
        MediaProcess.liveInputCommand("FFMPEG", url, hw)

    @Test
    fun `a relay input is tuned for the live edge`() {
        val a = input(srt)
        assertEquals(srt, a.last())
        assertEquals("-i", a[a.size - 2])
        assertEquals("nobuffer+discardcorrupt", a.valueOf("-fflags"))
        assertEquals("low_delay", a.valueOf("-flags"))
        assertEquals("0", a.valueOf("-analyzeduration"))
        assertEquals(MediaProcess.LIVE_PROBE_SIZE, a.valueOf("-probesize"))
        assertEquals("0", a.valueOf("-max_delay"))
        assertEquals("2000000", a.valueOf("-rw_timeout"))
        val whitelist = a.valueOf("-protocol_whitelist")!!.split(',')
        assertTrue(whitelist.containsAll(["srt", "rtmp", "rtmps", "rtsp", "rtp", "tcp", "udp", "tls"]))
        assertFalse("http" in whitelist || "https" in whitelist || "file" in whitelist, "A relay input never needs http or files.")
    }

    @Test
    fun `a relay input carries no http, seek or protocol-foreign options`() {
        for (url in [srt, "rtmp://h/live/a", "rtsp://h:8554/a"]) {
            val a = input(url)
            for (flag in ["-headers", "-reconnect", "-tls_verify", "-ss", "-multiple_requests", "-seg_max_retry"]) {
                assertFalse(flag in a, "$flag does not apply to $url")
            }
        }
        assertFalse("-rtsp_transport" in input(srt))
        assertFalse("-reorder_queue_size" in input(srt), "An RTSP-only demuxer option fails on any other input.")
        assertFalse("-rtmp_live" in input("rtsp://h:8554/a"))
    }

    @Test
    fun `rtsp runs over tcp and rtmp asks for a live feed`() {
        val rtsp = input("rtsp://h:8554/a")
        assertEquals("tcp", rtsp.valueOf("-rtsp_transport"))
        assertEquals("2000000", rtsp.valueOf("-timeout"))
        assertFalse("-rw_timeout" in rtsp, "The RTSP demuxer rejects the generic I/O timeout.")
        assertEquals("2000000", input("rtmp://h/live/a").valueOf("-rw_timeout"))
        assertEquals("0", rtsp.valueOf("-reorder_queue_size"))
        val rtmp = input("rtmps://h/live/a")
        assertEquals("live", rtmp.valueOf("-rtmp_live"))
        assertEquals("100", rtmp.valueOf("-rtmp_buffer"))
    }

    @Test
    fun `hardware decode applies to a relay input`() {
        val a = input(srt, HwAccelBackend.D3D11VA)
        assertEquals("d3d11va", a.valueOf("-hwaccel"))
        assertTrue(a.indexOf("-hwaccel") < a.indexOf("-i"))
    }

    @Test
    fun `relay video goes out at arrival, other video at a constant rate`() {
        val live = MediaProcess.videoArgs(
            "FFMPEG", srt, 640, 360, 0L, HwAccelBackend.NONE, MediaProcess.VideoTransport.RAW_NV12, 30.0,
            alreadyResolved = true,
        )
        assertEquals("passthrough", live.valueOf("-fps_mode"))
        assertEquals("1", live.valueOf("-flush_packets"))
        assertFalse("-r" in live, "A constant output rate holds frames back.")
        assertEquals("rawvideo", live.valueOf("-f"))

        val vod = MediaProcess.videoArgs(
            "FFMPEG", "https://cdn.example.com/a.mp4", 640, 360, 0L, HwAccelBackend.NONE,
            MediaProcess.VideoTransport.RAW_NV12, 30.0, alreadyResolved = true,
        )
        assertEquals("30.000000", vod.valueOf("-r"))
        assertFalse("-fps_mode" in vod)
        assertFalse("-flush_packets" in vod)
    }

    @Test
    fun `relay audio is flushed per packet`() {
        assertEquals("1", MediaProcess.audioOutputArgs(44100, liveEdge = true).valueOf("-flush_packets"))
        assertFalse("-flush_packets" in MediaProcess.audioOutputArgs(44100))
    }
}
