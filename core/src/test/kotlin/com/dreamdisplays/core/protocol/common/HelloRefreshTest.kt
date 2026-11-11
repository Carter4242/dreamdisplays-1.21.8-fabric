package com.dreamdisplays.core.protocol.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelloRefreshTest {
    private val s = 1_000_000_000L

    @Test
    fun `the answer to our own hello is not answered again`() {
        val r = HelloRefresh()
        r.onHelloSent(0)
        assertFalse(r.onPacket(1 * s, isServerHello = true, alreadyNegotiated = false))
        // Even if the server repeats it right away (e.g. after a re-handshake)
        r.onHelloSent(100 * s)
        assertFalse(r.onPacket(101 * s, isServerHello = true, alreadyNegotiated = true))
    }

    @Test
    fun `an unsolicited server hello is answered once`() {
        val r = HelloRefresh()
        r.onHelloSent(0)
        r.onPacket(1 * s, isServerHello = true, alreadyNegotiated = false)
        assertTrue(r.onPacket(600 * s, isServerHello = true, alreadyNegotiated = true))
        assertFalse(r.onPacket(601 * s, isServerHello = true, alreadyNegotiated = true))
        assertFalse(r.onPacket(700 * s, isServerHello = false, alreadyNegotiated = true))
    }

    @Test
    fun `steady traffic never triggers a hello`() {
        val r = HelloRefresh()
        r.onHelloSent(0)
        for (t in 0L..600L) {
            r.onPacket(t * s, isServerHello = false, alreadyNegotiated = true)
            assertFalse(r.tick(t * s, negotiated = true, displaysLoaded = true))
        }
    }

    @Test
    fun `silence with displays loaded triggers bounded, backed-off hellos`() {
        val r = HelloRefresh()
        r.onHelloSent(0)
        r.onPacket(1 * s, isServerHello = true, alreadyNegotiated = false)
        val sentAt = ArrayList<Long>()
        for (t in 2L..2_000L) if (r.tick(t * s, negotiated = true, displaysLoaded = true)) sentAt += t
        assertEquals(listOf(16L, 46L, 106L, 226L, 466L), sentAt)
        assertEquals(5, r.silenceAttempts)
    }

    @Test
    fun `a packet resets the backoff`() {
        val r = HelloRefresh()
        r.onPacket(0, isServerHello = false, alreadyNegotiated = true)
        assertTrue(r.tick(15 * s, negotiated = true, displaysLoaded = true))
        r.onPacket(20 * s, isServerHello = false, alreadyNegotiated = true)
        assertEquals(0, r.silenceAttempts)
        assertFalse(r.tick(30 * s, negotiated = true, displaysLoaded = true))
        assertTrue(r.tick(36 * s, negotiated = true, displaysLoaded = true))
    }

    @Test
    fun `no displays or no negotiation means no silence alarm`() {
        val r = HelloRefresh()
        r.onPacket(0, isServerHello = false, alreadyNegotiated = true)
        for (t in 1L..100L) assertFalse(r.tick(t * s, negotiated = true, displaysLoaded = false))
        // Silence is measured from when displays appear, not from the last packet long ago
        assertFalse(r.tick(101 * s, negotiated = true, displaysLoaded = true))
        assertFalse(r.tick(110 * s, negotiated = true, displaysLoaded = true))
        assertTrue(r.tick(116 * s, negotiated = true, displaysLoaded = true))
        val fresh = HelloRefresh()
        for (t in 1L..100L) assertFalse(fresh.tick(t * s, negotiated = false, displaysLoaded = true))
    }
}
