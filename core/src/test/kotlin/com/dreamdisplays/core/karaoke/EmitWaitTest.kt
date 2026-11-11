package com.dreamdisplays.core.karaoke

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmitWaitTest {
    private val ms = 1_000_000L

    @Test
    fun aFrameWithinAMillisecondOfItsTimeIsDue() {
        assertTrue(EmitWait.due(100 * ms, 99 * ms))
        assertTrue(EmitWait.due(100 * ms, 101 * ms))
        assertFalse(EmitWait.due(100 * ms, 98 * ms))
    }

    @Test
    fun onlyAQuarterMillisecondIsSpun() {
        assertEquals(20 * ms - EmitWait.DUE_EARLY_NANOS - EmitWait.SPIN_NANOS, EmitWait.sleepNanos(20 * ms, 0))
        assertEquals(0L, EmitWait.sleepNanos(ms + EmitWait.SPIN_NANOS, 0))
        assertEquals(0L, EmitWait.sleepNanos(0, 5 * ms))
    }
}
