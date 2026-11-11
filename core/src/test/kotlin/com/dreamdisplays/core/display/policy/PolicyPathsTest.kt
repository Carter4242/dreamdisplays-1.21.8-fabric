package com.dreamdisplays.core.display.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolicyPathsTest {
    @Test
    fun `every path maps onto a real policy field`() {
        for (path in PolicyPaths.all) {
            val sample = when (val kind = path.kind) {
                PolicyPaths.Kind.Bool -> "false"
                is PolicyPaths.Kind.Whole -> kind.max.toString()
                is PolicyPaths.Kind.Decimal -> kind.max.toString()
                is PolicyPaths.Kind.Choice -> kind.values.last()
                is PolicyPaths.Kind.Text -> "x"
            }
            val set = assertNotNull(PolicyPaths.apply(null, path, sample), path.name)
            assertEquals(path.kind.parse(sample), path.get(set), path.name)
            val cleared = assertNotNull(PolicyPaths.apply(set, path, null), path.name)
            assertNull(path.get(cleared), path.name)
        }
    }

    @Test
    fun `names follow the config keys`() {
        assertEquals("audio.radiusBlocks", PolicyPaths.find("audio.radius_blocks")?.field)
        assertEquals("sync.deadBandMs", PolicyPaths.find("SYNC.DEAD_BAND_MS")?.field)
        assertEquals("visibility.maxDistanceBlocks", PolicyPaths.find("visibility.max_distance_blocks")?.field)
        assertNull(PolicyPaths.find("audio.radiusBlocks"))
        assertNull(PolicyPaths.find("nope"))
        assertTrue(PolicyPaths.all.all { it.name.matches(Regex("[a-z]+\\.[a-z_]+")) })
    }

    @Test
    fun `values are type and range checked`() {
        val dead = PolicyPaths.find("sync.dead_band_ms")!!
        assertNull(PolicyPaths.apply(null, dead, "abc"))
        assertNull(PolicyPaths.apply(null, dead, "-1"))
        assertNull(PolicyPaths.apply(null, dead, "1.5"))
        assertEquals(30, PolicyPaths.apply(null, dead, "30")?.sync?.deadBandMs)
        val vol = PolicyPaths.find("audio.start_volume")!!
        assertNull(PolicyPaths.apply(null, vol, "1.5"))
        assertNull(PolicyPaths.apply(null, vol, "NaN"))
        assertEquals(0.5, PolicyPaths.apply(null, vol, "0.5")?.audio?.startVolume)
        val falloff = PolicyPaths.find("audio.falloff")!!
        assertEquals(DisplayPolicy.Falloff.LINEAR, PolicyPaths.apply(null, falloff, "Linear")?.audio?.falloff)
        assertNull(PolicyPaths.apply(null, falloff, "cubic"))
        val enabled = PolicyPaths.find("sync.enabled")!!
        assertEquals(false, PolicyPaths.apply(null, enabled, "off")?.sync?.enabled)
        assertNull(PolicyPaths.apply(null, enabled, "maybe"))
    }

    @Test
    fun `setting keeps the other overrides`() {
        val radius = PolicyPaths.find("audio.radius_blocks")!!
        val height = PolicyPaths.find("quality.max_height")!!
        val a = PolicyPaths.apply(null, radius, "48")!!
        val b = PolicyPaths.apply(a, height, "720")!!
        assertEquals(48.0, b.audio?.radiusBlocks)
        assertEquals(720, b.quality?.maxHeight)
    }

    @Test
    fun `format is readable`() {
        assertEquals("96", PolicyPaths.format(96.0))
        assertEquals("0.5", PolicyPaths.format(0.5))
        assertEquals("-", PolicyPaths.format(null))
        assertEquals("true", PolicyPaths.format(true))
        assertEquals("0..1", PolicyPaths.find("audio.start_volume")!!.hint)
    }
}
