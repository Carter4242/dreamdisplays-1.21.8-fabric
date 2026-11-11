package com.dreamdisplays.media.player.managers

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioFaultBudgetTest {
    private val s = 1_000_000_000L

    @Test
    fun `spaced-out faults always restart in place`() {
        val b = AudioFaultBudget(maxRestarts = 3, windowNanos = 10 * s)
        for (i in 1..20) assertTrue(b.allowRestart(i * 60 * s))
    }

    @Test
    fun `a fault that keeps recurring falls back after the budget, and recovers once it stops`() {
        val b = AudioFaultBudget(maxRestarts = 3, windowNanos = 10 * s)
        assertTrue(b.allowRestart(100 * s))
        assertTrue(b.allowRestart(101 * s))
        assertTrue(b.allowRestart(102 * s))
        assertFalse(b.allowRestart(103 * s))
        assertFalse(b.allowRestart(104 * s))
        assertTrue(b.allowRestart(200 * s))
        b.reset()
        assertTrue(b.allowRestart(201 * s))
    }
}
