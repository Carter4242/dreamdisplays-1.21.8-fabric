package com.dreamdisplays.core.queue

import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.core.karaoke.KaraokeTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The end of a video with nothing queued, as the server's 100 ms task sees it: the loop restart runs before the karaoke
 * check, so the song-end decision has to come from the restart itself; an entry queued while the restart is still
 * ahead takes over at that end.
 */
class LoopRestartEndsSongTest {
    private val duration = 90_000L
    private val earlyMs = 150L
    private val songEndMs = 100L
    private val playing = Timeline(0, 1_000, paused = false, durationMs = duration, loop = true)

    /** Runs 100 ms ticks from [startMs] through the end: loop restart first, then the karaoke check, like the server. */
    private fun simulate(startMs: Long, useRestart: Boolean): Pair<Boolean, Timeline> {
        var timeline = playing
        var songOver = false
        var now = startMs
        while (now < 93_000L && !songOver) {
            var restarted = false
            QueueTiming.loopRestartAtMs(timeline, now, earlyMs)?.let { at ->
                timeline = Timeline(0, at, paused = false, durationMs = duration, loop = true)
                restarted = true
            }
            val untilEnd = QueueTiming.msUntilEnd(timeline, now)
            songOver = KaraokeTiming.lineSongOver("song", "song", untilEnd, songEndMs, useRestart && restarted)
            now += 100
        }
        return songOver to timeline
    }

    @Test
    fun `without the restart signal the song-end check never sees the end`() {
        // Every tick phase: the restart always lands first and re-anchors the clock at 0
        for (phase in 0L until 100L step 10L) assertFalse(simulate(89_000L + phase, useRestart = false).first, "phase $phase")
    }

    @Test
    fun `the restart ends the line song at every tick phase`() {
        for (phase in 0L until 100L step 10L) assertTrue(simulate(89_000L + phase, useRestart = true).first, "phase $phase")
    }

    @Test
    fun `other reasons a line song is over still count`() {
        assertTrue(KaraokeTiming.lineSongOver("a", "b", 50_000L, songEndMs, restartedAtEnd = false))
        assertTrue(KaraokeTiming.lineSongOver("a", "a", 80L, songEndMs, restartedAtEnd = false))
        assertFalse(KaraokeTiming.lineSongOver("a", "a", 50_000L, songEndMs, restartedAtEnd = false))
        assertFalse(KaraokeTiming.lineSongOver("a", "a", null, songEndMs, restartedAtEnd = false))
    }

    @Test
    fun `an entry queued while the restart is still ahead takes over at the end instant`() {
        val restartAt = QueueTiming.loopRestartAtMs(playing, 90_900L, earlyMs)!!
        val restarted = Timeline(0, restartAt, paused = false, durationMs = duration, loop = true)
        assertEquals(91_000L, QueueTiming.pendingRestartAtMs(restarted, restarted, 90_950L))
        // Once the video has started over, an add waits for the next end like any add after a wrap
        assertNull(QueueTiming.pendingRestartAtMs(restarted, restarted, 91_000L))
        // Anything that replaced the restart (a pause, seek, new video) is not that end any more
        assertNull(QueueTiming.pendingRestartAtMs(restarted, restarted.copy(paused = true), 90_950L))
        assertNull(QueueTiming.pendingRestartAtMs(null, restarted, 90_950L))
    }

    @Test
    fun `without the pending restart such an add would wait a full play-through`() {
        val restarted = Timeline(0, 91_000L, paused = false, durationMs = duration, loop = true)
        // Armed at 90 950 (the add), checked at the next queue tick, 91 050: no end since the anchor
        assertFalse(QueueTiming.advanceDue(restarted, 91_050L, 300L, armedAtMs = 90_950L))
    }
}
