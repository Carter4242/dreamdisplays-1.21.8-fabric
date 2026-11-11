package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualDisplayLifetimeTest {
    @Test
    fun dismissedBroadcastIsDropped() {
        assertTrue(VirtualDisplayLifetime.isDone(virtual = true, wasShown = true, popoutOpen = false, prepared = false))
    }

    @Test
    fun broadcastNotShownYetIsKept() {
        // Its description arrived; the fullscreen state is still pending (world not settled yet)
        assertFalse(VirtualDisplayLifetime.isDone(virtual = true, wasShown = false, popoutOpen = false, prepared = false))
    }

    @Test
    fun broadcastStillOnScreenOrMinimizedIsKept() {
        assertFalse(VirtualDisplayLifetime.isDone(virtual = true, wasShown = true, popoutOpen = true, prepared = false))
    }

    @Test
    fun preparedBroadcastIsKept() {
        assertFalse(VirtualDisplayLifetime.isDone(virtual = true, wasShown = true, popoutOpen = false, prepared = true))
    }

    @Test
    fun realDisplaysAreLeftToTheDistanceUnload() {
        assertFalse(VirtualDisplayLifetime.isDone(virtual = false, wasShown = true, popoutOpen = false, prepared = false))
    }
}
