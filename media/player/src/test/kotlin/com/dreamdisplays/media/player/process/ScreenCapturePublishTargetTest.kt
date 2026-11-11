package com.dreamdisplays.media.player.process

import kotlin.test.Test
import kotlin.test.assertFalse

class ScreenCapturePublishTargetTest {
    @Test
    fun `a server cannot aim the screen capture at a file or a non-relay protocol`() {
        for (url in [
            "C:\Users\Public\x.ts",
            "/tmp/x.ts",
            "file:///C:/Users/Public/x.ts",
            "tcp://192.168.1.20:9100",
            "http://192.168.1.1/upload",
            "https://relay.example.com/x",
            "",
        ]) assertFalse(ScreenCapture.publishTargetAllowed(url), url)
    }

    @Test
    fun `a relay on a private or loopback address is refused`() {
        assertFalse(ScreenCapture.publishTargetAllowed("rtmp://127.0.0.1:1935/abc?user=p&pass=x"))
        assertFalse(ScreenCapture.publishTargetAllowed("srt://192.168.1.10:8890?streamid=publish:abc"))
    }
}
