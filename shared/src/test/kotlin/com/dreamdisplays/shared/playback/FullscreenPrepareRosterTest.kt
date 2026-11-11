package com.dreamdisplays.shared.playback

import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FullscreenPrepareRosterTest {
    private val a = UUID(0, 1)
    private val b = UUID(0, 2)
    private val c = UUID(0, 3)

    @Test
    fun `delivery starts every player loading and counts it once`() {
        val roster = FullscreenPrepareRoster("p1", expiresAtMs = 10_000)
        assertTrue(roster.onDelivered(a, 0))
        assertFalse(roster.onDelivered(a, 500))
        assertTrue(roster.onDelivered(b, 0))
        assertEquals(FullscreenPrepareState.LOADING, roster.stateOf(a))
        assertEquals(FullscreenPrepareRoster.Tally(2, 0, 2, 0, 0, 0), roster.tally())
        assertFalse(roster.tally().settled)
    }

    @Test
    fun `reports move players to ready and the tally settles`() {
        val roster = FullscreenPrepareRoster("p1", expiresAtMs = 10_000)
        roster.onDelivered(a, 0)
        roster.onDelivered(b, 0)
        assertTrue(roster.onStatus(a, "p1", FullscreenPrepareState.READY))
        assertFalse(roster.tally().settled)
        assertTrue(roster.onStatus(b, "p1", FullscreenPrepareState.READY))
        val tally = roster.tally()
        assertEquals(2, tally.ready)
        assertTrue(tally.settled)
        assertTrue(tally.allReady)
    }

    @Test
    fun `reports for another preparation or from strangers are ignored`() {
        val roster = FullscreenPrepareRoster("p1", expiresAtMs = 10_000)
        roster.onDelivered(a, 0)
        assertFalse(roster.onStatus(a, "other", FullscreenPrepareState.READY))
        assertFalse(roster.onStatus(c, "p1", FullscreenPrepareState.READY))
        assertNull(roster.stateOf(c))
        assertEquals(FullscreenPrepareState.LOADING, roster.stateOf(a))
        assertEquals(1, roster.tally().delivered)
    }

    @Test
    fun `players with displays off do not hold up settling, failures block all-ready`() {
        val roster = FullscreenPrepareRoster("p1", expiresAtMs = 10_000)
        roster.onDelivered(a, 0)
        roster.onDelivered(b, 0)
        roster.onStatus(a, "p1", FullscreenPrepareState.READY)
        roster.onStatus(b, "p1", FullscreenPrepareState.DISABLED)
        assertTrue(roster.tally().settled)
        assertTrue(roster.tally().allReady)
        roster.onStatus(b, "p1", FullscreenPrepareState.FAILED)
        assertTrue(roster.tally().settled)
        assertFalse(roster.tally().allReady)
    }

    @Test
    fun `old clients are counted apart until they get a delivery`() {
        val roster = FullscreenPrepareRoster("p1", expiresAtMs = 10_000)
        roster.onUnsupported(c)
        roster.onUnsupported(c)
        assertEquals(1, roster.tally().unsupported)
        assertEquals(0, roster.tally().delivered)
        roster.onDelivered(c, 0)
        assertEquals(0, roster.tally().unsupported)
        roster.onUnsupported(c) // A delivered player is never re-counted as unsupported
        assertEquals(0, roster.tally().unsupported)
    }

    @Test
    fun `only players still loading are due for a fresh delivery`() {
        val roster = FullscreenPrepareRoster("p1", expiresAtMs = 10_000)
        roster.onDelivered(a, 0)
        roster.onDelivered(b, 0)
        roster.onStatus(b, "p1", FullscreenPrepareState.READY)
        assertEquals(emptyList(), roster.dueForRedelivery(4_999, 5_000))
        assertEquals(listOf(a), roster.dueForRedelivery(5_000, 5_000))
        roster.onDelivered(a, 5_000)
        assertEquals(emptyList(), roster.dueForRedelivery(9_000, 5_000))
        // Ready again turned loading (e.g. the player changed worlds): due once its last delivery is old enough
        roster.onStatus(b, "p1", FullscreenPrepareState.LOADING)
        assertEquals(listOf(b), roster.dueForRedelivery(9_000, 5_000))
    }

    @Test
    fun `forgetting and expiry`() {
        val roster = FullscreenPrepareRoster("p1", expiresAtMs = 10_000)
        roster.onDelivered(a, 0)
        assertTrue(roster.forget(a))
        assertFalse(roster.forget(a))
        assertFalse(roster.onStatus(a, "p1", FullscreenPrepareState.READY))
        assertFalse(roster.isExpired(9_999))
        assertTrue(roster.isExpired(10_000))
    }

    @Test
    fun `state wire values are stable`() {
        assertEquals(0, FullscreenPrepareState.LOADING.wire)
        assertEquals(1, FullscreenPrepareState.READY.wire)
        assertEquals(2, FullscreenPrepareState.FAILED.wire)
        assertEquals(3, FullscreenPrepareState.DISABLED.wire)
        assertEquals(FullscreenPrepareState.LOADING, FullscreenPrepareState.fromWire(99))
    }
}
