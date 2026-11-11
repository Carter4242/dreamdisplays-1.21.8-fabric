package com.dreamdisplays.core.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PermResolveTest {
    private fun lookupOf(vararg set: Pair<String, Boolean>): (String) -> Boolean? = mapOf(*set)::get

    @Test
    fun unsetEverywhereLeavesTheDefault() {
        assertNull(Perm.resolve(Perm.DISPLAY_CREATE, lookupOf()))
    }

    @Test
    fun ownSettingWins() {
        val lookup = lookupOf(Perm.DISPLAY_CREATE to false, Perm.ADMIN to true, "dreamdisplays.create" to true)
        assertEquals(false, Perm.resolve(Perm.DISPLAY_CREATE, lookup))
    }

    @Test
    fun legacyNodeGrantsItsNewNodes() {
        val lookup = lookupOf("dreamdisplays.fullscreen.start" to true)
        assertEquals(true, Perm.resolve(Perm.FULLSCREEN, lookup))
        assertEquals(true, Perm.resolve(Perm.PREPARE, lookup))
        assertNull(Perm.resolve(Perm.DISPLAY_CREATE, lookup))
    }

    @Test
    fun adminGrantsEveryNode() {
        val lookup = lookupOf(Perm.ADMIN to true)
        Perm.ALL.forEach { assertEquals(true, Perm.resolve(it, lookup), it) }
        assertNull(Perm.resolve("group.vip", lookup))
    }
}
