package com.dreamdisplays.shared.geometry

/**
 * How big a conforming (wrapped) display may be. Its mesh is read block by block from the world on the client's render
 * thread, and re-checked twice a second, so what costs is the volume, not any one side: 256 x 256 x 256 alone is 16.8
 * million block reads per check. The server refuses to create a larger one and the client refuses to show one.
 */
object ConformingLimits {
    /** 64 x 64 x 64; the default size limits (32 x 24, depth up to 32) stay far below it. */
    const val MAX_BLOCKS: Long = 262_144L

    fun fits(width: Int, height: Int, depth: Int): Boolean =
        width.toLong() * height.toLong() * depth.toLong() <= MAX_BLOCKS
}
