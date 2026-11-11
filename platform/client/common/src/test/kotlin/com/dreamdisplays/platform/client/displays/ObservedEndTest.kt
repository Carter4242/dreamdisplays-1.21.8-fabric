package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObservedEndTest {
    @Test
    fun `a stream with no header length reports where it ended`() {
        assertEquals(312_520L, observedEndToReport(positionMs = 312_480, sampleAgeMs = 40, metadataMs = 0))
    }

    @Test
    fun `a header length rounded to whole seconds is replaced by the real end`() {
        assertEquals(300_940L, observedEndToReport(positionMs = 300_900, sampleAgeMs = 40, metadataMs = 300_000))
    }

    @Test
    fun `a precise header length that matches is left alone`() {
        assertNull(observedEndToReport(positionMs = 300_400, sampleAgeMs = 40, metadataMs = 300_437))
    }

    @Test
    fun `a header length that is off is corrected`() {
        assertEquals(240_040L, observedEndToReport(positionMs = 240_000, sampleAgeMs = 40, metadataMs = 300_437))
    }

    @Test
    fun `a game stall over the end still measures it from the time played since`() {
        // No header length: the only report the server will ever get
        assertEquals(10_476L, observedEndToReport(positionMs = 9_876, sampleAgeMs = 600, metadataMs = 0))
        assertEquals(11_076L, observedEndToReport(positionMs = 9_876, sampleAgeMs = 600, metadataMs = 0, rate = 2.0))
        // With a header, a stale sample is not trusted to correct it
        assertNull(observedEndToReport(positionMs = 120_000, sampleAgeMs = 2_000, metadataMs = 300_437))
    }

    @Test
    fun `a missing or ancient position says nothing`() {
        assertNull(observedEndToReport(positionMs = 0, sampleAgeMs = 10, metadataMs = 0))
        assertNull(observedEndToReport(positionMs = 120_000, sampleAgeMs = 60_000, metadataMs = 0))
    }
}
