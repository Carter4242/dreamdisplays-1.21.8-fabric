package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmptyLoadTest {
    @Test
    fun `an empty link stops the player that was showing something`() {
        assertTrue(emptyLoadUnloads(shownUrl = "srt://relay/abc", hasPlayer = true))
        assertTrue(emptyLoadUnloads(shownUrl = "https://a", hasPlayer = true))
    }

    @Test
    fun `a new empty display learns it is empty once`() {
        assertTrue(emptyLoadUnloads(shownUrl = null, hasPlayer = false))
        assertFalse(emptyLoadUnloads(shownUrl = "", hasPlayer = false), "An empty display would reload every second.")
    }
}
