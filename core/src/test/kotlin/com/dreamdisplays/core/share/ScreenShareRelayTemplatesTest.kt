package com.dreamdisplays.core.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenShareRelayTemplatesTest {
    private val publish = "rtmps://live.example.org:1935/{id}?user=publish&pass=secret"
    private val play = "rtmps://live.example.org:1935/{id}"

    private fun problem(publish: String, play: String, leased: Boolean = false) =
        ScreenShareRelayTemplates.problem(publish, play, leased)

    @Test
    fun `the shipped shapes are accepted`() {
        assertNull(problem(publish, play))
        assertNull(problem("srt://r:8890?streamid=publish:{id}:u:p", "srt://r:8890?streamid=read:{id}"))
        assertNull(problem("rtmp://r/live/{id}", "rtsp://r:8554/live/{id}"))
    }

    @Test
    fun `an https or hls play url is refused, so viewers never treat a share as a public video`() {
        assertNotNull(problem(publish, "https://live.example.org/{id}/index.m3u8"))
        assertNotNull(problem(publish, "http://live.example.org/{id}.flv"))
    }

    @Test
    fun `rtsp works both ways (the client publishes through the rtsp muxer), other schemes do not`() {
        assertNull(problem("rtsp://r:8554/{id}", play))
        assertNotNull(problem("http://r/{id}", play))
        assertNull(problem(publish, "rtsp://r:8554/{id}"))
    }

    @Test
    fun `both need an id and a host`() {
        assertNotNull(problem("rtmps://live.example.org/fixed", play))
        assertNotNull(problem(publish, "rtmps://live.example.org/fixed"))
        assertNotNull(problem("rtmps:///{id}", play))
        assertNotNull(problem(publish, ""))
    }

    @Test
    fun `the key placeholder goes with a lease service and never into the play url`() {
        val keyed = "rtmps://live.example.org:1935/{id}?user=dd&pass={key}"
        assertNull(problem(keyed, play, leased = true))
        assertNotNull(problem(publish, play, leased = true))
        assertNotNull(problem(keyed, play, leased = false))
        assertNotNull(problem(keyed, "rtmps://live.example.org:1935/{id}?k={key}", leased = true))
    }

    @Test
    fun `both empty is off, not a problem`() {
        assertFalse(ScreenShareRelayTemplates.isSet("", " "))
        assertTrue(ScreenShareRelayTemplates.isSet(publish, ""))
    }

    @Test
    fun `urls are filled in`() {
        assertEquals(
            "rtmps://h:1935/ddabc?user=dd&pass=k123",
            ScreenShareRelayTemplates.publishUrl("rtmps://h:1935/{id}?user=dd&pass={key}", "ddabc", "k123"),
        )
        assertEquals("rtmps://h:1935/ddabc", ScreenShareRelayTemplates.playUrl(" rtmps://h:1935/{id} ", "ddabc"))
    }
}
