package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LatestTimerTest {
    @Test
    fun `a timer already queued when it was re-armed does not act`() {
        val t = LatestTimer()
        val first = t.arm()
        val second = t.arm()
        assertFalse(t.isLatest(first), "The superseded timer would apply a stale timeline.")
        assertTrue(t.isLatest(second))
    }
}
