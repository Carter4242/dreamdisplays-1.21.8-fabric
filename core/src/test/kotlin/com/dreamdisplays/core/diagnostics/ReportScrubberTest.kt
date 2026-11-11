package com.dreamdisplays.core.diagnostics

import com.dreamdisplays.api.media.source.LiveStreamUrls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportScrubberTest {
    @Test
    fun `mac home paths become tilde`() {
        assertEquals(
            "~/Library/Application Support/minecraft/libs/yt-dlp/yt-dlp.pyz",
            ReportScrubber.scrub("/Users/jsmith/Library/Application Support/minecraft/libs/yt-dlp/yt-dlp.pyz"),
        )
        assertEquals("python at ~ failed", ReportScrubber.scrub("python at /Users/jsmith failed"))
    }

    @Test
    fun `windows home paths become tilde, also with spaces and escaped separators`() {
        assertEquals(
            "~\\AppData\\Roaming\\.minecraft\\dreamdisplays\\ffmpeg\\ffmpeg.exe",
            ReportScrubber.scrub("C:\\Users\\Jane Doe\\AppData\\Roaming\\.minecraft\\dreamdisplays\\ffmpeg\\ffmpeg.exe"),
        )
        assertEquals("~/AppData", ReportScrubber.scrub("c:/users/carter/AppData"))
        assertEquals("\"~\\\\AppData\\\\x\"", ReportScrubber.scrub("\"C:\\\\Users\\\\carter\\\\AppData\\\\x\""))
        assertEquals("open ~ denied", ReportScrubber.scrub("open C:\\Users\\carter denied"))
    }

    @Test
    fun `linux home paths become tilde`() {
        assertEquals("~/.local/share/x", ReportScrubber.scrub("/home/alice/.local/share/x"))
        assertEquals("~/.var/app/x", ReportScrubber.scrub("/var/home/alice/.var/app/x"))
        assertEquals("~/.minecraft", ReportScrubber.scrub("/root/.minecraft"))
        assertEquals("/usr/bin/python3", ReportScrubber.scrub("/usr/bin/python3"))
    }

    @Test
    fun `the client's own home dir is replaced even outside the usual places`() {
        assertEquals("~/games/mc", ReportScrubber.scrub("/data/alice/games/mc", listOf("/data/alice")))
    }

    @Test
    fun `signed stream urls keep host and path only`() {
        val url = "https://rr3---sn-ab5l6nzr.googlevideo.com/videoplayback?expire=1762845123&ei=x&ip=203.0.113.7" +
            "&id=o-AB&itag=399&sig=AJfQdSswRgIhAK&lsig=APaTxxMw&pot=MnQ"
        val scrubbed = ReportScrubber.scrub("[https @ 0x1] HTTP error 403 Forbidden for $url\n")
        assertEquals("[https @ 0x1] HTTP error 403 Forbidden for https://rr3---sn-ab5l6nzr.googlevideo.com/videoplayback?<redacted>\n", scrubbed)
        assertFalse("203.0.113.7" in scrubbed)
        assertEquals("https://cdn.example.com/a.mp4?<redacted>", ReportScrubber.scrubUrl("https://cdn.example.com/a.mp4?token=abc&x=1"))
        assertEquals("https://cdn.example.com/a.mp4?<redacted>", ReportScrubber.scrubUrl("https://cdn.example.com/a.mp4?Signature=abc"))
    }

    @Test
    fun `relay credentials never survive a scrub`() {
        val line = "[out#0/flv @ 0x1] Error opening output rtmps://live.example.org:443/ddabc?user=publish&pass=s3cr3t: I/O error"
        val scrubbed = ReportScrubber.scrub(line)
        assertFalse("s3cr3t" in scrubbed)
        assertFalse("publish&" in scrubbed)
        assertTrue("rtmps://live.example.org:443/ddabc?" in scrubbed)
        assertFalse("hunter2" in ReportScrubber.scrub("rtmp://alice:hunter2@relay.example.com:1935/ddabc: Connection refused"))
        assertFalse("hunter2" in ReportScrubber.scrubUrl("rtmp://alice:hunter2@relay.example.com/ddabc"))
        assertEquals(
            "srt://h:8890?streamid=publish:room1:<redacted>&passphrase=<redacted>",
            LiveStreamUrls.redactCredentials("srt://h:8890?streamid=publish:room1:bob:pw&passphrase=longsecret1"),
        )
        assertEquals(
            "srt://h:8890?streamid=read:room1",
            LiveStreamUrls.redactCredentials("srt://h:8890?streamid=read:room1"),
        )
        // Redaction is stable: a redacted line redacts to itself
        val once = LiveStreamUrls.redactCredentials(line)
        assertEquals(once, LiveStreamUrls.redactCredentials(once))
        assertEquals("rtmps://live.example.org:443/ddabc", LiveStreamUrls.redactCredentials("rtmps://live.example.org:443/ddabc"))
    }

    @Test
    fun `ordinary links are kept whole`() {
        val watch = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42"
        assertEquals(watch, ReportScrubber.scrub(watch))
    }

    @Test
    fun `cookies and auth headers are dropped`() {
        val text = "--add-header \"Cookie: SID=abc; HSID=def\" --cookies /Users/bob/cookies.txt\nAuthorization: Bearer xyz"
        val scrubbed = ReportScrubber.scrub(text)
        assertFalse("SID=abc" in scrubbed)
        assertFalse("xyz" in scrubbed)
        assertFalse("cookies.txt" in scrubbed)
        assertTrue("Cookie: <redacted>" in scrubbed)
        assertTrue("Authorization: <redacted>" in scrubbed)
    }

    @Test
    fun `excerpt keeps the last lines within the byte budget`() {
        val stderr = (1..40).joinToString("\n") { "line $it /Users/x/y" }
        val excerpt = ReportScrubber.excerpt(stderr)
        val lines = excerpt.lines()
        assertEquals(15, lines.size)
        assertEquals("line 40 ~/y", lines.last())
        assertTrue("/Users/x" !in excerpt)
        val big = (1..10).joinToString("\n") { "x".repeat(500) + it }
        assertTrue(ReportScrubber.excerpt(big).toByteArray().size <= 2048)
        assertTrue(ReportScrubber.excerpt(big).endsWith("10"))
        assertEquals(2048, ReportScrubber.excerpt("y".repeat(5000)).length)
        assertEquals("", ReportScrubber.excerpt("  \n \n"))
    }

    @Test
    fun `byte truncation never splits a character`() {
        val text = "é".repeat(10)
        val cut = ReportScrubber.truncateBytes(text, 5)
        assertEquals("éé", cut)
    }

    @Test
    fun `an owner's name in an audio device name is taken out`() {
        assertEquals(
            "Display audio line failed on \"Headphones (<name>'s AirPods Pro)\"",
            ReportScrubber.scrub("Display audio line failed on \"Headphones (Alex's AirPods Pro)\""),
        )
        assertEquals("Line unavailable: <name>'s AirPods", ReportScrubber.scrub("Line unavailable: Jo’s AirPods"))
        assertEquals("Speakers (2- <name>'s Buds2)", ReportScrubber.scrub("Speakers (2- Mia-Li's Buds2)"))
        assertEquals("openal on <name>'s MacBook Pro Speakers", ReportScrubber.scrub("openal on <name>'s MacBook Pro Speakers"))
    }

    @Test
    fun `ordinary possessives and contractions stay readable`() {
        val plain = "It's gone: the stream's codec isn't supported, FFmpeg's demuxer can't open it"
        assertEquals(plain, ReportScrubber.scrub(plain))
        val device = "javasound on \"Speakers (Realtek(R) Audio)\", 44100 Hz"
        assertEquals(device, ReportScrubber.scrub(device))
    }
}
