package com.dreamdisplays.core.sync

import com.dreamdisplays.core.display.policy.SyncSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `sync.correctiveSeeks` / `sync.cheapSeeks`: the server can calm a client that keeps seeking. */
class SeekLeversTest {
    private val base = SyncSettings(deadBandMs = 40, maxRatePct = 1.0, seekThresholdMs = 1_500, seekCooldownMs = 8_000)

    @Test
    fun `cheap streams seek past 300 ms by default`() {
        val c = DriftController(base)
        assertTrue(c.update(400.0, 0.0, cheapSeeks = true).seek)
        assertTrue(c.cheapSeeksAllowed(true))
    }

    @Test
    fun `without cheap seeks the configured threshold and cooldown apply`() {
        val c = DriftController(base.copy(cheapSeeks = false, seekThresholdMs = 60_000, seekCooldownMs = 600_000))
        val d = c.update(400.0, 0.0, cheapSeeks = true)
        assertFalse(d.seek)
        assertTrue(d.ratio > 1.0) // still closed by rate
        assertFalse(c.cheapSeeksAllowed(true))
        assertFalse(c.update(5_000.0, 10_000.0, cheapSeeks = true).seek)
    }

    @Test
    fun `without corrective seeks nothing is ever seeked away, only rate-corrected`() {
        val c = DriftController(base.copy(correctiveSeeks = false))
        for (t in 0..100) {
            val d = c.update(10_000.0, t * 1_000.0, cheapSeeks = t % 2 == 0)
            assertFalse(d.seek)
        }
        assertEquals(1.01, c.ratio, 1e-9)
        assertFalse(c.landingNeedsReseek(6_000.0))
        assertFalse(c.reaimsLanding(6_000.0, afterReaim = false))
        assertFalse(c.cheapSeeksAllowed(true))
    }

    @Test
    fun `rate correction off as well leaves drift alone`() {
        val c = DriftController(base.copy(correctiveSeeks = false, enabled = false))
        val d = c.update(10_000.0, 0.0)
        assertFalse(d.seek)
        assertEquals(1.0, d.ratio)
    }
}
