package com.dreamdisplays.core.diagnostics

import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each player fills at most their share of the client error log; the rest is counted and logged as one line. */
class ReportByteBudgetTest {
    private val alice = UUID(0, 1)
    private val bob = UUID(0, 2)
    private val window = ReportByteBudget.DEFAULT_WINDOW_MS

    @Test
    fun `a modified client at the full report rate fills only its share`() {
        val budget = ReportByteBudget()
        // 30 reports a minute (the rate limit) of 15 KB each, for 20 minutes: what used to rotate the 5 MB file
        var written = 0L
        var suppressedLines = 0
        var suppressedReports = 0
        for (i in 0 until 600) {
            val now = i * 2_000L
            val decision = budget.admit(alice, 15_000, now)
            if (decision.allowed) written += 15_000
            decision.ended?.let { suppressedLines++; suppressedReports += it.reports }
        }
        // Two 10-minute windows, each letting four 15 KB reports through (60 KB of 64): 120 KB instead of 9 MB
        assertEquals(2 * 4 * 15_000L, written)
        assertEquals(1, suppressedLines, "the second window's count comes with the next report after it")
        assertEquals(300 - 4, suppressedReports)
        assertEquals(296, budget.forget(alice)?.reports, "the last window's count when they leave")
    }

    @Test
    fun `other players are unaffected`() {
        val budget = ReportByteBudget()
        repeat(100) { budget.admit(alice, 15_000, 1_000) }
        assertFalse(budget.admit(alice, 6_000, 1_000).allowed)
        assertTrue(budget.admit(bob, 15_000, 1_000).allowed)
        assertTrue(budget.admit(bob, 15_000, 1_000).allowed)
    }

    @Test
    fun `a normal player never notices`() {
        val budget = ReportByteBudget()
        // A bad evening: a 3 KB report every 30 s for three hours
        for (i in 0 until 360) {
            val decision = budget.admit(alice, 3_000, i * 30_000L)
            assertTrue(decision.allowed, "report $i")
            assertNull(decision.ended)
        }
        assertNull(budget.forget(alice))
    }

    @Test
    fun `the suppressed count covers its window and resets after`() {
        val budget = ReportByteBudget(maxBytes = 1_000, windowMs = 60_000)
        assertTrue(budget.admit(alice, 600, 0).allowed)
        assertFalse(budget.admit(alice, 600, 10).allowed)
        assertFalse(budget.admit(alice, 500, 20).allowed)
        assertTrue(budget.admit(alice, 400, 30).allowed, "a smaller line that still fits")
        val next = budget.admit(alice, 600, 60_000)
        assertTrue(next.allowed)
        val ended = assertNotNull(next.ended)
        assertEquals(ReportByteBudget.Suppressed(reports = 2, bytes = 1_100, sinceMs = 0, untilMs = 60_000), ended)
        assertNull(budget.admit(alice, 100, 60_001).ended, "said once")
    }

    @Test
    fun `pruning for room keeps a pending suppressed count`() {
        val budget = ReportByteBudget(maxBytes = 100, windowMs = 1_000, maxPlayers = 3)
        budget.admit(alice, 100, 0)
        assertFalse(budget.admit(alice, 100, 0).allowed)
        // Two more players, then one more once every window ended: the full-share ones go, alice's count stays
        budget.admit(UUID(1, 1), 10, 0)
        budget.admit(UUID(1, 2), 10, 0)
        budget.admit(UUID(1, 3), 10, 5_000)
        assertEquals(1, budget.forget(alice)?.reports)
    }

    @Test
    fun `utf8 length matches encoding`() {
        for (s in listOf("", "abc", "é", "日本語", "emoji 😀 end")) {
            assertEquals(s.toByteArray(Charsets.UTF_8).size, ReportByteBudget.utf8Length(s), s)
        }
    }

    @Test
    fun `the suppressed line is one JSON object`() {
        val line = ClientErrorLog.suppressedLine(0, "Alice", alice, 3, 45_000, 0, window, java.time.ZoneOffset.UTC)
        assertTrue(line.startsWith("{\"type\":\"suppressed\""), line)
        assertTrue("\"reports\":3" in line && "\"bytes\":45000" in line, line)
        assertFalse('\n' in line)
    }
}
