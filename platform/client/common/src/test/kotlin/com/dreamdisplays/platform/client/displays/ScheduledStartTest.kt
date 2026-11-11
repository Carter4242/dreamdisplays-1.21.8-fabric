package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduledStartTest {
    @Test
    fun `a start run late by a stall counts the delay as played time`() {
        assertEquals(0.0, startLatenessMs(dueNanos = 5_000_000_000, nowNanos = 4_990_000_000))
        assertEquals(750.0, startLatenessMs(dueNanos = 5_000_000_000, nowNanos = 5_750_000_000))
    }

    @Test
    fun `a switch packet replayed late keeps how late it is instead of starting at its old position`() {
        assertEquals(120, replayedStartInMs(startInMs = 300, elapsedMs = 180.0))
        // 1050 ms after a 300 ms schedule: the start was 750 ms ago
        assertEquals(-750, replayedStartInMs(startInMs = 300, elapsedMs = 1_050.0))
        // Exactly due is never 0, which would read as "no scheduled start"
        assertEquals(-1, replayedStartInMs(startInMs = 300, elapsedMs = 300.0))
    }
}
