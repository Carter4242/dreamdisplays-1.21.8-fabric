package com.dreamdisplays.media.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WarmUpHoldTest {
    @Test
    fun `a warm-up parks exactly where it opened, whatever the speed or scheduling delay`() {
        // The harness case: opened at 710 ms at 2x; parking 400 ms later from the control thread used to land at 1510
        for (speed in [0.5, 1.0, 2.0, 4.0]) {
            for (delayMs in [0L, 16L, 400L, 2_000L]) {
                val hold = WarmUpHold()
                hold.arm(710_000_000L)
                val clockAtPark = 710_000_000L + (delayMs * 1_000_000L * speed).toLong()
                var parkedAt = -1L
                assertTrue(hold.onPlayoutStart { at -> parkedAt = at; true })
                assertEquals(710_000_000L, parkedAt, "speed $speed, delay $delayMs ms (clock read $clockAtPark)")
                assertTrue(hold.disarm(), "The control thread learns the session is already held.")
            }
        }
    }

    @Test
    fun `only an armed warm-up is parked, and only once`() {
        val hold = WarmUpHold()
        var parks = 0
        assertFalse(hold.onPlayoutStart { parks++; true }, "Nothing armed: an ordinary start plays on.")
        hold.arm(5_000L)
        assertTrue(hold.onPlayoutStart { parks++; true })
        assertFalse(hold.onPlayoutStart { parks++; true }, "A second first-frame call (a seek in place) must not park.")
        assertEquals(1, parks)
    }

    @Test
    fun `a play or pause that comes first cancels the park`() {
        val hold = WarmUpHold()
        hold.arm(5_000L)
        assertFalse(hold.disarm(), "Nothing was held yet.")
        assertFalse(hold.onPlayoutStart { error("must not park after the warm-up was cancelled") })
    }

    @Test
    fun `a park the session refused is not reported as held`() {
        val hold = WarmUpHold()
        hold.arm(5_000L)
        assertFalse(hold.onPlayoutStart { false })
        assertFalse(hold.disarm(), "The control thread falls back to parking it itself.")
    }

    @Test
    fun `a negative target parks at zero`() {
        val hold = WarmUpHold()
        hold.arm(-40L)
        var at = -1L
        hold.onPlayoutStart { at = it; true }
        assertEquals(0L, at)
    }
}
