package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PausedSeekPreviewTest {
    private val ms = 1_000_000L
    private val gen = 7L
    private fun seek(t: Long, fire: Boolean = false, open: Boolean = true) = PausedSeekPreview.Seek(t * ms, fire, open)
    private fun PausedSeekPreview.req(t: Long, fire: Boolean = false, now: Long = 0, g: Long = gen) = request(t * ms, fire, now, g)
    private fun PausedSeekPreview.at(now: Long, eligible: Boolean = true, busy: Boolean = false, showing: Long? = null, g: Long = gen) =
        poll(now, eligible, busy, showing, g)

    @Test
    fun `a paused seek is carried out and shown on the next tick`() {
        val p = PausedSeekPreview()
        p.req(5_000, fire = true)
        assertEquals(seek(5_000, fire = true), p.at(0))
        assertNull(p.at(1))
    }

    @Test
    fun `a resume carries the waiting seek out before it plays`() {
        val p = PausedSeekPreview()
        val order = ArrayList<String>()
        p.req(5_000, fire = true)
        p.resume(gen, seek = { order += "seek ${it.targetNanos / ms} ${it.fire} ${it.open}" }) { order += "play" }
        assertEquals(listOf("seek 5000 true false", "play"), order)
        assertFalse(p.pending)
        // Nothing waiting: just the play
        order.clear()
        p.resume(gen, seek = { order += "seek" }) { order += "play" }
        assertEquals(listOf("play"), order)
    }

    @Test
    fun `a timeline correction never replaces the player's own seek still waiting, nor gets announced`() {
        val p = PausedSeekPreview()
        p.req(40_000, fire = true)
        p.req(12_000, fire = false) // the server's old position, snapped to before it heard of the seek
        assertEquals(40_000 * ms, p.pendingTarget(gen))
        assertEquals(seek(40_000, fire = true), p.at(0))
        p.req(12_000, fire = false)
        assertEquals(seek(12_000, fire = false), p.at(200 * ms))
    }

    @Test
    fun `relative seeks build on the seek still waiting`() {
        val p = PausedSeekPreview()
        p.req(15_000, fire = true)
        assertEquals(15_000 * ms, p.pendingTarget(gen))
        assertNull(p.pendingTarget(gen + 1))
    }

    @Test
    fun `a seek for another video is dropped`() {
        val p = PausedSeekPreview()
        p.req(5_000, fire = true)
        assertNull(p.at(0, g = gen + 1))
        assertFalse(p.pending)
        p.req(5_000, fire = true)
        assertNull(p.takePending(gen + 1))
        p.req(5_000, fire = true, g = gen)
        p.req(6_000, fire = false, g = gen + 1) // a new player: the old seek goes
        assertEquals(seek(6_000), p.at(0, g = gen + 1))
    }

    @Test
    fun `a drag opens the stream once for where it ended, at most every 150 ms`() {
        val p = PausedSeekPreview()
        p.req(1_000)
        assertEquals(seek(1_000), p.at(0))
        p.req(2_000, fire = true, now = 10 * ms)
        p.req(3_000, fire = true, now = 20 * ms)
        assertNull(p.at(100 * ms))
        p.req(4_000, fire = true, now = 120 * ms)
        assertEquals(seek(4_000, fire = true), p.at(160 * ms))
    }

    @Test
    fun `it waits while an opening is under way, but not for ever`() {
        val p = PausedSeekPreview()
        p.req(1_000)
        assertNull(p.at(0, busy = true))
        p.req(9_000, now = 100 * ms)
        assertNull(p.at(1_000 * ms, busy = true))
        assertEquals(seek(9_000), p.at(1_600 * ms, busy = true))
    }

    @Test
    fun `once it may not show a frame (play, live, private) the seek is carried out without opening`() {
        val p = PausedSeekPreview()
        p.req(1_000, fire = true)
        assertEquals(seek(1_000, fire = true, open = false), p.at(0, eligible = false, busy = true))
        assertFalse(p.pending)
    }

    @Test
    fun `a new video drops the request`() {
        val p = PausedSeekPreview()
        p.req(2_000, fire = true)
        p.cancel()
        assertNull(p.at(0))
    }

    @Test
    fun `a stream already held on the target needs nothing, unless the seek must be announced`() {
        val p = PausedSeekPreview()
        p.req(5_000)
        assertNull(p.at(0, showing = 5_020 * ms))
        assertFalse(p.pending)
        p.req(5_000, fire = true)
        assertEquals(seek(5_000, fire = true, open = false), p.at(0, showing = 5_000 * ms))
        p.req(5_000)
        assertTrue(p.at(200 * ms, showing = 6_000 * ms)!!.open)
    }
}
