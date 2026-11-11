package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewerReportScrubTest {
    @Test
    fun `a viewer report's error keeps no account name or signed query`() {
        val raw = """Failed to start FFmpeg: Cannot run program "C:\Users\Jane Doe\AppData\Roaming\.minecraft\dreamdisplays\ffmpeg\windows-x64-9.0\ffmpeg.exe""""
        val sent = ViewerReporter.errorMessage(raw, listOf("""C:\Users\Jane Doe"""))
        assertFalse("Jane" in sent, sent)
        assertTrue(sent.startsWith("Failed to start FFmpeg: Cannot run program \"~"), sent)

        val url = ViewerReporter.errorMessage("HTTP 403 for https://rr3---sn-x.googlevideo.com/videoplayback?ip=1.2.3.4&sig=abc", emptyList())
        assertEquals("HTTP 403 for https://rr3---sn-x.googlevideo.com/videoplayback?<redacted>", url)
    }

    @Test
    fun `it is cut after scrubbing and empty stays empty`() {
        assertEquals(160, ViewerReporter.errorMessage("x".repeat(500), emptyList()).length)
        assertEquals("", ViewerReporter.errorMessage(null, emptyList()))
    }
}
