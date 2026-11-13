package com.dreamdisplays.media.player.managers

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeekCostTest {
    @Test
    fun `a stream with no seek yet or a fast in-place last seek is cheap`() {
        assertTrue(SeekCost.cheap(-1L, lastInPlace = false, sinceSlowMs = null))
        assertTrue(SeekCost.cheap(35L, lastInPlace = true, sinceSlowMs = null))
        assertFalse(SeekCost.cheap(35L, lastInPlace = false, sinceSlowMs = null), "A reopen is never cheap.")
        assertFalse(SeekCost.cheap(1_915L, lastInPlace = true, sinceSlowMs = null))
    }

    @Test
    fun `one slow seek keeps the stream expensive for a minute even after fast ones`() {
        // The owner's log: 1 915 ms, then 35 ms, 41 ms, 19 ms in-place seeks a few seconds apart
        assertTrue(SeekCost.marksSlow(1_915L, inPlace = true))
        assertFalse(SeekCost.cheap(35L, lastInPlace = true, sinceSlowMs = 3_000L))
        assertFalse(SeekCost.cheap(19L, lastInPlace = true, sinceSlowMs = SeekCost.SLOW_MEMORY_MS - 1))
        assertTrue(SeekCost.cheap(19L, lastInPlace = true, sinceSlowMs = SeekCost.SLOW_MEMORY_MS))
    }

    @Test
    fun `only a slow in-place seek marks the stream slow`() {
        assertFalse(SeekCost.marksSlow(SeekCost.CHEAP_MS, inPlace = true))
        assertTrue(SeekCost.marksSlow(SeekCost.CHEAP_MS + 1, inPlace = true))
        assertFalse(SeekCost.marksSlow(3_000L, inPlace = false), "A reopen is judged by the last seek alone, as before.")
    }
}
