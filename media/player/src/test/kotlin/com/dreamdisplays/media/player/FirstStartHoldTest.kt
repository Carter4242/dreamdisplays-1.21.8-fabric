package com.dreamdisplays.media.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstStartHoldTest {
    @Test
    fun `a display back in range whose timeline has not arrived does not open at the start`() {
        assertTrue(FirstStartHold.holds(requested = true, everStarted = false, live = false, seekable = true, startKnown = false, bootstrap = false))
    }

    @Test
    fun `nothing is held once a position is known, for live or unseekable streams, or after the first open`() {
        assertFalse(FirstStartHold.holds(requested = true, everStarted = false, live = false, seekable = true, startKnown = true, bootstrap = false))
        assertFalse(FirstStartHold.holds(requested = true, everStarted = false, live = false, seekable = true, startKnown = false, bootstrap = true))
        assertFalse(FirstStartHold.holds(requested = true, everStarted = false, live = true, seekable = true, startKnown = false, bootstrap = false))
        assertFalse(FirstStartHold.holds(requested = true, everStarted = false, live = false, seekable = false, startKnown = false, bootstrap = false))
        assertFalse(FirstStartHold.holds(requested = true, everStarted = true, live = false, seekable = true, startKnown = false, bootstrap = false))
        assertFalse(FirstStartHold.holds(requested = false, everStarted = false, live = false, seekable = true, startKnown = false, bootstrap = false))
    }

    @Test
    fun `a player waiting for its first open reports where it will open`() {
        assertEquals(15_600L, FirstStartHold.idlePositionNanos(everStarted = false, live = false, primedNanos = 15_600L, originNanos = 0L))
        assertEquals(0L, FirstStartHold.idlePositionNanos(everStarted = false, live = false, primedNanos = -1L, originNanos = 0L))
        assertEquals(7_000L, FirstStartHold.idlePositionNanos(everStarted = true, live = false, primedNanos = 15_600L, originNanos = 7_000L))
        assertEquals(0L, FirstStartHold.idlePositionNanos(everStarted = false, live = true, primedNanos = 15_600L, originNanos = 0L))
    }
}
