package com.dreamdisplays.platform.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplaysSwitchTest {
    @Test
    fun `a server switching displays off affects that server only`() {
        val (global, disabled) = DisplaysSwitch.afterServerSet(true, emptySet(), "play.example.org", enabled = false)
        assertTrue(global)
        assertFalse(DisplaysSwitch.enabledOn(global, disabled, "play.example.org"))
        assertFalse(DisplaysSwitch.enabledOn(global, disabled, "PLAY.example.org"))
        assertTrue(DisplaysSwitch.enabledOn(global, disabled, "other.example.org:25566"))
    }

    @Test
    fun `switching back on clears that server and an old global off`() {
        val (global, disabled) = DisplaysSwitch.afterServerSet(false, setOf("a", "b"), "a", enabled = true)
        assertTrue(global)
        assertEquals(setOf("b"), disabled)
    }

    @Test
    fun `an old global off still holds everywhere until a server switches it on`() {
        assertFalse(DisplaysSwitch.enabledOn(false, emptySet(), "anywhere"))
    }

    @Test
    fun `with no server known nothing is stored`() {
        assertEquals(true to setOf("a"), DisplaysSwitch.afterServerSet(true, setOf("a"), null, enabled = false))
    }

    @Test
    fun `the server list survives the config file`() {
        val servers = setOf("mc.example.org:25565", "singleplayer")
        assertEquals(servers, DisplaysSwitch.parseServers(DisplaysSwitch.formatServers(servers)))
        assertEquals(emptySet(), DisplaysSwitch.parseServers(""))
    }
}
