package com.dreamdisplays.media.player.nativebridge

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LavInputGateTest {
    @Test
    fun `plain http(s) media opens in process`() {
        assertTrue(lavMayOpen("https://cdn.example.com/video.mp4"))
        assertTrue(lavMayOpen("http://cdn.example.com/video.webm"))
    }

    @Test
    fun `a real googlevideo paced url may take the bounded-request path`() {
        assertTrue(lavMayOpen("https://rr1---sn-abc.googlevideo.com/videoplayback?gir=yes&clen=1000&dur=10"))
    }

    @Test
    fun `non-http schemes never reach the native reader`() {
        assertFalse(lavMayOpen("file://evil.example/share/googlevideo.com/x?gir=yes&clen=1000"))
        assertFalse(lavMayOpen("file:///C:/secret.mp4"))
        assertFalse(lavMayOpen("rtsp://relay.example.com/live"))
    }

    @Test
    fun `a url that only mimics googlevideo is kept off the bounded-request path`() {
        assertFalse(lavMayOpen("https://evil.example/googlevideo.com/x?gir=yes&clen=1000"))
        assertFalse(lavMayOpen("http://rr1---sn-abc.googlevideo.com/videoplayback?gir=yes&clen=1000"))
    }
}
