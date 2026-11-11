package com.dreamdisplays.core.sync

import com.dreamdisplays.core.display.policy.SyncSettings
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineEstimatorTest {
    @Test
    fun `playing target projects forward with latency compensation`() {
        val e = TimelineEstimator()
        assertTrue(e.onPacket(10_000, paused = false, nowMs = 1_000.0, latencyCompMs = 25.0))
        assertEquals(10_025.0, e.targetMs(1_000.0))
        assertEquals(12_025.0, e.targetMs(3_000.0))
    }

    @Test
    fun `a late packet does not pull the estimate back`() {
        val e = TimelineEstimator()
        e.onPacket(10_000, false, 1_000.0, 0.0)
        // Sent 5 s later but handled 80 ms late: it implies an earlier timeline, so the first one still wins
        assertFalse(e.onPacket(15_000, false, 6_080.0, 0.0))
        assertEquals(15_080.0, e.targetMs(6_080.0))
        // A punctual one that implies a later timeline is taken at once
        e.onPacket(20_010, false, 11_000.0, 0.0)
        assertEquals(20_010.0, e.targetMs(11_000.0))
    }

    @Test
    fun `old observations expire so clock-rate differences cannot accumulate`() {
        val e = TimelineEstimator(windowMs = 30_000.0)
        e.onPacket(0, false, 0.0, 0.0) // offset 0
        e.onPacket(39_960, false, 40_000.0, 0.0) // offset -40, first one is now outside the window
        assertEquals(40_960.0, e.targetMs(41_000.0))
    }

    @Test
    fun `jumps and pause changes start a new segment`() {
        val e = TimelineEstimator()
        e.onPacket(10_000, false, 0.0, 0.0)
        assertTrue(e.onPacket(500, false, 5_000.0, 0.0)) // loop wrap / seek
        assertEquals(500.0, e.targetMs(5_000.0))
        assertTrue(e.onPacket(600, true, 5_100.0, 30.0))
        assertTrue(e.paused)
        assertEquals(600.0, e.targetMs(99_000.0)) // paused: frozen, no latency compensation
        assertFalse(e.onPacket(600, true, 6_000.0, 0.0))
        assertTrue(e.onPacket(700, true, 6_100.0, 0.0)) // paused seek
        assertTrue(e.onPacket(700, false, 7_000.0, 0.0))
        assertFalse(e.paused)
    }

    @Test
    fun `reset forgets the timeline`() {
        val e = TimelineEstimator()
        e.onPacket(1_000, false, 0.0, 0.0)
        e.reset()
        assertFalse(e.hasTimeline)
        assertTrue(e.onPacket(1_000, false, 0.0, 0.0))
    }
}

class DriftControllerTest {
    private val settings = SyncSettings(deadBandMs = 40, maxRatePct = 1.0, seekThresholdMs = 1_500, seekCooldownMs = 8_000)

    @Test
    fun `inside the dead band nothing happens`() {
        val c = DriftController(settings)
        for (t in 0..100) assertEquals(DriftController.Decision(1.0, false), c.update(30.0, t * 50.0))
    }

    @Test
    fun `outside the dead band the rate moves proportionally and the right way`() {
        val behind = DriftController(settings)
        val d = behind.update(80.0, 0.0)
        assertFalse(d.seek)
        assertEquals(1.0 + 80.0 / DriftController.CONVERGE_MS, d.ratio, 1e-9)
        val ahead = DriftController(settings)
        assertTrue(ahead.update(-80.0, 0.0).ratio < 1.0)
    }

    @Test
    fun `cheap seeks take over from the rate a lot earlier`() {
        // 700 ms behind: rate correction at 1 % would need over a minute
        val slow = DriftController(settings)
        assertFalse(slow.update(700.0, 0.0).seek)
        val cheap = DriftController(settings)
        assertTrue(cheap.update(700.0, 0.0, cheapSeeks = true).seek)
        // Below the cheap threshold the rate still does it
        val small = DriftController(settings)
        assertFalse(small.update(200.0, 0.0, cheapSeeks = true).seek)
        // And the cooldown between cheap seeks is short, not the configured 8 s
        assertFalse(cheap.update(700.0, 3_000.0, cheapSeeks = true).seek)
        assertTrue(cheap.update(700.0, 4_000.0, cheapSeeks = true).seek)
    }

    @Test
    fun `rate is capped at the configured maximum`() {
        val c = DriftController(settings)
        assertEquals(1.01, c.update(1_000.0, 0.0).ratio, 1e-12)
        val gentle = DriftController(settings.copy(maxRatePct = 0.5))
        assertEquals(1.005, gentle.update(1_000.0, 0.0).ratio, 1e-12)
    }

    @Test
    fun `small drift still gets the minimum rate`() {
        val c = DriftController(settings.copy(deadBandMs = 5))
        assertEquals(1.0 + DriftController.MIN_RATE, c.update(6.0, 0.0).ratio, 1e-12)
    }

    @Test
    fun `hysteresis keeps correcting until settled`() {
        val c = DriftController(settings)
        c.update(60.0, 0.0)
        // Below the dead band but not yet settled: keep going
        assertTrue(c.update(30.0, 10_000.0).ratio > 1.0)
        assertTrue(c.update(20.0, 20_000.0).ratio > 1.0)
        assertEquals(1.0, c.update(10.0, 30_000.0).ratio)
        // And it does not restart until the dead band is crossed again
        assertEquals(1.0, c.update(35.0, 40_000.0).ratio)
    }

    @Test
    fun `disabled means no rate correction but seeks still happen`() {
        val c = DriftController(settings.copy(enabled = false))
        assertEquals(1.0, c.update(500.0, 0.0).ratio)
        assertTrue(DriftController(settings.copy(enabled = false)).update(5_000.0, 0.0).seek)
    }

    @Test
    fun `big drift seeks, then respects the cooldown`() {
        val c = DriftController(settings)
        assertTrue(c.update(2_000.0, 0.0).seek)
        assertEquals(null, c.filteredMs) // the measurement restarts after a seek
        val during = c.update(2_000.0, 1_000.0)
        assertFalse(during.seek)
        assertEquals(1.01, during.ratio, 1e-12) // full rate while waiting out the cooldown
        assertTrue(c.update(2_000.0, 8_000.0).seek)
    }

    @Test
    fun `back-to-back seeks stretch the cooldown and the stretch decays`() {
        val c = DriftController(settings)
        assertTrue(c.update(1_800.0, 0.0).seek)
        assertTrue(c.update(1_800.0, 8_000.0).seek) // streak 1 -> next cooldown 16 s
        assertFalse(c.update(1_800.0, 20_000.0).seek)
        assertTrue(c.update(1_800.0, 24_000.0).seek) // streak 2 -> 24 s
        assertFalse(c.update(1_800.0, 40_000.0).seek)
        assertTrue(c.update(1_800.0, 48_000.0).seek) // streak 3 -> 32 s
        assertTrue(c.update(1_800.0, 80_000.0).seek) // > 30 s apart: streak forgotten
        assertEquals(8_000.0, c.currentCooldownMs())
    }

    @Test
    fun `the filter smooths a single outlier`() {
        val c = DriftController(settings)
        for (i in 0..20) c.update(0.0, i * 50.0)
        val d = c.update(400.0, 21 * 50.0)
        assertTrue(abs(c.filteredMs!!) < 50.0, "filtered ${c.filteredMs}")
        assertFalse(d.seek)
    }

    @Test
    fun `closed loop converges into the settle band without seeking`() {
        // Simulated client whose clock runs 0.07 % slow (about 1 s per 25 min), starting 300 ms behind
        val c = DriftController(settings)
        var drift = 300.0
        var t = 0.0
        var ratio = 1.0
        var maxAfter = 0.0
        repeat(20 * 60 * 10) { // 10 minutes of 50 ms ticks
            drift += 50.0 * (0.0007 - (ratio - 1.0))
            val d = c.update(drift, t)
            assertFalse(d.seek)
            ratio = d.ratio
            t += 50.0
            if (t > 120_000.0) maxAfter = maxOf(maxAfter, abs(drift))
        }
        assertTrue(maxAfter <= 45.0, "drift after settling reached $maxAfter ms")
    }
}
