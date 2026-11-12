package com.dreamdisplays.core.protocol.common

import com.dreamdisplays.core.protocol.common.packets.ServerHello
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelloResyncTest {
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val plain = ServerHello(protocolVersion = 12, allowedFeatures = listOf("queue"), maxDisplays = 3)
    private val sharer = plain.copy(allowedFeatures = listOf("queue", "screen_share", "screen_share_heartbeat"))

    @Test
    fun `a hello that would come out the same is not sent again`() {
        val r = HelloResync<ServerHello>()
        r.onSent(alice, plain)
        assertFalse(r.changed(alice, plain.copy()))
    }

    @Test
    fun `a grant and its revocation are each sent once`() {
        val r = HelloResync<ServerHello>()
        r.onSent(alice, plain)
        assertTrue(r.changed(alice, sharer))
        assertFalse(r.changed(alice, sharer))
        assertTrue(r.changed(alice, plain))
        assertFalse(r.changed(alice, plain))
    }

    @Test
    fun `every capability bit counts`() {
        val r = HelloResync<ServerHello>()
        r.onSent(alice, plain)
        assertTrue(r.changed(alice, plain.copy(isAdmin = true)))
        assertTrue(r.changed(alice, plain.copy(isAdmin = true, isPremium = true)))
        assertTrue(r.changed(alice, plain.copy(isAdmin = true, isPremium = true, maxDisplays = -1)))
    }

    @Test
    fun `a player who never got a hello is not sent one`() {
        val r = HelloResync<ServerHello>()
        assertFalse(r.changed(alice, sharer))
        // Nor is one who left
        r.onSent(bob, plain)
        r.forget(bob)
        assertFalse(r.changed(bob, sharer))
        assertEquals(emptySet(), r.known())
    }

    @Test
    fun `a burst of recalculations is rechecked once, after the last`() {
        val r = HelloResync<ServerHello>(debounceMs = 250)
        r.request(alice, 1_000)
        r.request(alice, 1_010)
        r.request(alice, 1_200)
        assertEquals(emptyList(), r.takeDue(1_300))
        assertEquals(emptyList(), r.takeDue(1_449))
        assertEquals(listOf(alice), r.takeDue(1_450))
        assertEquals(emptyList(), r.takeDue(2_000))
    }

    @Test
    fun `players are debounced on their own`() {
        val r = HelloResync<ServerHello>(debounceMs = 250)
        r.request(alice, 1_000)
        r.request(bob, 1_200)
        assertEquals(listOf(alice), r.takeDue(1_250))
        r.request(alice, 1_300)
        assertEquals(listOf(bob), r.takeDue(1_450))
        assertEquals(listOf(alice), r.takeDue(1_550))
    }

    @Test
    fun `a pending recheck is dropped when the player leaves`() {
        val r = HelloResync<ServerHello>(debounceMs = 250)
        r.request(alice, 1_000)
        r.forget(alice)
        assertEquals(emptyList(), r.takeDue(5_000))
    }

    @Test
    fun `sweeps come every interval from the first call`() {
        val r = HelloResync<ServerHello>(sweepEveryMs = 5_000)
        assertFalse(r.sweepDue(10_000))
        assertFalse(r.sweepDue(14_999))
        assertTrue(r.sweepDue(15_000))
        assertFalse(r.sweepDue(15_100))
        assertTrue(r.sweepDue(20_050))
        r.clear()
        assertFalse(r.sweepDue(20_100))
        assertTrue(r.sweepDue(25_100))
    }

    @Test
    fun `a sweep covers everyone who got a hello`() {
        val r = HelloResync<ServerHello>()
        r.onSent(alice, plain)
        r.onSent(bob, sharer)
        assertEquals(setOf(alice, bob), r.known())
        r.clear()
        assertEquals(emptySet(), r.known())
    }
}
