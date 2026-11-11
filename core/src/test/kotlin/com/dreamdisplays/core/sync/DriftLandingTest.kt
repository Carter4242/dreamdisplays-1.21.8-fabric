package com.dreamdisplays.core.sync

import com.dreamdisplays.core.display.policy.SyncSettings
import kotlin.test.Test
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
    fun `outlier or stalled landings are not learned`() {
        assertTrue(DriftController.learnsFromLanding(367.0, 0.0))
        assertTrue(DriftController.learnsFromLanding(-283.0, 120.0))
        assertFalse(DriftController.learnsFromLanding(6_372.0, 0.0))
        assertFalse(DriftController.learnsFromLanding(300.0, 9_000.0))
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
