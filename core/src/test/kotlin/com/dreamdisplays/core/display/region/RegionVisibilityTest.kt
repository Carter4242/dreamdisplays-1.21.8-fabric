package com.dreamdisplays.core.display.region

import com.dreamdisplays.core.display.policy.DisplayPolicy
import com.dreamdisplays.core.display.policy.PolicyPaths
import com.dreamdisplays.core.display.region.RegionVisibility.Reason
import com.dreamdisplays.core.display.region.RegionVisibility.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegionVisibilityTest {
    private val eps = 1e-9

    @Test
    fun `fade is 1 inside, 0 past the fade band, smooth between`() {
        assertEquals(1.0, RegionVisibility.fade(0.0, 3.0))
        assertEquals(1.0, RegionVisibility.fade(-1.0, 3.0))
        assertEquals(0.0, RegionVisibility.fade(3.0, 3.0))
        assertEquals(0.0, RegionVisibility.fade(10.0, 3.0))
        assertEquals(0.5, RegionVisibility.fade(1.5, 3.0), eps)
        // Smoothstep: flat at both ends of the band
        assertTrue(1.0 - RegionVisibility.fade(0.03, 3.0) < 1e-3)
        assertTrue(RegionVisibility.fade(2.97, 3.0) < 1e-3)
        var last = 1.0
        var d = 0.0
        while (d <= 3.0) {
            val f = RegionVisibility.fade(d, 3.0)
            assertTrue(f <= last + eps && f in 0.0..1.0, "at $d")
            last = f
            d += 0.05
        }
        // No fade band: the sound stops at the edge
        assertEquals(1.0, RegionVisibility.fade(0.0, 0.0))
        assertEquals(0.0, RegionVisibility.fade(0.01, 0.0))
    }

    private fun policy(region: String? = "hall", fade: Double? = null, load: Double? = null) =
        DisplayPolicy(visibility = DisplayPolicy.Visibility(region = region, regionFadeBlocks = fade, regionLoadBlocks = load))

    @Test
    fun `settings default, clamp, and load is never under fade`() {
        assertEquals(3.0, RegionVisibility.fadeBlocks(policy()))
        assertEquals(8.0, RegionVisibility.loadBlocks(policy()))
        assertEquals(16.0, RegionVisibility.fadeBlocks(policy(fade = 40.0)))
        assertEquals(0.0, RegionVisibility.fadeBlocks(policy(fade = -2.0)))
        assertEquals(3.0, RegionVisibility.fadeBlocks(policy(fade = Double.NaN)))
        assertEquals(10.0, RegionVisibility.loadBlocks(policy(fade = 10.0, load = 4.0)))
        assertEquals(20.0, RegionVisibility.loadBlocks(policy(load = 20.0)))
        assertEquals("hall", RegionVisibility.regionId(policy()))
        assertNull(RegionVisibility.regionId(policy(region = "  ")))
        assertNull(RegionVisibility.regionId(null))
    }

    // Region: blocks 0..9 x 0..9 x 0..9
    private val shape = RegionShape.Cuboid(0, 0, 0, 9, 9, 9)
    private val active = State.Active("hall", shape, fadeBlocks = 3.0, loadBlocks = 8.0)

    private fun admits(state: State, capable: Boolean, x: Double, was: Boolean = false) =
        RegionVisibility.admits(state, capable, x, 1.0, 5.0, was)

    @Test
    fun `new clients get it within load distance plus the unload slack`() {
        assertTrue(admits(active, capable = true, x = 5.0))             // inside
        assertTrue(admits(active, capable = true, x = 12.0))            // in the fade band
        assertTrue(admits(active, capable = true, x = 18.0))            // at load distance (8 past x = 10)
        assertTrue(admits(active, capable = true, x = 20.0))            // within the 2-block slack
        assertFalse(admits(active, capable = true, x = 20.5))
        assertFalse(admits(active, capable = true, x = 60.0))
    }

    @Test
    fun `released clients get it only inside, keeping it one block past the edge`() {
        assertTrue(admits(active, capable = false, x = 5.0))
        assertTrue(admits(active, capable = false, x = 9.99))
        assertFalse(admits(active, capable = false, x = 10.5))           // just outside, not yet holding it
        assertTrue(admits(active, capable = false, x = 10.5, was = true)) // walked out half a block: kept
        assertFalse(admits(active, capable = false, x = 11.5, was = true))
        assertFalse(admits(active, capable = false, x = 12.0))           // fade band: nothing for them
    }

    @Test
    fun `a missing region shows the display to nobody, no region to everyone`() {
        val missing = State.Unavailable("hall", Reason.NOT_FOUND)
        for (capable in listOf(true, false)) {
            assertFalse(admits(missing, capable, 5.0, was = true))
            assertFalse(RegionVisibility.hears(missing, capable, 5.0, 1.0, 5.0))
            assertTrue(admits(State.None, capable, 500.0))
            assertTrue(RegionVisibility.hears(State.None, capable, 500.0, 1.0, 5.0))
        }
    }

    @Test
    fun `heard inside and in the fade band only`() {
        assertTrue(RegionVisibility.hears(active, true, 5.0, 1.0, 5.0))
        assertTrue(RegionVisibility.hears(active, true, 12.9, 1.0, 5.0))
        assertFalse(RegionVisibility.hears(active, true, 13.0, 1.0, 5.0))
        assertFalse(RegionVisibility.hears(active, true, 17.0, 1.0, 5.0))
        assertTrue(RegionVisibility.hears(active, false, 5.0, 1.0, 5.0))
        assertFalse(RegionVisibility.hears(active, false, 11.0, 1.0, 5.0))
    }

    @Test
    fun `client loads within load distance, fades over the fade band`() {
        val p = policy()
        assertTrue(RegionVisibility.clientMayLoad(p, shape, 18.0, 1.0, 5.0))
        assertFalse(RegionVisibility.clientMayLoad(p, shape, 18.5, 1.0, 5.0))
        assertTrue(RegionVisibility.clientMayLoad(p, shape, 20.0, 1.0, 5.0, slackBlocks = 2.0))
        // A region the server sent no shape for: never loaded; no region at all: always
        assertFalse(RegionVisibility.clientMayLoad(p, null, 5.0, 1.0, 5.0))
        assertTrue(RegionVisibility.clientMayLoad(policy(region = null), null, 500.0, 1.0, 5.0))
        assertTrue(RegionVisibility.clientMayLoad(null, null, 500.0, 1.0, 5.0))

        assertEquals(1.0, RegionVisibility.clientFade(p, shape, 5.0, 1.0, 5.0))
        assertEquals(0.5, RegionVisibility.clientFade(p, shape, 11.5, 1.0, 5.0), eps)
        assertEquals(0.0, RegionVisibility.clientFade(p, shape, 14.0, 1.0, 5.0))
        assertEquals(0.0, RegionVisibility.clientFade(p, null, 5.0, 1.0, 5.0))
        assertEquals(1.0, RegionVisibility.clientFade(null, null, 500.0, 1.0, 5.0))
        // Loaded but silent between the fade and load distances
        assertTrue(RegionVisibility.clientMayLoad(p, shape, 15.0, 1.0, 5.0))
        assertEquals(0.0, RegionVisibility.clientFade(p, shape, 15.0, 1.0, 5.0))
    }

    @Test
    fun `policy keys set the region per display only`() {
        val region = assertNotNull(PolicyPaths.find("visibility.region"))
        assertTrue(region.displayOnly)
        assertFalse(region.serverWideOnly)
        assertEquals("cinema_1", PolicyPaths.apply(null, region, "cinema_1")?.visibility?.region)
        assertNull(PolicyPaths.apply(null, region, "bad id with spaces"))
        val fade = assertNotNull(PolicyPaths.find("visibility.region_fade_blocks"))
        assertFalse(fade.displayOnly)
        assertEquals(5.0, PolicyPaths.apply(null, fade, "5")?.visibility?.regionFadeBlocks)
        assertNull(PolicyPaths.apply(null, fade, "17"))
        val load = assertNotNull(PolicyPaths.find("visibility.region_load_blocks"))
        assertEquals(12.0, PolicyPaths.apply(null, load, "12")?.visibility?.regionLoadBlocks)
        // A per-display region over server defaults for the distances
        val merged = policy(fade = null, load = null).mergedOver(DisplayPolicy(visibility = DisplayPolicy.Visibility(regionFadeBlocks = 4.0)))
        assertEquals("hall", merged.visibility?.region)
        assertEquals(4.0, merged.visibility?.regionFadeBlocks)
        // Survives the JSON trip to clients
        assertEquals(merged.visibility, DisplayPolicy.decode(merged.encode())?.visibility)
    }
}
