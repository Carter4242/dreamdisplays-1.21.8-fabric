package com.dreamdisplays.core.display.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerPoliciesTest {
    private fun path(name: String) = PolicyPaths.find(name) ?: error("no key $name")

    @Test
    fun `only server-wide keys can be set for a player`() {
        assertTrue(PlayerPolicies.keys.isNotEmpty())
        assertTrue(PlayerPolicies.keys.all { it.field in DisplayPolicy.GLOBAL_ONLY })
        assertNull(PlayerPolicies.withKey(null, path("client.enabled"), "false"), "every display carries its own copy")
        assertNotNull(PlayerPolicies.withKey(null, path("client.video_decoder"), "process"))
        val perDisplay = PolicyPaths.all.first { it.field.startsWith("sync.") }
        assertNull(PlayerPolicies.withKey(null, perDisplay, "30"))
    }

    @Test
    fun `a player's override lands on top of the server-wide policy and can be cleared again`() {
        val key = PlayerPolicies.keys.first { it.kind == PolicyPaths.Kind.Bool }
        val serverWide = PolicyPaths.apply(null, key, "true")!!.serverWide()
        val override = assertNotNull(PlayerPolicies.withKey(null, key, "false"))
        assertEquals(false, key.get(PlayerPolicies.applyTo(serverWide, override)))
        // Without an override the player gets exactly the server-wide policy
        assertEquals(serverWide, PlayerPolicies.applyTo(serverWide, null))
        // Clearing the only key leaves nothing
        val cleared = assertNotNull(PlayerPolicies.withKey(override, key, null))
        assertTrue(cleared.isEmpty)
        assertEquals(true, key.get(PlayerPolicies.applyTo(serverWide, cleared)))
    }

    @Test
    fun `a bad value is refused`() {
        val key = PlayerPolicies.keys.first { it.kind == PolicyPaths.Kind.Bool }
        assertNull(PlayerPolicies.withKey(null, key, "maybe"))
    }

    @Test
    fun `only server-wide sections are ever sent`() {
        val sync = PolicyPaths.all.first { it.field.startsWith("sync.") }
        val sneaky = PolicyPaths.apply(null, sync, sync.kind.let { if (it == PolicyPaths.Kind.Bool) "true" else "30" })
        val sent = PlayerPolicies.applyTo(DisplayPolicy.EMPTY, sneaky)
        assertNull(sent.sync)
    }
}
