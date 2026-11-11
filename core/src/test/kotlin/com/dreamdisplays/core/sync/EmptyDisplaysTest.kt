package com.dreamdisplays.core.sync

import com.dreamdisplays.api.playback.model.PlaybackAction
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmptyDisplaysTest {
    @Test
    fun `an empty or blank URL is no video`() {
        assertFalse(EmptyDisplays.hasVideo(""))
        assertFalse(EmptyDisplays.hasVideo("  "))
        assertTrue(EmptyDisplays.hasVideo("https://youtu.be/x"))
    }

    @Test
    fun `only a play on an empty display with something queued starts the queue`() {
        assertTrue(EmptyDisplays.playStartsQueue("", PlaybackAction.PLAY, queueHasNext = true))
        assertFalse(EmptyDisplays.playStartsQueue("", PlaybackAction.PLAY, queueHasNext = false))
        assertFalse(EmptyDisplays.playStartsQueue("", PlaybackAction.SEEK, queueHasNext = true))
        assertFalse(EmptyDisplays.playStartsQueue("https://youtu.be/x", PlaybackAction.PLAY, queueHasNext = true))
    }
}
