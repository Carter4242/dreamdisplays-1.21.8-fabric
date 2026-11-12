package com.dreamdisplays.shared.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaUrlPolicyTest {
    @Test
    fun emptyIsAllowed() = assertTrue(MediaUrlPolicy.isAllowed(""))

    @Test
    fun bareYouTubeIdIsAllowed() = assertTrue(MediaUrlPolicy.isAllowed("dQw4w9WgXcQ"))

    @Test
    fun httpUrlIsAllowed() = assertTrue(MediaUrlPolicy.isAllowed("https://youtube.com/watch?v=dQw4w9WgXcQ"))

    @Test
    fun uppercaseHttpSchemeIsAllowed() = assertTrue(MediaUrlPolicy.isAllowed("HTTPS://example.com/video.mp4"))

    @Test
    fun hostlessHttpUrlIsRejected() = assertFalse(MediaUrlPolicy.isAllowed("https:///watch?v=dQw4w9WgXcQ"))

    @Test
    fun nonHttpUrlIsRejected() = assertFalse(MediaUrlPolicy.isAllowed("javascript:alert(1)"))

    @Test
    fun whitespaceIsRejected() = assertFalse(MediaUrlPolicy.isAllowed("https://example.com/a b"))

    @Test
    fun controlCharIsRejected() = assertFalse(MediaUrlPolicy.isAllowed("https://example.com/\u0000"))

    @Test
    fun nonUrlIsRejected() = assertFalse(MediaUrlPolicy.isAllowed("not-a-url"))

    @Test
    fun urlAtCapIsAllowed() {
        val url = "https://example.com/" + "a".repeat(MediaUrlPolicy.MAX_URL_LENGTH - 20)
        assertTrue(MediaUrlPolicy.isAllowed(url))
        assertTrue(url.length == MediaUrlPolicy.MAX_URL_LENGTH)
    }

    @Test
    fun overlongUrlIsRejected() {
        val url = "https://example.com/" + "a".repeat(MediaUrlPolicy.MAX_URL_LENGTH)
        assertFalse(MediaUrlPolicy.isAllowed(url))
    }

    @Test
    fun overlongRawUrlIsRejectedEvenWhenTrimmedValueIsValid() {
        val url = " ".repeat(MediaUrlPolicy.MAX_URL_LENGTH) + "https://example.com/video.mp4"
        assertFalse(MediaUrlPolicy.isAllowed(url))
    }

    @Test
    fun languageTagIsSanitized() {
        assertEquals("enus", MediaUrlPolicy.sanitizeLang(" en\tus\u0000"))
        assertEquals("abcdefghijklmnop", MediaUrlPolicy.sanitizeLang("abcdefghijklmnopq"))
    }
}

class MediaUrlPolicyLiveTest {
    @Test
    fun relayLinksAreAllowed() {
        for (url in [
            "srt://relay.example.com:8890?streamid=read:room1",
            "rtmp://203.0.113.5/live/room1",
            "rtmps://relay.example.com/live/room1",
            "rtsp://relay.example.com:8554/room1",
        ]) assertTrue(MediaUrlPolicy.isAllowed(url), url)
    }

    @Test
    fun brokenOrForeignRelayLinksAreRejected() {
        assertFalse(MediaUrlPolicy.isAllowed("srt://"))
        assertFalse(MediaUrlPolicy.isAllowed("srt://host:8890?streamid=read:a b"))
        assertFalse(MediaUrlPolicy.isAllowed("udp://239.0.0.1:1234"))
        assertFalse(MediaUrlPolicy.isAllowed("file:///etc/passwd"))
        assertFalse(MediaUrlPolicy.isAllowed("srt://h:1?x=" + "a".repeat(MediaUrlPolicy.MAX_URL_LENGTH)))
    }
}

class MediaUrlPolicyPastedLinkTest {
    @Test
    fun pastedSiteLinksAreAllowed() {
        // A link pasted into the menu search and queued as-is (watch URL = the link)
        for (url in [
            "https://www.instagram.com/reel/Dfku6yHuW2R",
            "https://www.tiktok.com/@someone/video/7300000000000000000",
            "https://cdn.example.com/v.mp4",
        ]) {
            assertTrue(MediaUrlPolicy.isAllowed(url), url)
            assertEquals(CustomMediaPolicy.Verdict.ALLOWED, CustomMediaPolicy.evaluate(url, CustomMediaPolicy.Settings.DEFAULT), url)
        }
    }
}
