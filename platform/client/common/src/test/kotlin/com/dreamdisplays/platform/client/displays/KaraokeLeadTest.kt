package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KaraokeLeadTest {
    @Test
    fun `a singer's warm preload keeps its hold for the lead at any speed`() {
        assertTrue(heldForLead(driftMs = 700, toleranceMs = 250, leadMs = 700, rate = 1.0))
        // 2x: a 700 ms lead is 1400 ms of content
        assertTrue(heldForLead(driftMs = 1_400, toleranceMs = 250, leadMs = 700, rate = 2.0))
        assertFalse(heldForLead(driftMs = 1_400, toleranceMs = 250, leadMs = 700, rate = 1.0))
        assertFalse(heldForLead(driftMs = 200, toleranceMs = 250, leadMs = 700, rate = 1.0))
        assertFalse(heldForLead(driftMs = 700, toleranceMs = 250, leadMs = 0, rate = 1.0))
    }
}
