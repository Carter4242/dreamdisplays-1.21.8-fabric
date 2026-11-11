package com.dreamdisplays.api.media.source.url

import com.dreamdisplays.api.media.source.CustomMediaKind
import com.dreamdisplays.api.media.source.CustomMediaUrls
import com.dreamdisplays.api.media.source.LiveStreamUrls
import com.dreamdisplays.api.media.source.MediaPlatform
import com.dreamdisplays.api.media.source.MediaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveStreamUrlsTest {
    private val srt = "srt://relay.example.com:8890?streamid=read:room1"

    @Test
    fun `every relay scheme is a live link`() {
        for (url in [srt, "rtmp://relay.example.com/live/room1", "rtmps://relay.example.com/live/room1",
            "rtsp://relay.example.com:8554/room1", "SRT://203.0.113.5:8890"]) {
            assertTrue(LiveStreamUrls.isValid(url), url)
            assertEquals(CustomMediaKind.LIVE, CustomMediaUrls.classify(url), url)
            assertTrue(CustomMediaKind.LIVE.isDirect)
        }
    }

    @Test
    fun `other schemes and broken relay links are not`() {
        assertFalse(LiveStreamUrls.isLive("https://relay.example.com/live.m3u8"))
        assertFalse(LiveStreamUrls.isLive("udp://239.0.0.1:1234"))
        assertFalse(LiveStreamUrls.isValid("srt://"))
        assertFalse(LiveStreamUrls.isValid("srt:///path"))
        assertFalse(LiveStreamUrls.isValid("srt://host:8890?streamid=read:a b"))
        assertFalse(LiveStreamUrls.isValid(srt, maxLength = 10))
        assertEquals(CustomMediaKind.PROGRESSIVE, CustomMediaUrls.classify("https://cdn.example.com/a.ts"))
    }

    @Test
    fun `a relay link is a direct live source, never a platform or extractor link`() {
        val source = MediaSource.from(srt)
        assertIs<MediaSource.DirectStream>(source)
        assertEquals(CustomMediaKind.LIVE, source.kind)
        assertEquals(srt, source.streamUrl)
        assertEquals(MediaPlatform.DIRECT, source.platform)
        // A host that looks like a platform's must not turn it into one
        assertIs<MediaSource.DirectStream>(MediaSource.from("rtmp://live.twitch.tv/app/key"))
        assertIs<MediaSource.DirectStream>(MediaSource.from("<$srt>"))
    }

    @Test
    fun `normalize keeps a relay link as it is`() {
        assertEquals(srt, CustomMediaUrls.normalize(" $srt "))
        assertNull(CustomMediaUrls.normalize("srt://"))
    }

    @Test
    fun `a relay link is named after its stream`() {
        assertEquals("room1", LiveStreamUrls.displayName(srt))
        assertEquals("room1", LiveStreamUrls.displayName("srt://h:8890?streamid=read:room1:user:pass"))
        assertEquals("room2", LiveStreamUrls.displayName("srt://h:8890?streamid=%23!::r=room2,m=request"))
        assertEquals("live/room1", LiveStreamUrls.displayName("rtmp://h/live/room1"))
        assertEquals("h", LiveStreamUrls.displayName("srt://h:8890"))
        assertEquals("room1", CustomMediaUrls.displayName(srt))
        assertEquals("relay.example.com", CustomMediaUrls.hostOf(srt))
    }

    @Test
    fun `srt gets the default latency in microseconds and live transport`() {
        assertEquals(
            "srt://h:8890?streamid=read:room1&latency=80000&transtype=live",
            LiveStreamUrls.playUrl("srt://h:8890?streamid=read:room1"),
        )
        assertEquals("srt://h:8890?latency=60000&transtype=live", LiveStreamUrls.playUrl("srt://h:8890", 60))
    }

    @Test
    fun `a latency the url sets wins, read as ms when small, and is clamped`() {
        assertEquals("srt://h?latency=120000&transtype=live", LiveStreamUrls.playUrl("srt://h?latency=120", 60))
        assertEquals("srt://h?latency=150000&transtype=live", LiveStreamUrls.playUrl("srt://h?latency=150000"))
        assertEquals("srt://h?rcvlatency=20000&transtype=live", LiveStreamUrls.playUrl("srt://h?rcvlatency=1"))
        assertEquals("srt://h?latency=20000&transtype=live", LiveStreamUrls.playUrl("srt://h", 5))
        assertEquals(
            "srt://h?transtype=live&latency=80000",
            LiveStreamUrls.playUrl("srt://h?transtype=live"),
        )
    }

    @Test
    fun `other relay schemes pass through untouched`() {
        assertEquals("rtmp://h/live/a", LiveStreamUrls.playUrl(" rtmp://h/live/a ", 60))
        assertEquals("rtsp://h:8554/a", LiveStreamUrls.playUrl("rtsp://h:8554/a"))
    }
}

class LiveStreamPublishUrlTest {
    @Test
    fun `an srt publish url gets latency, live transport and the relay packet size`() {
        assertEquals(
            "srt://h:8890?streamid=publish:a&latency=60000&transtype=live&pkt_size=1316",
            LiveStreamUrls.publishUrl("srt://h:8890?streamid=publish:a", 60),
        )
        assertEquals(
            "srt://h:8890?pkt_size=1000&latency=80000&transtype=live",
            LiveStreamUrls.publishUrl("srt://h:8890?pkt_size=1000"),
        )
        assertEquals("rtmp://h/live/a", LiveStreamUrls.publishUrl("rtmp://h/live/a", 60))
    }
}
