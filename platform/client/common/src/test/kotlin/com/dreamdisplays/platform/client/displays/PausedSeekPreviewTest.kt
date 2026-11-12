package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PausedSeekPreviewTest {
    private val ms = 1_000_000L
    private fun seek(t: Long, fire: Boolean = false, open: Boolean = true) = PausedSeekPreview.Seek(t * ms, fire, open)

    @Test
    fun `a paused seek is carried out and shown on the next tick`() {
        val p = PausedSeekPreview()
        p.request(5_000 * ms, fire = true, nowNanos = 0)
        assertEquals(seek(5_000, fire = true), p.poll(0, eligible = true, busy = false, showingNanos = null))
        assertNull(p.poll(1, eligible = true, busy = false, showingNanos = null))
    }

    @Test
    fun `a drag opens the stream once for where it ended, at most every 150 ms, announced if any step was`() {
        val p = PausedSeekPreview()
        p.request(1_000 * ms, fire = false, nowNanos = 0)
        assertEquals(seek(1_000), p.poll(0, true, false, null))
        p.request(2_000 * ms, fire = true, nowNanos = 10 * ms)
        p.request(3_000 * ms, fire = false, nowNanos = 20 * ms)
        assertNull(p.poll(100 * ms, true, false, null))
        p.request(4_000 * ms, fire = false, nowNanos = 120 * ms)
        assertEquals(seek(4_000, fire = true), p.poll(160 * ms, true, false, null))
    }

    @Test
    fun `it waits while an opening is under way, but not for ever`() {
        val p = PausedSeekPreview()
        p.request(1_000 * ms, fire = false, nowNanos = 0)
        assertNull(p.poll(0, eligible = true, busy = true, showingNanos = null))
        p.request(9_000 * ms, fire = false, nowNanos = 100 * ms)
        assertNull(p.poll(1_000 * ms, eligible = true, busy = true, showingNanos = null))
        assertEquals(seek(9_000), p.poll(1_600 * ms, eligible = true, busy = true, showingNanos = null))
    }

    @Test
    fun `once it may not show a frame (play, live, private) the seek is carried out without opening`() {
        val p = PausedSeekPreview()
        p.request(1_000 * ms, fire = true, nowNanos = 0)
        assertEquals(seek(1_000, fire = true, open = false), p.poll(0, eligible = false, busy = true, showingNanos = null))
        assertFalse(p.pending)
    }

    @Test
    fun `a new video drops the request`() {
        val p = PausedSeekPreview()
        p.request(2_000 * ms, fire = true, nowNanos = 0)
        p.cancel()
        assertNull(p.poll(0, eligible = true, busy = false, showingNanos = null))
    }

    @Test
    fun `a stream already held on the target needs nothing, unless the seek must be announced`() {
        val p = PausedSeekPreview()
        p.request(5_000 * ms, fire = false, nowNanos = 0)
        assertNull(p.poll(0, eligible = true, busy = false, showingNanos = 5_020 * ms))
        assertFalse(p.pending)
        p.request(5_000 * ms, fire = true, nowNanos = 0)
        assertEquals(seek(5_000, fire = true, open = false), p.poll(0, true, false, 5_000 * ms))
        p.request(5_000 * ms, fire = false, nowNanos = 0)
        assertTrue(p.poll(200 * ms, true, false, 6_000 * ms)!!.open)
    }
}
