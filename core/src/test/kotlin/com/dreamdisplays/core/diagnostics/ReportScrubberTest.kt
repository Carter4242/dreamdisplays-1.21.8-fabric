package com.dreamdisplays.core.diagnostics

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
}
