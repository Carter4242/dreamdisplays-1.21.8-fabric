package com.dreamdisplays.core.queue

import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.core.queue.EndOfStreamGuard.Verdict
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Clients from before readiness reports loop at their own stream end, which can be far from the shared one. */
class StrayEndOfStreamTest {
    private val display = UUID.fromString("00000000-0000-0000-0000-0000000000e1")

    /** 90 s video started at wall 1 000; at wall 41 000 the shared clock is 40 s in, far from the end. */
    private val playing = Timeline(0, 1_000, paused = false, durationMs = 90_000, loop = true)
    private val midway = 41_000L

    @Test
    fun `only pre-readiness, pre-12 clients count every seek to the start as their stream end`() {
        assertTrue(EndOfStreamGuard.startSeekIsStreamEnd(protocolVersion = 0, supportsReadiness = false))
        // 7522afe5-era builds report readiness; protocol 12 holds its last frame and never loops by seeking
        assertFalse(EndOfStreamGuard.startSeekIsStreamEnd(protocolVersion = 0, supportsReadiness = true))
        assertFalse(EndOfStreamGuard.startSeekIsStreamEnd(protocolVersion = 12, supportsReadiness = false))
        assertFalse(EndOfStreamGuard.startSeekIsStreamEnd(protocolVersion = 12, supportsReadiness = true))
    }

    @Test
    fun `an old client's seek to the start mid-video is dropped, not a restart for everyone`() {
        val guard = EndOfStreamGuard()
        assertEquals(Verdict.STRAY_END, guard.classify(display, playing, midway, PlaybackAction.SEEK, 0, startSeekIsEnd = true))
        assertEquals(Verdict.STRAY_END, guard.classify(display, playing, midway, PlaybackAction.SEEK, 1_000, startSeekIsEnd = true))
        // Paused, or no timeline yet: still its own loop
        assertEquals(Verdict.STRAY_END, guard.classify(display, playing.copy(paused = true), midway, PlaybackAction.SEEK, 0, startSeekIsEnd = true))
        assertEquals(Verdict.STRAY_END, guard.classify(display, null, midway, PlaybackAction.SEEK, 0, startSeekIsEnd = true))
    }

    @Test
    fun `an old client's real seeks and restart button still work`() {
        val guard = EndOfStreamGuard()
        assertEquals(Verdict.USER_SEEK, guard.classify(display, playing, midway, PlaybackAction.SEEK, 1_001, startSeekIsEnd = true))
        assertEquals(Verdict.USER_SEEK, guard.classify(display, playing, midway, PlaybackAction.SEEK, 30_000, startSeekIsEnd = true))
        assertEquals(Verdict.USER_SEEK, guard.classify(display, playing, midway, PlaybackAction.RESTART, 0, startSeekIsEnd = true))
        assertEquals(Verdict.USER_SEEK, guard.classify(display, playing, midway, PlaybackAction.PAUSE, 0, startSeekIsEnd = true))
    }

    @Test
    fun `other clients' seeks to the start mid-video stay restarts for everyone`() {
        val guard = EndOfStreamGuard()
        assertEquals(Verdict.USER_SEEK, guard.classify(display, playing, midway, PlaybackAction.SEEK, 0))
        assertEquals(Verdict.USER_SEEK, guard.classify(display, playing, midway, PlaybackAction.RESTART, 0))
    }

    @Test
    fun `near the shared end it is still the one stream-ended report`() {
        val guard = EndOfStreamGuard()
        val nearEnd = 1_000 + 89_400L
        assertEquals(Verdict.STREAM_ENDED, guard.classify(display, playing, nearEnd, PlaybackAction.SEEK, 0, startSeekIsEnd = true))
        assertEquals(Verdict.DUPLICATE, guard.classify(display, playing, nearEnd + 100, PlaybackAction.SEEK, 0, startSeekIsEnd = true))
    }
}
