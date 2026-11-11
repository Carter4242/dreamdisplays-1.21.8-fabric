package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClearVideoOfferTest {
    @Test
    fun offeredToAViewerWhoMaySetTheVideoWhileThereIsOne() {
        // Nothing about the player's state: a video still loading or one that failed counts as "there is one"
        assertTrue(ClearVideoOffer.offered(supported = true, canSetVideo = true, videoUrl = "https://youtu.be/x"))
    }

    @Test
    fun notOfferedWithoutAVideo() {
        assertFalse(ClearVideoOffer.offered(supported = true, canSetVideo = true, videoUrl = null))
        assertFalse(ClearVideoOffer.offered(supported = true, canSetVideo = true, videoUrl = ""))
    }

    @Test
    fun notOfferedToAViewerWhoMayNotSetTheVideo() {
        assertFalse(ClearVideoOffer.offered(supported = true, canSetVideo = false, videoUrl = "https://youtu.be/x"))
    }

    @Test
    fun notOfferedOnAServerThatCannotClear() {
        assertFalse(ClearVideoOffer.offered(supported = false, canSetVideo = true, videoUrl = "https://youtu.be/x"))
    }
}
