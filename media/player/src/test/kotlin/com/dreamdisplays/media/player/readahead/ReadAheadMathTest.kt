package com.dreamdisplays.media.player.readahead

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadAheadMathTest {
    @Test
    fun `range headers map to inclusive byte ranges`() {
        assertEquals(0L..999L, ReadAheadMath.parseRange(null, 1_000))
        assertEquals(0L..999L, ReadAheadMath.parseRange("bytes=0-", 1_000))
        assertEquals(100L..999L, ReadAheadMath.parseRange("bytes=100-", 1_000))
        assertEquals(100L..199L, ReadAheadMath.parseRange("bytes=100-199", 1_000))
        assertEquals(900L..999L, ReadAheadMath.parseRange("bytes=900-5000", 1_000), "An end past the file is clipped.")
        assertEquals(990L..999L, ReadAheadMath.parseRange("bytes=-10", 1_000), "Suffix form.")
        assertEquals(0L..999L, ReadAheadMath.parseRange("bytes=-5000", 1_000))
        assertEquals(5L..5L, ReadAheadMath.parseRange("BYTES=5-5", 1_000))
    }

    @Test
    fun `unsatisfiable or malformed ranges are refused`() {
        assertNull(ReadAheadMath.parseRange("bytes=1000-", 1_000), "Starts past the end.")
        assertNull(ReadAheadMath.parseRange("bytes=5-4", 1_000))
        assertNull(ReadAheadMath.parseRange("bytes=0-1,5-6", 1_000), "Multi-range.")
        assertNull(ReadAheadMath.parseRange("items=0-1", 1_000))
        assertNull(ReadAheadMath.parseRange("bytes=abc-", 1_000))
        assertNull(ReadAheadMath.parseRange("bytes=-0", 1_000))
        assertNull(ReadAheadMath.parseRange("bytes=0-", 0))
    }

    @Test
    fun `the window covers the requested seconds at the file's bitrate, within bounds`() {
        val mib = 1L shl 20
        // 1.7 GiB over two hours is ~250 KB/s: 30 s is ~7 MB
        val window = ReadAheadMath.windowBytes(30, 1_700L * mib, 7_200_000_000_000L, 4 * mib, 48 * mib, mib)
        assertEquals(30 * (1_700L * mib / 7_200.0).toLong(), window)
        assertTrue(window in 7_000_000L..7_500_000L)
        // A low bitrate still gets the minimum, a huge one is capped
        assertEquals(4 * mib, ReadAheadMath.windowBytes(30, 10 * mib, 3_600_000_000_000L, 4 * mib, 48 * mib, mib))
        assertEquals(48 * mib, ReadAheadMath.windowBytes(30, 10_000 * mib, 60_000_000_000L, 4 * mib, 48 * mib, mib))
        // Unknown duration falls back to the assumed bitrate
        assertEquals(30 * mib, ReadAheadMath.windowBytes(30, 900 * mib, null, 4 * mib, 48 * mib, mib))
    }

    @Test
    fun `block arithmetic`() {
        assertEquals(0L, ReadAheadMath.blockOf(0, 256))
        assertEquals(0L, ReadAheadMath.blockOf(255, 256))
        assertEquals(1L, ReadAheadMath.blockOf(256, 256))
        assertEquals(256, ReadAheadMath.blockLength(0, 256, 1_000))
        assertEquals(1_000 - 3 * 256, ReadAheadMath.blockLength(3, 256, 1_000), "The last block is short.")
        assertEquals(0, ReadAheadMath.blockLength(4, 256, 1_000))
    }

    @Test
    fun `eviction drops what playback passed first and never the window ahead`() {
        val cached = (0L..30L).toList()
        val order = ReadAheadMath.evictionOrder(cached, anchor = 10, keepBehind = 2, windowEnd = 20)
        // Passed blocks (before 8) oldest first, then beyond the window farthest first, then the kept-behind ones
        assertEquals((0L..7L).toList() + (30L downTo 21L).toList() + listOf(8L, 9L), order)
        assertEquals(emptyList(), order.filter { it in 10L..20L })
    }

    @Test
    fun `the file's header is dropped only after everything else outside the window`() {
        val cached = (0L..30L).toList()
        val header = { i: Long -> i <= 2L }
        val order = ReadAheadMath.evictionOrder(cached, anchor = 10, keepBehind = 2, windowEnd = 20, pinned = header)
        assertEquals((3L..7L).toList() + (30L downTo 21L).toList() + listOf(8L, 9L) + listOf(2L, 1L, 0L), order)
        assertEquals(emptyList(), order.filter { it in 10L..20L })
    }

    @Test
    fun `header blocks are the first and last pinned bytes of the file`() {
        // 1 000-byte blocks, a 10 000-byte file, 2 500 bytes pinned at each end
        val pinned = (0L..9L).filter { ReadAheadMath.inHeader(it, 1_000, 10_000, 2_500) }
        assertEquals(listOf(0L, 1L, 2L, 7L, 8L, 9L), pinned)
        assertEquals(emptyList(), (0L..9L).filter { ReadAheadMath.inHeader(it, 1_000, 10_000, 0) })
    }
}
