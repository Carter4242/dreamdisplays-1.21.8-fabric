package com.dreamdisplays.core.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayLeaseSyncTest {
    private val k1 = "a".repeat(32)
    private val k2 = "b".repeat(32)

    private class Relay(var up: Boolean = true) {
        val bodies = ArrayList<String>()
        fun send(body: String): Boolean {
            bodies += body
            return up
        }
    }

    @Test
    fun `a push carries the whole current set with a rising sequence`() {
        val relay = Relay()
        val sync = RelayLeaseSync("ep1", ttlMs = 20_000L, send = relay::send)
        sync.set(mapOf("dda" to k1))
        assertTrue(sync.push())
        sync.set(mapOf("dda" to k1, "ddb" to k2))
        assertTrue(sync.push())
        sync.set(emptyMap())
        assertTrue(sync.push())
        assertEquals(
            listOf(
                """{"epoch":"ep1","seq":1,"ttlMs":20000,"shares":{"dda":"$k1"}}""",
                """{"epoch":"ep1","seq":2,"ttlMs":20000,"shares":{"dda":"$k1","ddb":"$k2"}}""",
                """{"epoch":"ep1","seq":3,"ttlMs":20000,"shares":{}}""",
            ),
            relay.bodies,
        )
    }

    @Test
    fun `a start is confirmed only by a push that contains it`() {
        val relay = Relay()
        val sync = RelayLeaseSync("ep", send = relay::send)
        var result: Boolean? = null
        sync.set(mapOf("dda" to k1)) { result = it }
        assertNull(result)
        assertTrue(sync.dirty())
        sync.push()
        assertEquals(true, result)
        assertFalse(sync.dirty())
    }

    @Test
    fun `a newer set confirms the starts of every older one`() {
        val relay = Relay()
        val sync = RelayLeaseSync("ep", send = relay::send)
        val results = ArrayList<String>()
        sync.set(mapOf("dda" to k1)) { results += "a=$it" }
        sync.set(mapOf("dda" to k1, "ddb" to k2)) { results += "b=$it" }
        sync.push()
        assertEquals(listOf("a=true", "b=true"), results)
        assertEquals(1, relay.bodies.size)
    }

    @Test
    fun `an unreachable service refuses a waiting start after two failed pushes, then recovers`() {
        val relay = Relay(up = false)
        val sync = RelayLeaseSync("ep", send = relay::send)
        var result: Boolean? = null
        sync.set(mapOf("dda" to k1)) { result = it }
        assertFalse(sync.push())
        assertNull(result, "one failure is retried before the start is refused")
        assertFalse(sync.push())
        assertEquals(false, result)
        // Still unsent: renewals keep trying, and the next success clears it
        assertTrue(sync.dirty())
        relay.up = true
        assertTrue(sync.push())
        assertFalse(sync.dirty())
    }

    @Test
    fun `a throwing transport counts as a failed push`() {
        val sync = RelayLeaseSync("ep") { throw java.io.IOException("down") }
        sync.set(mapOf("dda" to k1))
        assertFalse(sync.push())
        assertTrue(sync.dirty())
    }

    @Test
    fun `renewal stops once an empty set was taken`() {
        val sync = RelayLeaseSync("ep", send = Relay()::send)
        assertFalse(sync.needsRenewal())
        sync.set(mapOf("dda" to k1))
        assertTrue(sync.needsRenewal())
        sync.push()
        assertTrue(sync.needsRenewal(), "a running share is renewed or its lease lapses")
        sync.set(emptyMap())
        assertTrue(sync.needsRenewal(), "the end must still reach the service")
        sync.push()
        assertFalse(sync.needsRenewal())
    }

    @Test
    fun `nothing that could break the json gets in`() {
        val sync = RelayLeaseSync("ep", send = Relay()::send)
        assertFailsWith<IllegalArgumentException> { sync.set(mapOf("dd\"x" to k1)) }
        assertFailsWith<IllegalArgumentException> { sync.set(mapOf("dda" to "short")) }
        assertFailsWith<IllegalArgumentException> { sync.set(mapOf("dda" to "a\"".repeat(10))) }
    }

    @Test
    fun `the lease service settings`() {
        assertNull(RelayControlConfig.problem("", ""))
        assertNull(RelayControlConfig.problem("https://live.example.org/dd-relay/v1/shares", "t".repeat(32)))
        assertNotNull(RelayControlConfig.problem("http://live.example.org/v1/shares", "t".repeat(32)))
        assertNotNull(RelayControlConfig.problem("https://live.example.org/v1/shares", "short"))
        assertNotNull(RelayControlConfig.problem("https:///v1/shares", "t".repeat(32)))
        assertNotNull(RelayControlConfig.problem("https://live.example.org/v1/shares", "t".repeat(16) + " x"))
    }
}
