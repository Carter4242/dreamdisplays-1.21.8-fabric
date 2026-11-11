package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerMediaGensTest {
    @Test
    fun `a timeline from an older server never asks for the display`() {
        assertFalse(timelineNeedsDescription(0L, null))
        assertFalse(timelineNeedsDescription(0L, 7L))
    }

    @Test
    fun `a timeline for the described video is followed as is`() {
        assertFalse(timelineNeedsDescription(7L, 7L))
    }

    @Test
    fun `a timeline for another video asks for the display`() {
        assertTrue(timelineNeedsDescription(8L, 7L))
    }

    @Test
    fun `a timeline for a display never described asks for it`() {
        assertTrue(timelineNeedsDescription(8L, null))
    }
}
