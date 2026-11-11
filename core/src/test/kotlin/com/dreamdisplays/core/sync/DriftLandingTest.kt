package com.dreamdisplays.core.sync

import com.dreamdisplays.core.display.policy.SyncSettings
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DriftLandingTest {
    private val settings = SyncSettings(deadBandMs = 40, maxRatePct = 1.0, seekThresholdMs = 1_500, seekCooldownMs = 8_000)

    @Test
    fun `a landing seconds off is re-aimed, a near one is not`() {
        val c = DriftController(settings)
        assertTrue(c.landingNeedsReseek(6_372.0))
        assertTrue(c.landingNeedsReseek(-1_200.0))
        assertFalse(c.landingNeedsReseek(367.0))
        // A seek threshold below a second re-aims from the threshold on
        assertTrue(DriftController(settings.copy(seekThresholdMs = 500)).landingNeedsReseek(600.0))
    }

    @Test
    fun `stalled, new-target or lone far-off landings are not learned`() {
        val l = SeekLeadLearner(400L, 3_000L)
        assertEquals(SeekLeadLearner.Verdict.LEARNED, l.onLanding(400L, 367.0, 0.0))
        assertEquals(SeekLeadLearner.Verdict.STALLED, l.onLanding(l.leadMs, 300.0, 9_000.0))
        assertEquals(SeekLeadLearner.Verdict.NEW_TARGET, l.onLanding(l.leadMs, -23_103.0, 0.0))
        val before = l.leadMs
        assertEquals(SeekLeadLearner.Verdict.UNCONFIRMED, l.onLanding(before, 1_400.0, 0.0))
        assertEquals(before, l.leadMs)
        // A second landing implying the same cost confirms it
        assertEquals(SeekLeadLearner.Verdict.LEARNED, l.onLanding(before, 1_300.0, 0.0))
        assertTrue(abs(l.leadMs - (before + 1_350)) <= 1)
    }

    @Test
    fun `consistent 1_8 s reopen seeks converge in at most 2 seeks, then rate correction only`() {
        val learner = SeekLeadLearner(400L, 3_000L)
        val c = DriftController(settings)
        val costMs = 1_800.0
        var drift = 1_600.0 // behind by more than the seek threshold
        var seeks = 0
        var reaims = 0
        var now = 0.0
        val dt = 50.0
        val seekLog = mutableListOf<Double>()
        fun land(lead: Long, afterReaim: Boolean): Double {
            val landing = costMs - lead + (seeks % 2) * 40.0 // a little jitter
            learner.onLanding(lead, landing, 0.0, isReaim = afterReaim)
            return landing
        }
        while (now < 120_000.0) {
            val d = c.update(drift, now, cheapSeeks = false)
            if (d.seek) {
                seeks++; seekLog += now
                val lead = learner.leadMs
                drift = land(lead, afterReaim = false)
                if (c.reaimsLanding(drift, afterReaim = false)) {
                    c.noteSeek(now)
                    seeks++; reaims++
                    drift = land(learner.impliedLeadMs(lead, drift), afterReaim = true)
                    assertFalse(c.reaimsLanding(drift, afterReaim = true))
                }
                c.resetMeasurement()
            } else {
                drift -= (d.ratio - 1.0) * dt
            }
            now += dt
        }
        assertTrue(seeks <= 2, "seeks=$seeks at $seekLog")
        assertTrue(reaims <= 1)
        assertTrue(abs(learner.leadMs - costMs) <= 100, "lead=${learner.leadMs}")
        assertTrue(abs(drift) < 100, "drift=$drift")
    }

    @Test
    fun `measured-cheap streams keep the low threshold, expensive ones the normal one`() {
        assertTrue(DriftController(settings).update(400.0, 0.0, cheapSeeks = true).seek)
        val expensive = DriftController(settings)
        assertFalse(expensive.update(400.0, 0.0, cheapSeeks = false).seek)
        assertFalse(expensive.update(1_400.0, 1_000.0, cheapSeeks = false).seek)
    }

    @Test
    fun `re-aim fires at most once per episode`() {
        val c = DriftController(settings)
        assertTrue(c.reaimsLanding(1_800.0, afterReaim = false))
        assertFalse(c.reaimsLanding(1_800.0, afterReaim = true))
        assertFalse(c.reaimsLanding(-23_103.0, afterReaim = true))
        // The far-off re-aim landing is accepted and learned
        val l = SeekLeadLearner(400L, 3_000L)
        assertEquals(SeekLeadLearner.Verdict.LEARNED, l.onLanding(400L, 1_800.0, 0.0, isReaim = true))
        assertTrue(l.leadMs > 1_500L)
    }

    @Test
    fun `large drift is not held back by the streak stretch`() {
        val c = DriftController(settings)
        assertTrue(c.update(1_800.0, 0.0).seek)
        assertTrue(c.update(1_800.0, 8_000.0).seek) // streak 1: cooldown now 16 s
        // Moderate drift waits out the stretch...
        assertFalse(c.update(1_800.0, 16_000.0).seek)
        // ...but seconds off seek as soon as the plain cooldown since the last seek has passed
        val far = DriftController(settings)
        assertTrue(far.update(6_000.0, 0.0).seek)
        assertTrue(far.update(6_000.0, 8_000.0).seek)
        assertFalse(far.update(6_400.0, 15_000.0).seek)
        assertTrue(far.update(6_400.0, 16_000.0).seek)
    }

    @Test
    fun `a corrective seek restarts the cooldown so misses cannot storm`() {
        val c = DriftController(settings)
        assertTrue(c.update(5_347.0, 0.0).seek)
        // Landed 6.4 s off at 2 s: re-aimed at once, outside the cooldown
        c.noteSeek(2_000.0)
        // Missed again: the controller waits the plain cooldown from the correction, then seeks
        c.resetMeasurement()
        assertFalse(c.update(6_000.0, 5_000.0).seek)
        assertFalse(c.update(6_000.0, 9_999.0).seek)
        assertTrue(c.update(6_000.0, 10_000.0).seek)
    }
}
