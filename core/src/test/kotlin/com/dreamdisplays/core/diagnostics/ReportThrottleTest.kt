package com.dreamdisplays.core.diagnostics

import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReportThrottleTest {
    private val a = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val b = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    @Test
    fun `identical reports are deduplicated and counted`() {
        val t = ReportThrottle(dedupeWindowMs = 60_000L)
        val sig = ReportThrottle.signature("error", "http_status", "NETWORK", "Stream refused (403)", 0, false)
        assertEquals(0, t.admit(a, sig, 0L))
        assertNull(t.admit(a, sig, 1_000L))
        assertNull(t.admit(a, sig, 2_000L))
        // Window over: goes out again and says two were held back
        assertEquals(2, t.admit(a, sig, 61_000L))
        // Another display is not a duplicate
        assertEquals(0, t.admit(b, sig, 61_000L))
    }

    @Test
    fun `each retry of a sequence is its own report`() {
        val t = ReportThrottle()
        (0..2).forEach { retry ->
            assertEquals(0, t.admit(a, ReportThrottle.signature("error", "resolve", "UNKNOWN", "x", retry, false), retry * 10L))
        }
        assertEquals(0, t.admit(a, ReportThrottle.signature("error", "resolve", "UNKNOWN", "x", 2, true), 40L))
    }

    @Test
    fun `long numbers do not defeat dedupe`() {
        assertEquals(
            ReportThrottle.signature("error", "resolve", "TIMEOUT", "Timed out after 25000 ms", 0, false),
            ReportThrottle.signature("error", "resolve", "TIMEOUT", "Timed out after 26120 ms", 0, false),
        )
    }

    @Test
    fun `per display and total rates are capped per minute`() {
        val t = ReportThrottle(perDisplayPerMinute = 3, totalPerMinute = 5)
        val sent = (0 until 10).count { t.admit(a, "s$it", it * 100L) != null }
        assertEquals(3, sent)
        assertEquals(2, (0 until 10).count { t.admit(b, "s$it", 2_000L + it) != null })
        // A minute later both budgets are back
        assertEquals(0, t.admit(a, "fresh", 70_000L))
    }
}
