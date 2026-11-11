package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueuePreloaderTest {
    private val s = 1_000_000_000L

    private fun starts(started: Boolean, live: Boolean, holding: Boolean, durationNanos: Long, positionNanos: Long) =
        queuePreloadStartsNow(started, live, holding, durationNanos, positionNanos, leadNanos = 25 * s)

    @Test
    fun `starts while playing near the end`() {
        assertTrue(starts(true, false, false, 220 * s, 210 * s))
    }

    @Test
    fun `waits until within the lead`() {
        assertFalse(starts(true, false, false, 220 * s, 100 * s))
    }

    @Test
    fun `never starts during a start hold after a seek near the end`() {
        assertFalse(starts(true, false, holding = true, durationNanos = 220 * s, positionNanos = 210 * s))
    }

    @Test
    fun `never starts for live or unstarted video`() {
        assertFalse(starts(true, true, false, 220 * s, 210 * s))
        assertFalse(starts(false, false, false, 220 * s, 210 * s))
        assertFalse(starts(true, false, false, 0L, 0L))
    }
}
