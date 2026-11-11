package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObservedEndTest {
    @Test
    fun `a stream with no header length reports where it ended`() {
        assertEquals(312_480L, observedEndToReport(positionMs = 312_480, sampleAgeMs = 40, metadataMs = 0))
    }

    @Test
    fun `a header length rounded down to the second is left alone`() {
        assertNull(observedEndToReport(positionMs = 300_900, sampleAgeMs = 40, metadataMs = 300_000))
    }

    @Test
    fun `a header length that is off is corrected`() {
        assertEquals(240_000L, observedEndToReport(positionMs = 240_000, sampleAgeMs = 40, metadataMs = 300_000))
    }

    @Test
    fun `a stale or missing position says nothing`() {
        assertNull(observedEndToReport(positionMs = 120_000, sampleAgeMs = 2_000, metadataMs = 0))
        assertNull(observedEndToReport(positionMs = 0, sampleAgeMs = 10, metadataMs = 0))
    }
}
