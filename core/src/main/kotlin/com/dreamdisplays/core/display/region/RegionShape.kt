package com.dreamdisplays.core.display.region

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The shape of a `WorldGuard` region a display is tied to (`visibility.region`), as the server reads it and clients
 * get it. Coordinates are `WorldGuard`'s own: whole blocks, inclusive, so a cuboid from `0 64 0` to `9 70 9` covers ten
 * blocks a side and `minY..maxY` is a range of block layers.
 *
 * [containsBlock] is `WorldGuard`'s membership test for a block (what released clients are held to). [distance] is a
 * continuous distance in blocks from a player's position (feet) to the region, 0 inside, which the fade and the load
 * range are measured with; it agrees with [containsBlock] to within half a block at a polygon's slanted edges.
 */
sealed class RegionShape {
    /** Lowest block layer inside the region. */
    abstract val minY: Int

    /** Highest block layer inside the region. */
    abstract val maxY: Int

    /** True when `WorldGuard` counts the block at [x] [y] [z] as inside. */
    abstract fun containsBlock(x: Int, y: Int, z: Int): Boolean

    /** Horizontal distance from the point ([x], [z]) to the region's footprint; 0 inside it. */
    abstract fun horizontalDistance(x: Double, z: Double): Double

    /**
     * Distance in blocks from a player standing at [x] [y] [z] (feet) to the region; 0 inside. Vertically the region
     * spans `minY` (the bottom of its lowest layer) to `maxY + 1` (the top of its highest).
     */
    fun distance(x: Double, y: Double, z: Double): Double {
        val dh = horizontalDistance(x, z)
        val dy = maxOf(minY - y, 0.0, y - (maxY + 1))
        return if (dy == 0.0) dh else sqrt(dh * dh + dy * dy)
    }

    /** [containsBlock] for the block a player at [x] [y] [z] stands in. */
    fun containsPosition(x: Double, y: Double, z: Double): Boolean =
        containsBlock(floor(x).toInt(), floor(y).toInt(), floor(z).toInt())

    /** An axis-aligned box of whole blocks (a `WorldGuard` cuboid region). */
    data class Cuboid(
        val minX: Int, override val minY: Int, val minZ: Int,
        val maxX: Int, override val maxY: Int, val maxZ: Int,
    ) : RegionShape() {
        init {
            require(minX <= maxX && minY <= maxY && minZ <= maxZ) { "min must not exceed max" }
        }

        override fun containsBlock(x: Int, y: Int, z: Int): Boolean =
            x in minX..maxX && y in minY..maxY && z in minZ..maxZ

        override fun horizontalDistance(x: Double, z: Double): Double {
            val dx = maxOf(minX - x, 0.0, x - (maxX + 1))
            val dz = maxOf(minZ - z, 0.0, z - (maxZ + 1))
            return if (dx == 0.0) dz else if (dz == 0.0) dx else sqrt(dx * dx + dz * dz)
        }
    }

    /**
     * A `WorldGuard` polygon region: the corners ([xs], [zs], at least three, in order) of its footprint, which may be
     * concave, between block layers [minY] and [maxY].
     *
     * Its blocks are those whose (x, z) lies inside or on the polygon through the corners. For [distance] the polygon
     * is moved to the blocks' centres and widened by half a block, so a rectangle covers the same ground as the
     * matching cuboid (its corners rounded).
     */
    class Polygon(xs: List<Int>, zs: List<Int>, override val minY: Int, override val maxY: Int) : RegionShape() {
        val xs: List<Int> = xs.toList()
        val zs: List<Int> = zs.toList()
        private val minX: Int
        private val maxX: Int
        private val minZ: Int
        private val maxZ: Int

        init {
            require(xs.size == zs.size) { "as many x as z coordinates" }
            require(xs.size >= 3) { "a polygon needs at least three corners" }
            require(minY <= maxY) { "minY must not exceed maxY" }
            minX = xs.min()
            maxX = xs.max()
            minZ = zs.min()
            maxZ = zs.max()
        }

        /** `WorldGuard`'s own test (`ProtectedPolygonalRegion.contains`): corners and edges count as inside. */
        override fun containsBlock(x: Int, y: Int, z: Int): Boolean {
            if (y < minY || y > maxY) return false
            if (x < minX || x > maxX || z < minZ || z > maxZ) return false
            var inside = false
            val n = xs.size
            var xOld = xs[n - 1]
            var zOld = zs[n - 1]
            for (i in 0 until n) {
                val xNew = xs[i]
                val zNew = zs[i]
                if (xNew == x && zNew == z) return true
                val x1: Int
                val z1: Int
                val x2: Int
                val z2: Int
                if (xNew > xOld) {
                    x1 = xOld; x2 = xNew; z1 = zOld; z2 = zNew
                } else {
                    x1 = xNew; x2 = xOld; z1 = zNew; z2 = zOld
                }
                if (x in x1..x2) {
                    val cross = (z.toLong() - z1) * (x2 - x1).toLong() - (z2.toLong() - z1) * (x - x1).toLong()
                    if (cross == 0L) {
                        if ((z1 <= z) == (z <= z2)) return true
                    } else if (cross < 0 && x1 != x) {
                        inside = !inside
                    }
                }
                xOld = xNew
                zOld = zNew
            }
            return inside
        }

        override fun horizontalDistance(x: Double, z: Double): Double {
            // Block space: a block's corner (bx, bz) stands for its centre (bx + 0.5, bz + 0.5)
            val px = x - 0.5
            val pz = z - 0.5
            if (insideOrOn(px, pz)) return 0.0
            var best = Double.MAX_VALUE
            val n = xs.size
            for (i in 0 until n) {
                val j = if (i == 0) n - 1 else i - 1
                best = minOf(best, segmentDistanceSq(px, pz, xs[j].toDouble(), zs[j].toDouble(), xs[i].toDouble(), zs[i].toDouble()))
            }
            return (sqrt(best) - HALF_BLOCK).coerceAtLeast(0.0)
        }

        /** Even-odd test of a point against the polygon (points on an edge may go either way; their distance is 0). */
        private fun insideOrOn(px: Double, pz: Double): Boolean {
            if (px < minX || px > maxX || pz < minZ || pz > maxZ) return false
            var inside = false
            val n = xs.size
            var j = n - 1
            for (i in 0 until n) {
                val xi = xs[i].toDouble()
                val zi = zs[i].toDouble()
                val xj = xs[j].toDouble()
                val zj = zs[j].toDouble()
                if ((zi > pz) != (zj > pz) && px < (xj - xi) * (pz - zi) / (zj - zi) + xi) inside = !inside
                j = i
            }
            return inside
        }

        override fun equals(other: Any?): Boolean =
            other is Polygon && xs == other.xs && zs == other.zs && minY == other.minY && maxY == other.maxY

        override fun hashCode(): Int = ((xs.hashCode() * 31 + zs.hashCode()) * 31 + minY) * 31 + maxY

        override fun toString(): String = "Polygon(${xs.indices.joinToString(" ") { "${xs[it]},${zs[it]}" }}, y $minY..$maxY)"

        private companion object {
            const val HALF_BLOCK = 0.5

            fun segmentDistanceSq(px: Double, pz: Double, ax: Double, az: Double, bx: Double, bz: Double): Double {
                val dx = bx - ax
                val dz = bz - az
                val lengthSq = dx * dx + dz * dz
                val t = if (lengthSq == 0.0) 0.0 else (((px - ax) * dx + (pz - az) * dz) / lengthSq).coerceIn(0.0, 1.0)
                val cx = ax + t * dx - px
                val cz = az + t * dz - pz
                return cx * cx + cz * cz
            }
        }
    }
}
