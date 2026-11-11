package com.dreamdisplays.core.queue

import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.core.queue.EndOfStreamGuard.Verdict
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A client's end-of-stream loop on a synced display, and the server's exact end-instant switch to the next entry. */
class QueueEndOfStreamTest {
    private val display = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val duration = 90_000L

    /** A looping synced timeline started at wall time 1 000 (server timelines always loop). */
    private val playing = Timeline(0, 1_000, paused = false, durationMs = duration, loop = true)

    @Test
    fun `switch is scheduled at the exact end instant`() {
        // 300 ms before the end (wall 91 000)
        assertEquals(91_000L, QueueTiming.switchAtMs(playing, 90_700, earlyMs = 400))
        // At double speed the content left takes half as long
        val fast = Timeline(80_000, 10_000, paused = false, durationMs = duration, loop = true, rate = 2.0)
        assertEquals(15_000L, QueueTiming.switchAtMs(fast, 14_800, earlyMs = 400))
        // A start scheduled ahead of now: the end counts from that start
        val scheduled = Timeline(0, 5_000, paused = false, durationMs = 1_000, loop = true)
        assertEquals(6_000L, QueueTiming.switchAtMs(scheduled, 4_000, earlyMs = 3_000))
        // Second play-through of a looping timeline ends one duration later
        assertEquals(181_000L, QueueTiming.switchAtMs(playing, 180_800, earlyMs = 400))
    }

    @Test
    fun `a late tick switches now`() {
        // Ticked 200 ms after the wrap: the end is past, start the next entry at once
        assertEquals(91_200L, QueueTiming.switchAtMs(playing, 91_200, earlyMs = 400))
        val once = playing.copy(loop = false)
        assertEquals(95_000L, QueueTiming.switchAtMs(once, 95_000, earlyMs = 400))
        assertNull(QueueTiming.switchAtMs(playing.copy(paused = true), 90_700, earlyMs = 400))
        assertNull(QueueTiming.switchAtMs(playing.copy(durationMs = 0), 90_700, earlyMs = 400))
    }

    @Test
    fun `client end of stream seek near the end is the stream ending (queue advances at the end instant)`() {
        val guard = EndOfStreamGuard()
        // The incident: a client's stream ended 0.6 s before the shared clock and it looped by seeking to 0
        val now = 1_000 + 89_375L
        assertEquals(Verdict.STREAM_ENDED, guard.classify(display, playing, now, PlaybackAction.SEEK, 0))
        // With something queued the server switches at the timeline's own end, not at the client's
        assertEquals(91_000L, QueueTiming.switchAtMs(playing, now, QueueTiming.END_OF_STREAM_WINDOW_MS * 2))
    }

    @Test
    fun `without a queue the report is still not a seek (the timeline wraps by itself)`() {
        val guard = EndOfStreamGuard()
        assertEquals(Verdict.STREAM_ENDED, guard.classify(display, playing, 1_000 + 89_900, PlaybackAction.RESTART, 0))
        // Just after the wrap
        assertEquals(Verdict.STREAM_ENDED, EndOfStreamGuard().classify(display, playing, 91_500, PlaybackAction.SEEK, 0))
        assertTrue(QueueTiming.isEndOfStreamLoop(playing.copy(loop = false), 120_000, 0))
    }

    @Test
    fun `a real seek to 0 mid video still works`() {
        val guard = EndOfStreamGuard()
        assertEquals(Verdict.USER_SEEK, guard.classify(display, playing, 1_000 + 40_000, PlaybackAction.SEEK, 0))
        assertEquals(Verdict.USER_SEEK, guard.classify(display, playing, 1_000 + 40_000, PlaybackAction.RESTART, 0))
        // Near the end, but a seek somewhere else than the start
        assertEquals(Verdict.USER_SEEK, guard.classify(display, playing, 1_000 + 89_500, PlaybackAction.SEEK, 30_000))
        // Paused display: a client stream does not end under a paused timeline
        assertFalse(QueueTiming.isEndOfStreamLoop(playing.copy(paused = true), 1_000 + 89_500, 0))
        // Play / pause are never end-of-stream reports
        assertEquals(Verdict.USER_SEEK, guard.classify(display, playing, 1_000 + 89_500, PlaybackAction.PAUSE, 0))
        // And after the reports of an end are over, a seek to 0 is a seek again
        assertEquals(Verdict.STREAM_ENDED, guard.classify(display, playing, 1_000 + 89_500, PlaybackAction.SEEK, 0))
        assertEquals(Verdict.USER_SEEK, guard.classify(display, playing, 1_000 + 90_000 + 30_000, PlaybackAction.SEEK, 0))
    }

    @Test
    fun `duplicate reports from other clients are dropped`() {
        val guard = EndOfStreamGuard()
        assertEquals(Verdict.STREAM_ENDED, guard.classify(display, playing, 90_329, PlaybackAction.SEEK, 0))
        // Other clients' streams end a little later, some after the wrap
        assertEquals(Verdict.DUPLICATE, guard.classify(display, playing, 90_351, PlaybackAction.SEEK, 0))
        assertEquals(Verdict.DUPLICATE, guard.classify(display, playing, 91_400, PlaybackAction.RESTART, 0))
        // After a queue switch the new video sits at 0: a late old-client loop is still the old video's end
        val next = Timeline(0, 91_000, paused = false, durationMs = 0, loop = true)
        guard.onVideoChanged(display, 91_000)
        assertEquals(Verdict.DUPLICATE, guard.classify(display, next, 93_500, PlaybackAction.SEEK, 0))
    }

    @Test
    fun `concurrent duplicate reports yield exactly one end`() {
        val guard = EndOfStreamGuard()
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<Verdict>())
        repeat(32) {
            pool.execute {
                go.await()
                results += guard.classify(display, playing, 90_500, PlaybackAction.SEEK, 0)
            }
        }
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(1, results.count { it == Verdict.STREAM_ENDED })
        assertEquals(31, results.count { it == Verdict.DUPLICATE })
    }

    @Test
    fun `a seek during a start hold does not leave the display paused`() {
        // wasPaused=true in the log was the start hold, not a viewer's pause: the display must play after the seek
        assertTrue(EndOfStreamGuard.playsAfterJump(PlaybackAction.SEEK, paused = true, holding = true))
        assertTrue(EndOfStreamGuard.playsAfterJump(PlaybackAction.SEEK, paused = false, holding = false))
        // A display a viewer paused stays paused through a seek
        assertFalse(EndOfStreamGuard.playsAfterJump(PlaybackAction.SEEK, paused = true, holding = false))
        assertTrue(EndOfStreamGuard.playsAfterJump(PlaybackAction.RESTART, paused = true, holding = false))
    }

    @Test
    fun `only clients before protocol 12 loop their own player at the end`() {
        kotlin.test.assertTrue(EndOfStreamGuard.loopsOwnEnd(0), "release 11 sends no version")
        kotlin.test.assertTrue(EndOfStreamGuard.loopsOwnEnd(11))
        kotlin.test.assertFalse(EndOfStreamGuard.loopsOwnEnd(12))
    }
}
