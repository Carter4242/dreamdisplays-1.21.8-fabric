package com.dreamdisplays.core.display.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolicyGainRampTest {
    private val ms = 1_000_000L

    @Test
    fun `target follows the policy gain except for singers`() {
        val ducked = DisplayPolicy.EMPTY.withGain(0.8)
        assertEquals(0.8, PolicyGainRamp.targetFor(ducked, singing = false))
        assertEquals(1.0, PolicyGainRamp.targetFor(ducked, singing = true))
        assertEquals(1.0, PolicyGainRamp.targetFor(DisplayPolicy.EMPTY, singing = false))
        assertEquals(1.0, PolicyGainRamp.targetFor(null, singing = false))
    }

    @Test
    fun `gain ramps linearly over 300 ms and stays there`() {
        val ramp = PolicyGainRamp()
        assertEquals(1.0, ramp.value(0))
        ramp.retarget(0.8, 1_000 * ms)
        assertEquals(1.0, ramp.value(1_000 * ms), 1e-9)
        assertEquals(0.9, ramp.value(1_150 * ms), 1e-9)
        assertEquals(0.8, ramp.value(1_300 * ms), 1e-9)
        assertEquals(0.8, ramp.value(5_000 * ms), 1e-9)
    }

    @Test
    fun `retarget mid ramp starts from the current value`() {
        val ramp = PolicyGainRamp()
        ramp.retarget(0.8, 0)
        ramp.retarget(1.0, 150 * ms) // at 0.9
        assertEquals(0.9, ramp.value(150 * ms), 1e-9)
        assertEquals(0.95, ramp.value(300 * ms), 1e-9)
        assertEquals(1.0, ramp.value(450 * ms), 1e-9)
        ramp.retarget(1.0, 500 * ms) // same target: no new ramp
        assertEquals(1.0, ramp.value(500 * ms), 1e-9)
    }

    @Test
    fun `steps per tick stay small and the final value is always applied`() {
        val ramp = PolicyGainRamp()
        assertFalse(ramp.pollChanged(0))
        ramp.retarget(0.8, 0)
        var last = 1.0
        var t = 50 * ms
        var changes = 0
        while (t <= 400 * ms) {
            if (ramp.pollChanged(t)) changes++
            val v = ramp.value(t)
            assertTrue(last - v <= 0.034 + 1e-9, "step ${last - v} at $t")
            last = v
            t += 50 * ms
        }
        assertEquals(6, changes)
        assertEquals(0.8, last, 1e-9)
        assertFalse(ramp.pollChanged(450 * ms))
    }

    @Test
    fun `gain is clamped`() {
        val ramp = PolicyGainRamp(rampNanos = 0)
        ramp.retarget(5.0, 0)
        assertEquals(2.0, ramp.value(0))
        ramp.retarget(-1.0, 0)
        assertEquals(0.0, ramp.value(0))
    }
}
