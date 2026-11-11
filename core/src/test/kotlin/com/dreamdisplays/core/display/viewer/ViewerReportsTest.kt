package com.dreamdisplays.core.display.viewer

import com.dreamdisplays.core.protocol.common.packets.ViewerReport
import com.dreamdisplays.core.protocol.common.packets.ViewerState
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewerReportsTest {
    private val display = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val other = UUID.fromString("00000000-0000-0000-0000-0000000000d2")
    private val alice = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val bob = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    private fun report(
        id: UUID = display, drift: Int = 0, syncing: Boolean = true, state: ViewerState = ViewerState.PLAYING,
        error: String = "", stalls: Int = 0, seeks: Int = 0, buffering: Boolean = false,
    ) = ViewerReport(
        id = id, state = state.wire, syncing = syncing, driftMs = drift, stalls = stalls, seeks = seeks,
        buffering = buffering, errorKind = error,
    )

    private fun entry(r: ViewerReport, name: String = "p") = ViewerEntry(UUID.randomUUID(), name, r, 0L)

    @Test
    fun `reports within the minimum interval are dropped per viewer and display`() {
        val store = ViewerReportStore(minIntervalMs = 1_000L)
        assertTrue(store.offer(alice, "alice", report(drift = 10), 10_000L))
        assertFalse(store.offer(alice, "alice", report(drift = 20), 10_500L))
        assertTrue(store.offer(bob, "bob", report(drift = 30), 10_500L))
        assertTrue(store.offer(alice, "alice", report(id = other), 10_500L))
        assertTrue(store.offer(alice, "alice", report(drift = 40), 11_000L))
        val viewers = store.viewers(display, 11_000L, 15_000L)
        assertEquals(listOf(40, 30), viewers.map { it.report.driftMs })
    }

    @Test
    fun `one viewer cannot track more displays than the cap`() {
        val store = ViewerReportStore(maxDisplaysPerPlayer = 2)
        assertTrue(store.offer(alice, "alice", report(id = UUID.randomUUID()), 0L))
        assertTrue(store.offer(alice, "alice", report(id = UUID.randomUUID()), 0L))
        assertFalse(store.offer(alice, "alice", report(id = UUID.randomUUID()), 0L))
        assertTrue(store.offer(bob, "bob", report(id = UUID.randomUUID()), 0L))
        // Room again once the old ones are pruned
        store.prune(100_000L, 15_000L)
        assertTrue(store.offer(alice, "alice", report(id = UUID.randomUUID()), 100_000L))
    }

    @Test
    fun `stale reports, quitting viewers and removed displays are forgotten`() {
        val store = ViewerReportStore()
        store.offer(alice, "alice", report(), 0L)
        store.offer(bob, "bob", report(), 10_000L)
        assertEquals(1, store.viewers(display, 20_000L, 15_000L).size)
        store.prune(20_000L, 15_000L)
        assertEquals(listOf("bob"), store.viewers(display, 20_000L, 60_000L).map { it.playerName })
        store.forgetPlayer(bob)
        assertTrue(store.displays().isEmpty())
        store.offer(alice, "alice", report(id = other), 30_000L)
        store.forgetDisplay(other)
        assertTrue(store.displays().isEmpty())
        // Forgetting reset the rate limit too
        assertTrue(store.offer(alice, "alice", report(id = other), 30_001L))
    }

    @Test
    fun `reports are cleaned before they are kept`() {
        val r = ViewerReportStore.sanitized(
            ViewerReport(
                driftMs = Int.MAX_VALUE, rate = Double.NaN, latencyCompMs = -5, action = 9, positionMs = -1,
                errorKind = "NETWORK\u0000", errorMessage = "&kbad §cthing\n" + "x".repeat(500),
            ),
        )
        assertEquals(86_400_000, r.driftMs)
        assertEquals(1.0, r.rate)
        assertEquals(0, r.latencyCompMs)
        assertEquals(3, r.action)
        assertEquals(0L, r.positionMs)
        assertEquals("NETWORK", r.errorKind)
        assertTrue(r.errorMessage.startsWith("bad thing"), r.errorMessage)
        assertEquals(160, r.errorMessage.length)
    }

    @Test
    fun `summary covers drift only of viewers in sync`() {
        val s = ViewerSummary.of(
            listOf(
                entry(report(drift = 40, stalls = 1, seeks = 2)),
                entry(report(drift = -25, buffering = true)),
                entry(report(drift = 900, syncing = false)), // local playback: drift means nothing
                entry(report(drift = 5_000, state = ViewerState.ERROR, error = "NETWORK", stalls = 3)),
            ),
        )
        assertEquals(4, s.viewers)
        assertEquals(2, s.syncing)
        assertEquals(40, s.maxAbsDriftMs)
        assertEquals(65, s.spreadMs)
        assertEquals(1, s.erroring)
        assertEquals(1, s.buffering)
        assertEquals(4, s.stalls)
        assertEquals(2, s.seeks)
    }

    @Test
    fun `summary edge cases`() {
        val empty = ViewerSummary.of(emptyList())
        assertEquals(0, empty.viewers)
        assertNull(empty.maxAbsDriftMs)
        assertNull(empty.spreadMs)
        val one = ViewerSummary.of(listOf(entry(report(drift = -70))))
        assertEquals(70, one.maxAbsDriftMs)
        assertNull(one.spreadMs)
        val errorOnlyKind = ViewerSummary.of(listOf(entry(report(state = ViewerState.PLAYING, error = "DECODE"))))
        assertEquals(1, errorOnlyKind.erroring)
        assertEquals(0, errorOnlyKind.syncing)
    }

    @Test
    fun `viewers list errors first, then the largest drift`() {
        val store = ViewerReportStore()
        store.offer(alice, "alice", report(drift = 10), 0L)
        store.offer(bob, "bob", report(drift = -80), 0L)
        val carol = UUID.randomUUID()
        store.offer(carol, "carol", report(state = ViewerState.ERROR, error = "TIMEOUT"), 0L)
        assertEquals(listOf("carol", "bob", "alice"), store.viewers(display, 0L, 15_000L).map { it.playerName })
    }
}
