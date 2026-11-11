package com.dreamdisplays.core.karaoke

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KaraokeAutoTrimTest {
    @Test
    fun lowConfidenceAndWildReportsAreIgnored() {
        val c = AutoTrimController()
        repeat(10) { assertFalse(c.onReport(200.0, 0.2, it * 10_000L)) }
        assertFalse(c.onReport(5_000.0, 0.9, 200_000L))
        assertFalse(c.onReport(Double.NaN, 0.9, 210_000L))
        assertEquals(0, c.reports)
        assertEquals(0L, c.trimMs)
    }

    @Test
    fun needsAFewReportsThenStepsAtMostTwentyMsEveryFiveSeconds() {
        val c = AutoTrimController()
        assertFalse(c.onReport(200.0, 0.8, 0L))
        assertFalse(c.onReport(200.0, 0.8, 2_000L))
        // The third report gives a median: the voice is 200 ms late, so it goes out earlier, 20 ms at a time
        assertTrue(c.onReport(200.0, 0.8, 4_000L))
        assertEquals(-20L, c.trimMs)
        assertFalse(c.onReport(200.0, 0.8, 6_000L))
        assertEquals(-20L, c.trimMs)
        assertTrue(c.onReport(200.0, 0.8, 9_000L))
        assertEquals(-40L, c.trimMs)
        var t = 9_000L
        while (t < 120_000L) {
            t += 2_000L
            c.onReport(200.0, 0.8, t)
        }
        assertEquals(-200L, c.trimMs)
        // There it stays
        assertFalse(c.onReport(200.0, 0.8, t + 10_000L))
        assertEquals(-200L, c.trimMs)
    }

    @Test
    fun theTrimIsClamped() {
        val c = AutoTrimController()
        var t = 0L
        repeat(500) {
            c.onReport(950.0, 0.9, t)
            t += 5_000L
        }
        assertEquals(-600L, c.trimMs)
    }

    @Test
    fun aMedianShrugsOffOneBadWindow() {
        val c = AutoTrimController()
        listOf(100.0, 104.0, 98.0, -700.0, 102.0).forEachIndexed { i, offset -> c.onReport(offset, 0.8, i * 1_000L) }
        assertEquals(100.0, c.medianOffsetMs())
    }

    @Test
    fun anEarlyVoiceIsHeldBack() {
        val c = AutoTrimController()
        repeat(3) { c.onReport(-80.0, 0.7, it * 1_000L) }
        assertEquals(20L, c.trimMs)
    }

    @Test
    fun aNewSongStartsTheReportsAfreshButKeepsTheTrim() {
        val c = AutoTrimController()
        repeat(3) { c.onReport(100.0, 0.7, it * 1_000L) }
        assertEquals(-20L, c.trimMs)
        c.resetReports()
        assertEquals(0, c.reports)
        assertEquals(-20L, c.trimMs)
        assertFalse(c.onReport(-300.0, 0.7, 60_000L)) // one report is not enough again
    }
}
