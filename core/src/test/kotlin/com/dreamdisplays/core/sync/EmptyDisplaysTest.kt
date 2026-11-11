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

    @Test
    fun `clients older than protocol 12 drop a cleared display first, newer ones stop it themselves`() {
        assertTrue(EmptyDisplays.dropBeforeClear(11))
        assertTrue(EmptyDisplays.dropBeforeClear(0))
        assertFalse(EmptyDisplays.dropBeforeClear(12))
        assertFalse(EmptyDisplays.dropBeforeClear(13))
    }

    @Test
    fun `an empty display is never described to a client that can't take an empty url`() {
        kotlin.test.assertTrue(EmptyDisplays.hiddenFrom("", 0))
        kotlin.test.assertTrue(EmptyDisplays.hiddenFrom("  ", 11))
        kotlin.test.assertFalse(EmptyDisplays.hiddenFrom("", 12))
        kotlin.test.assertFalse(EmptyDisplays.hiddenFrom("https://youtu.be/x", 0))
    }
}
