package com.dreamdisplays.core.display.region

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegionShapeTest {
    private val eps = 1e-9

    // Blocks 0..9 x 64..69 x 0..9: ground from x 0 to 10, feet from y 64 to 70
    private val box = RegionShape.Cuboid(0, 64, 0, 9, 69, 9)

    @Test
    fun `cuboid blocks are inclusive`() {
        assertTrue(box.containsBlock(0, 64, 0))
        assertTrue(box.containsBlock(9, 69, 9))
        assertFalse(box.containsBlock(10, 64, 0))
        assertFalse(box.containsBlock(-1, 64, 0))
        assertFalse(box.containsBlock(5, 63, 5))
        assertFalse(box.containsBlock(5, 70, 5))
        assertTrue(box.containsPosition(9.99, 64.0, 0.01))
        assertFalse(box.containsPosition(10.0, 64.0, 5.0))
    }

    @Test
    fun `cuboid distance covers whole blocks and is zero inside`() {
        assertEquals(0.0, box.distance(5.0, 65.0, 5.0), eps)
        assertEquals(0.0, box.distance(0.0, 64.0, 10.0), eps)   // on the faces
        assertEquals(0.0, box.distance(10.0, 70.0, 0.0), eps)
        assertEquals(1.5, box.distance(11.5, 65.0, 5.0), eps)   // past the far side of block 9
        assertEquals(2.0, box.distance(-2.0, 65.0, 5.0), eps)
        assertEquals(3.0, box.distance(5.0, 61.0, 5.0), eps)    // below
        assertEquals(2.0, box.distance(5.0, 72.0, 5.0), eps)    // above the top layer (69 + 1)
        assertEquals(5.0, box.distance(13.0, 65.0, 14.0), eps)  // corner: 3, 4
        assertEquals(sqrt(3.0), box.distance(11.0, 71.0, 11.0), eps)
    }

    @Test
    fun `distance grows monotonically walking away`() {
        var last = -1.0
        var x = 9.0
        while (x < 30.0) {
            val d = box.distance(x, 65.0, 5.0)
            assertTrue(d >= last, "at $x")
            last = d
            x += 0.25
        }
    }

    // A rectangle as a polygon: same blocks as the cuboid
    private val rect = RegionShape.Polygon(listOf(0, 9, 9, 0), listOf(0, 0, 9, 9), 64, 69)

    @Test
    fun `rectangular polygon holds the same blocks as the cuboid`() {
        for (x in -2..12) for (z in -2..12) for (y in listOf(63, 64, 69, 70)) {
            assertEquals(box.containsBlock(x, y, z), rect.containsBlock(x, y, z), "$x $y $z")
        }
    }

    @Test
    fun `rectangular polygon distance matches the cuboid along the sides`() {
        assertEquals(0.0, rect.distance(5.0, 65.0, 5.0), eps)
        assertEquals(0.0, rect.distance(9.9, 65.0, 5.0), eps)
        for (d in listOf(0.5, 1.0, 2.5, 7.0)) {
            assertEquals(box.distance(10.0 + d, 65.0, 5.0), rect.distance(10.0 + d, 65.0, 5.0), eps)
            assertEquals(box.distance(-d, 65.0, 5.0), rect.distance(-d, 65.0, 5.0), eps)
            assertEquals(box.distance(5.0, 65.0, 10.0 + d), rect.distance(5.0, 65.0, 10.0 + d), eps)
        }
        // Corners are rounded off (half a block): at most about a fifth of a block further than the cuboid's
        val diff = rect.distance(13.0, 65.0, 14.0) - box.distance(13.0, 65.0, 14.0)
        assertTrue(diff in 0.0..0.21, "corner diff $diff")
        assertEquals(2.0, rect.distance(5.0, 72.0, 5.0), eps)
    }

    // An L: the square 0..20 x 0..20 minus its corner 10..20 x 10..20 (concave at 10,10)
    private val ell = RegionShape.Polygon(listOf(0, 20, 20, 10, 10, 0), listOf(0, 0, 10, 10, 20, 20), 0, 10)

    @Test
    fun `concave polygon follows WorldGuard's block test`() {
        assertTrue(ell.containsBlock(5, 5, 5))
        assertTrue(ell.containsBlock(15, 5, 5))
        assertTrue(ell.containsBlock(5, 5, 15))
        assertFalse(ell.containsBlock(15, 5, 15))   // the notch
        assertTrue(ell.containsBlock(10, 5, 10))    // the inner corner
        assertTrue(ell.containsBlock(15, 5, 10))    // on an edge
        assertTrue(ell.containsBlock(10, 5, 15))
        assertTrue(ell.containsBlock(20, 5, 0))     // a corner
        assertFalse(ell.containsBlock(11, 5, 11))
        assertFalse(ell.containsBlock(21, 5, 5))
        assertFalse(ell.containsBlock(5, 11, 5))
        assertFalse(ell.containsBlock(5, -1, 5))
    }

    @Test
    fun `concave polygon distance measures to the nearest edge, notch included`() {
        assertEquals(0.0, ell.distance(5.5, 5.0, 5.5), eps)
        assertEquals(0.0, ell.distance(15.5, 5.0, 5.5), eps)
        // In the notch, 3 blocks past the edge z = 10 (block row 10 covers z 10..11)
        assertEquals(3.0, ell.distance(16.5, 5.0, 14.0), eps)
        assertEquals(2.5, ell.distance(13.5, 5.0, 16.5), eps)   // 2.5 past the edge x = 10
        // Deep in the notch, to the inner corner: nearer than the bounding box would say (which is 0)
        assertTrue(ell.distance(18.5, 5.0, 18.5) > 7.0)
        // Outside the outer edge
        assertEquals(4.0, ell.distance(25.0, 5.0, 5.5), eps)
        // Vertical bound: 0..10 layers, feet up to 11
        assertEquals(0.0, ell.distance(5.5, 11.0, 5.5), eps)
        assertEquals(2.0, ell.distance(5.5, 13.0, 5.5), eps)
        assertEquals(5.0, ell.distance(5.5, -5.0, 5.5), eps)
        // Diagonally below and outside: horizontal 4, vertical 3
        assertEquals(5.0, ell.distance(25.0, -3.0, 5.5), eps)
    }

    @Test
    fun `triangle with a slanted edge`() {
        val tri = RegionShape.Polygon(listOf(0, 10, 0), listOf(0, 0, 10), 0, 0)
        assertTrue(tri.containsBlock(5, 0, 5))   // on the hypotenuse
        assertTrue(tri.containsBlock(4, 0, 5))
        assertFalse(tri.containsBlock(6, 0, 5))
        assertEquals(0.0, tri.distance(2.5, 0.0, 2.5), eps)
        // A point 3 blocks out from the hypotenuse x + z = 10 (block space, then less the half-block margin)
        val px = 5.0 + 3.0 / sqrt(2.0) + 0.5
        val pz = 5.0 + 3.0 / sqrt(2.0) + 0.5
        assertEquals(2.5, tri.distance(px, 0.5, pz), 1e-6)
    }

    @Test
    fun `bad shapes are refused`() {
        assertFailsWith<IllegalArgumentException> { RegionShape.Polygon(listOf(0, 1), listOf(0, 1), 0, 1) }
        assertFailsWith<IllegalArgumentException> { RegionShape.Polygon(listOf(0, 1, 2), listOf(0, 1), 0, 1) }
        assertFailsWith<IllegalArgumentException> { RegionShape.Polygon(listOf(0, 1, 2), listOf(0, 1, 0), 5, 1) }
        assertFailsWith<IllegalArgumentException> { RegionShape.Cuboid(5, 0, 0, 1, 0, 0) }
    }
}
