package com.dreamdisplays.media.player.managers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TempoSwitchTest {
    @Test
    fun `an in-process decode keeps running up to the speed it serves`() {
        assertTrue(videoKeepsPace(inProcess = true, sourceFps = 30.0, fromTempo = 1.0, toTempo = 2.0, alpha = false))
        assertTrue(videoKeepsPace(inProcess = true, sourceFps = 60.0, fromTempo = 1.5, toTempo = 0.5, alpha = false))
        assertFalse(videoKeepsPace(inProcess = true, sourceFps = 30.0, fromTempo = 1.0, toTempo = 2.5, alpha = false))
    }

    @Test
    fun `a decode process keeps running while it keeps the same frame rate`() {
        // 30 fps at 1.2x is 36 pictures a second: still every frame
        assertTrue(videoKeepsPace(inProcess = false, sourceFps = 30.0, fromTempo = 1.0, toTempo = 1.2, alpha = false))
        assertTrue(videoKeepsPace(inProcess = false, sourceFps = 30.0, fromTempo = 0.5, toTempo = 2.0, alpha = false))
        // 60 fps past 1x thins frames out: another rate, so the process is reopened
        assertFalse(videoKeepsPace(inProcess = false, sourceFps = 60.0, fromTempo = 1.0, toTempo = 1.5, alpha = false))
        assertFalse(videoKeepsPace(inProcess = false, sourceFps = 30.0, fromTempo = 1.0, toTempo = 4.0, alpha = false))
        // Alpha video always decodes in a process
        assertFalse(videoKeepsPace(inProcess = true, sourceFps = 60.0, fromTempo = 1.0, toTempo = 1.5, alpha = true))
    }

    @Test
    fun `the sound picks up where the old speed reaches at the instant`() {
        val ms = 1_000_000L
        assertEquals(60 * ms, tempoSwitchShiftNanos(60 * ms, fromRate = 1.0, toRate = 2.0))
        assertEquals(120 * ms, tempoSwitchShiftNanos(60 * ms, fromRate = 2.0, toRate = 1.0))
        assertEquals(0L, tempoSwitchShiftNanos(0L, fromRate = 1.0, toRate = 2.0))
    }

    @Test
    fun `the new-speed sound opens a margin before where the old speed reaches at the instant, never behind the playhead`() {
        val ms = 1_000_000L
        // 400 ms ahead at 2x: the switch lands 800 ms on; the stream opens 150 ms before that
        assertEquals(10_650 * ms, tempoSwitchOpenNanos(10_000 * ms, 400 * ms, fromRate = 2.0))
        // Too close to the instant for the margin: from the playhead
        assertEquals(10_000 * ms, tempoSwitchOpenNanos(10_000 * ms, 50 * ms, fromRate = 1.0))
        // Already late
        assertEquals(10_000 * ms, tempoSwitchOpenNanos(10_000 * ms, -80 * ms, fromRate = 1.0))
    }

    @Test
    fun `a late switch catches up a speed-up and never goes back for a slow-down`() {
        val ms = 1_000_000L
        // 100 ms late at 1x -> 2x: the new speed would be 100 ms of content further
        assertEquals(100 * ms, tempoSwitchShiftNanos(-100 * ms, fromRate = 1.0, toRate = 2.0))
        assertEquals(20 * ms, tempoSwitchShiftNanos(-100 * ms, fromRate = 1.0, toRate = 1.2))
        assertEquals(0L, tempoSwitchShiftNanos(-100 * ms, fromRate = 2.0, toRate = 1.0))
    }
}
