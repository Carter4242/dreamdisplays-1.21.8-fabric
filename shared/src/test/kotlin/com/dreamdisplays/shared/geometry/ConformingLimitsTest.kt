package com.dreamdisplays.shared.geometry

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConformingLimitsTest {
    @Test
    fun theDefaultSizeLimitsAlwaysFit() {
        assertTrue(ConformingLimits.fits(32, 24, 32))
        assertTrue(ConformingLimits.fits(64, 64, 64))
    }

    @Test
    fun volumeNotAnySingleSideIsWhatIsBounded() {
        assertFalse(ConformingLimits.fits(256, 256, 256))
        assertFalse(ConformingLimits.fits(128, 72, 64))
        assertTrue(ConformingLimits.fits(256, 256, 1))
    }
}
