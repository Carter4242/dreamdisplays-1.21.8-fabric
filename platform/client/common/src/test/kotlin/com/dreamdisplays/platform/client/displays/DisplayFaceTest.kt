package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayFaceTest {
    private val url = "https://youtu.be/x"

    @Test
    fun aDisplayWithoutAVideoIsIdleNotLoading() {
        // New (no packet URL yet) and cleared ("") displays alike: the frozen bar, never the animated loading one
        assertEquals(DisplayFace.IDLE, DisplayFace.of(videoUrl = null, pictureReady = false, errored = false))
        assertEquals(DisplayFace.IDLE, DisplayFace.of(videoUrl = "", pictureReady = false, errored = false))
    }

    @Test
    fun aDisplayWithoutAVideoNeverShowsAnError() {
        assertEquals(DisplayFace.IDLE, DisplayFace.of(videoUrl = "", pictureReady = false, errored = true))
        assertEquals(DisplayFace.IDLE, DisplayFace.of(videoUrl = null, pictureReady = false, errored = true))
    }

    @Test
    fun aSetVideoNotOnScreenYetIsLoading() {
        assertEquals(DisplayFace.LOADING, DisplayFace.of(videoUrl = url, pictureReady = false, errored = false))
    }

    @Test
    fun aSetVideoThatFailedShowsTheError() {
        assertEquals(DisplayFace.ERROR, DisplayFace.of(videoUrl = url, pictureReady = false, errored = true))
    }

    @Test
    fun aPictureOnScreenAlwaysPlays() {
        assertEquals(DisplayFace.PLAYING, DisplayFace.of(videoUrl = url, pictureReady = true, errored = false))
        // A picture already up is never covered by the error look (same as before this state existed)
        assertEquals(DisplayFace.PLAYING, DisplayFace.of(videoUrl = url, pictureReady = true, errored = true))
    }
}
