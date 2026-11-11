package com.dreamdisplays.platform.client.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReflectiveProbeTest {
    @Test
    fun missingModIsLookedUpOnce() {
        var lookups = 0
        val probe = ReflectiveProbe {
            lookups++
            Class.forName("not.installed.ShaderMod")
            error("unreachable")
        }
        repeat(1_000) { assertFalse(probe.active()) }
        assertEquals(1, lookups)
    }

    @Test
    fun resolvedQueryIsAskedEveryTimeButResolvedOnce() {
        var lookups = 0
        var inUse = false
        val probe = ReflectiveProbe {
            lookups++
            val query: () -> Boolean = { inUse }
            query
        }
        assertFalse(probe.active())
        inUse = true
        assertTrue(probe.active())
        assertEquals(1, lookups)
    }

    @Test
    fun otherLookupFailuresAreRetried() {
        var lookups = 0
        val probe = ReflectiveProbe {
            lookups++
            if (lookups == 1) throw IllegalStateException("not initialized yet")
            val query: () -> Boolean = { true }
            query
        }
        assertFalse(probe.active())
        assertTrue(probe.active())
        assertEquals(2, lookups)
    }
}
