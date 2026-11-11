package com.dreamdisplays.media.runtime.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaHostGuardSchemeTest {
    @Test
    fun `http(s) and live relay schemes are fetchable`() {
        for (url in ["https://a.example/v.mp4", "HTTP://a.example/v.mp4", "srt://relay.example:8890", "rtmps://r.example/live/x"]) {
            assertTrue(MediaHostGuard.hasAllowedScheme(url), url)
        }
    }

    @Test
    fun `file and other schemes are refused before any lookup`() {
        for (url in ["file://evil.example/share/x.mpd", "file:///C:/x.mp4", "ftp://a.example/v.mp4", "smb://h/s", "a.example/v.mp4"]) {
            assertFalse(MediaHostGuard.hasAllowedScheme(url), url)
            assertEquals(MediaHostGuard.HostVerdict.NON_PUBLIC, MediaHostGuard.verdict(url), url)
        }
        assertFailsWith<MediaHostGuard.HostRefusedException> {
            MediaHostGuard.resolveSafeUrl("file://evil.example/share/googlevideo.com/x?gir=yes&clen=1")
        }
    }
}
