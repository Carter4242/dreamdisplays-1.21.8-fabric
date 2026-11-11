package com.dreamdisplays.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PointerRouterTest {
    private class Box(val x0: Double, val y0: Double, val x1: Double, val y1: Double) : PointerTarget {
        val log = mutableListOf<String>()
        var accepting = true

        override fun hitTest(x: Double, y: Double) = accepting && x in x0..x1 && y in y0..y1
        override fun onPointerPress(button: Int, x: Double, y: Double) { log += "press $button" }
        override fun onPointerRelease(button: Int, x: Double, y: Double) { log += "release $button" }
        override fun onPointerCancel(button: Int) { log += "cancel $button" }
    }

    private val router = PointerRouter()

    @Test
    fun `a press that misses every target is left for the game`() {
        val box = Box(0.0, 0.0, 10.0, 10.0)
        assertFalse(router.press(0, 50.0, 50.0, true, listOf(box)))
        assertFalse(router.release(0, 5.0, 5.0))
        assertTrue(box.log.isEmpty())
    }

    @Test
    fun `press and release over a target are both consumed`() {
        val box = Box(0.0, 0.0, 10.0, 10.0)
        assertTrue(router.press(0, 5.0, 5.0, true, listOf(box)))
        assertTrue(router.release(0, 5.0, 5.0))
        assertEquals(listOf("press 0", "release 0"), box.log)
    }

    @Test
    fun `the release goes to the press owner even when it lands elsewhere`() {
        val box = Box(0.0, 0.0, 10.0, 10.0)
        router.press(0, 5.0, 5.0, true, listOf(box))
        assertTrue(router.release(0, 500.0, 500.0))
        assertEquals(listOf("press 0", "release 0"), box.log)
    }

    @Test
    fun `a release that started off-target is not swallowed by a target under it`() {
        val box = Box(0.0, 0.0, 10.0, 10.0)
        assertFalse(router.press(0, 50.0, 50.0, true, listOf(box)))
        assertFalse(router.release(0, 5.0, 5.0))
        assertTrue(box.log.isEmpty())
    }

    @Test
    fun `only the top-most overlapping target gets the press`() {
        val top = Box(0.0, 0.0, 10.0, 10.0)
        val bottom = Box(0.0, 0.0, 20.0, 20.0)
        router.press(1, 5.0, 5.0, true, listOf(top, bottom))
        router.release(1, 5.0, 5.0)
        assertEquals(listOf("press 1", "release 1"), top.log)
        assertTrue(bottom.log.isEmpty())
    }

    @Test
    fun `every button is consumed over a target, not just left`() {
        val box = Box(0.0, 0.0, 10.0, 10.0)
        for (button in 0..4) {
            assertTrue(router.press(button, 5.0, 5.0, true, listOf(box)))
            assertTrue(router.release(button, 5.0, 5.0))
        }
    }

    @Test
    fun `a grabbed cursor never hits a target`() {
        val box = Box(0.0, 0.0, 10.0, 10.0)
        assertFalse(router.press(0, 5.0, 5.0, false, listOf(box)))
        assertFalse(router.scroll(5.0, 5.0, false, listOf(box)))
        assertTrue(box.log.isEmpty())
    }

    @Test
    fun `the release is still consumed after the cursor gets grabbed mid-click`() {
        val box = Box(0.0, 0.0, 10.0, 10.0)
        router.press(0, 5.0, 5.0, true, listOf(box))
        assertTrue(router.release(0, Double.NaN, Double.NaN))
    }

    @Test
    fun `a target that stops accepting input is skipped`() {
        val box = Box(0.0, 0.0, 10.0, 10.0).apply { accepting = false }
        assertFalse(router.press(0, 5.0, 5.0, true, listOf(box)))
    }

    @Test
    fun `scroll is swallowed only over a target`() {
        val box = Box(0.0, 0.0, 10.0, 10.0)
        assertTrue(router.scroll(5.0, 5.0, true, listOf(box)))
        assertFalse(router.scroll(50.0, 5.0, true, listOf(box)))
    }

    @Test
    fun `a missed release is cancelled by the next press of that button`() {
        val box = Box(0.0, 0.0, 10.0, 10.0)
        router.press(0, 5.0, 5.0, true, listOf(box))
        assertFalse(router.press(0, 50.0, 50.0, true, listOf(box)))
        assertEquals(listOf("press 0", "cancel 0"), box.log)
        assertFalse(router.release(0, 50.0, 50.0))
    }

    @Test
    fun `reconcile cancels captures whose button is no longer held`() {
        val box = Box(0.0, 0.0, 10.0, 10.0)
        router.press(0, 5.0, 5.0, true, listOf(box))
        router.press(1, 5.0, 5.0, true, listOf(box))
        router.reconcile { it == 1 }
        assertFalse(router.isCaptured(0))
        assertTrue(router.isCaptured(1))
        assertEquals(listOf("press 0", "press 1", "cancel 0"), box.log)
    }
}
