package com.dreamdisplays.shared.playback

import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadinessRosterTest {
    private val display = UUID(1, 1)
    private val other = UUID(1, 2)
    private val a = UUID(0, 1)
    private val b = UUID(0, 2)
    private val c = UUID(0, 3)

    @Test
    fun `nobody reported means everyone is waited for`() {
        val roster = ReadinessRoster()
        assertEquals(listOf(a, b), roster.waitingFor(display, 0, listOf(a, b)))
        assertNull(roster.reportOf(display, a))
    }

    @Test
    fun `ready near the start position counts, elsewhere does not`() {
        val roster = ReadinessRoster(toleranceMs = 300)
        roster.report(display, a, ReadinessState.READY, 10_100, 20)
        roster.report(display, b, ReadinessState.READY, 42_000, 20)
        assertTrue(roster.isReadyAt(display, a, 10_000))
        assertFalse(roster.isReadyAt(display, a, 10_401))
        assertEquals(listOf(b), roster.waitingFor(display, 10_000, listOf(a, b)))
        assertEquals(emptyList(), roster.waitingFor(display, 42_250, listOf(b)))
    }

    @Test
    fun `not ready blocks, absent never does`() {
        val roster = ReadinessRoster()
        roster.report(display, a, ReadinessState.READY, 0, 0)
        roster.report(display, b, ReadinessState.NOT_READY, 0, 0)
        roster.report(display, c, ReadinessState.ABSENT, 0, 0)
        assertEquals(listOf(b), roster.waitingFor(display, 0, listOf(a, b, c)))
        roster.report(display, b, ReadinessState.READY, 0, 0)
        assertEquals(emptyList(), roster.waitingFor(display, 0, listOf(a, b, c)))
    }

    @Test
    fun `reports are per display`() {
        val roster = ReadinessRoster()
        roster.report(display, a, ReadinessState.READY, 0, 0)
        assertEquals(listOf(a), roster.waitingFor(other, 0, listOf(a)))
    }

    @Test
    fun `lead covers the slowest viewer, capped`() {
        val roster = ReadinessRoster()
        roster.report(display, a, ReadinessState.READY, 0, 30)
        roster.report(display, b, ReadinessState.READY, 0, 80)
        assertEquals(95, roster.leadMs(display, listOf(a, b), capMs = 150, slackMs = 15))
        assertEquals(45, roster.leadMs(display, listOf(a), capMs = 150, slackMs = 15))
        assertEquals(60, roster.leadMs(display, listOf(a, b), capMs = 60, slackMs = 15))
        assertEquals(0, roster.leadMs(display, listOf(a, b), capMs = 0))
        // Unknown latency counts as zero: the slack alone
        assertEquals(15, roster.leadMs(display, listOf(c), capMs = 150, slackMs = 15))
        assertEquals(15, roster.leadMs(display, emptyList(), capMs = 150, slackMs = 15))
    }

    @Test
    fun `forgetting players and displays`() {
        val roster = ReadinessRoster()
        roster.report(display, a, ReadinessState.READY, 0, 0)
        roster.report(other, a, ReadinessState.READY, 0, 0)
        roster.forgetPlayer(a)
        assertNull(roster.reportOf(display, a))
        assertNull(roster.reportOf(other, a))
        roster.report(display, b, ReadinessState.READY, 0, 0)
        roster.forgetDisplay(display)
        assertNull(roster.reportOf(display, b))
    }

    @Test
    fun `state wire values are stable`() {
        assertEquals(0, ReadinessState.NOT_READY.wire)
        assertEquals(1, ReadinessState.READY.wire)
        assertEquals(2, ReadinessState.ABSENT.wire)
        assertEquals(ReadinessState.NOT_READY, ReadinessState.fromWire(7))
    }
}
