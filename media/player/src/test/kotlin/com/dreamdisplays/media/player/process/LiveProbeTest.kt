package com.dreamdisplays.media.player.process

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveProbeTest {
    @AfterTest
    fun reset() = LiveProbe.forget()

    private fun List<String>.valueOf(flag: String): String? =
        indexOf(flag).takeIf { it >= 0 && it + 1 < size }?.let { this[it + 1] }

    // FFmpeg 9's picture process when the probe ran out before the first keyframe (measured against MediaMTX)
    private val ranOut = "Output file does not contain any stream\nError opening output file -.\nError opening output files: Invalid argument"

    @Test
    fun `an outside rtmp source whose keyframe came after the probe gets the wide probe on its next attempts`() {
        val url = "rtmp://relay.example.org/live/obs"
        val before = MediaProcess.liveInputCommand("FFMPEG", url, HwAccelBackend.NONE, picture = true)
        assertEquals(MediaProcess.LIVE_PROBE_SIZE, before.valueOf("-probesize"))
        assertTrue(LiveProbe.noteFailure(url, ranOut))
        assertFalse(LiveProbe.noteFailure(url, ranOut), "only the first one is news")
        val after = MediaProcess.liveInputCommand("FFMPEG", url, HwAccelBackend.NONE, picture = true)
        assertEquals(MediaProcess.LIVE_WIDE_PROBE_SIZE, after.valueOf("-probesize"))
        assertEquals(MediaProcess.LIVE_WIDE_ANALYZE_MICROS, after.valueOf("-analyzeduration"))
        // Inside the 6 s startup watchdog
        assertTrue(MediaProcess.LIVE_WIDE_ANALYZE_MICROS.toLong() <= 4_500_000L)
        // The sound process keeps the short probe: it must not wait for a picture it drops anyway
        val sound = MediaProcess.liveInputCommand("FFMPEG", url, HwAccelBackend.NONE, picture = false)
        assertEquals(MediaProcess.LIVE_PROBE_SIZE, sound.valueOf("-probesize"))
        // Other streams are untouched
        val share = MediaProcess.liveInputCommand("FFMPEG", "rtmps://relay.example.org/ddabc", HwAccelBackend.NONE, picture = true)
        assertEquals(MediaProcess.LIVE_PROBE_SIZE, share.valueOf("-probesize"))
    }

    @Test
    fun `only rtmp widens, and only for a probe that ran out`() {
        assertTrue(LiveProbe.ranOutBeforePicture("rtmps://h/a", ranOut))
        // MPEG-TS over SRT always probes to the limit, and RTSP knows its streams from the SDP
        assertFalse(LiveProbe.ranOutBeforePicture("srt://h:8890?streamid=read:a", ranOut))
        assertFalse(LiveProbe.ranOutBeforePicture("rtsp://h/a", ranOut))
        // A share not on the air yet, a dropped connection
        assertFalse(LiveProbe.ranOutBeforePicture("rtmps://h/a", "Server error: no stream is available on path 'a'"))
        assertFalse(LiveProbe.ranOutBeforePicture("rtmps://h/a", "rtmps://h/a: Input/output error"))
    }
}
