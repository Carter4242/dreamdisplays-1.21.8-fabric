package com.dreamdisplays.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class ResyncMarkerTest {
    @Test
    fun `strips the marker wherever it sits in the query`() {
        assertEquals(
            "https://www.youtube.com/watch?v=ywuuaGH2erA",
            ResyncMarker.strip("https://www.youtube.com/watch?v=ywuuaGH2erA&ddsync=1"),
        )
        assertEquals("https://x.io/a.mp4", ResyncMarker.strip("https://x.io/a.mp4?ddsync=2"))
        assertEquals("https://x.io/a.mp4?x=1&y=2", ResyncMarker.strip("https://x.io/a.mp4?ddsync=1&x=1&y=2"))
        assertEquals("https://x.io/a.mp4?x=1&y=2", ResyncMarker.strip("https://x.io/a.mp4?x=1&ddsync=12&y=2"))
    }

    @Test
    fun `keeps the fragment`() {
        assertEquals("https://x.io/a.mp4#t=30", ResyncMarker.strip("https://x.io/a.mp4?ddsync=1#t=30"))
        assertEquals("https://x.io/a?v=1#frag", ResyncMarker.strip("https://x.io/a?v=1&ddsync=2#frag"))
    }

    @Test
    fun `leaves other URLs untouched`() {
        for (url in listOf(
            "https://x.io/a.mp4", "https://x.io/a.mp4?x=1", "https://x.io/a?ddsyncx=1", "https://x.io/a?myddsync=1",
            "https://x.io/a#ddsync=1", "", "https://x.io/a?ddsync=abc",
        )) assertEquals(url, ResyncMarker.strip(url))
    }
}
