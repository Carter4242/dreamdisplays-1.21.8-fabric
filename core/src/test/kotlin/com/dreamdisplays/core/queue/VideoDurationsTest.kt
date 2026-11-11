package com.dreamdisplays.core.queue

import com.dreamdisplays.api.playback.model.Timeline
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Display 619a9ce2: a 7:03 video, three screen shares that failed and ended within a second each, two speed changes,
 * then the end of the video. The display must know its length throughout and come round again at the end.
 */
class VideoDurationsTest {
    private val display = UUID.fromString("619a9ce2-0000-0000-0000-000000000001")
    private val player = UUID.fromString("00000000-0000-0000-0000-00000000aaaa")
    private val video = "https://youtu.be/abc"
    private val relay = "srt://relay.example/live/dd123"
    private val mediaMs = 423_346L

    @Test
    fun `quick screen shares never leave the display without its length`() {
        val durations = VideoDurations()
        assertEquals(0L, durations.onVideoChanged(display, video))
        assertEquals(mediaMs, durations.report(display, video, player, mediaMs, entryMs = 0, nowMs = 10_000))

        // Each share: live (no duration), then back to the video; the client reloads it and reports again at once
        var now = 45_000L
        repeat(3) {
            assertEquals(0L, durations.onVideoChanged(display, relay))
            now += 1_000
            // Back to the same video: its length is known before any report
            assertEquals(mediaMs, durations.onVideoChanged(display, video))
            now += 600
            // The client's re-report of the same length changes nothing, and never lowers it
            assertNull(durations.report(display, video, player, mediaMs, entryMs = 0, nowMs = now))
            assertNull(durations.report(display, video, UUID.randomUUID(), 30_000, entryMs = 0, nowMs = now))
            assertEquals(mediaMs, durations.mediaMsOf(display, video))
            now += 3_000
        }
        // A new video's first report is heard even within the cooldown of this viewer's report on the previous one
        val next = "https://youtu.be/next"
        assertEquals(0L, durations.onVideoChanged(display, next))
        durations.report(display, next, player, 60_000, entryMs = 0, nowMs = now)
        durations.onVideoChanged(display, "https://youtu.be/third")
        assertEquals(90_000L, durations.report(display, "https://youtu.be/third", player, 90_000, entryMs = 0, nowMs = now + 500))
        // The same viewer again within the cooldown, same video: nothing to apply
        assertNull(durations.report(display, "https://youtu.be/third", player, 95_000, entryMs = 0, nowMs = now + 600))

        // The timeline runs with that length; speed changes keep it, and it comes round at the end
        val scheduled = QueueTiming.scheduledDurationMs(mediaMs)
        val playing = Timeline(0, 50_000, paused = false, durationMs = scheduled, loop = true)
            .withRate(1.25, 250_000).withRate(1.0, 262_000)
        assertEquals(scheduled, playing.durationMs)
        val endAt = QueueTiming.loopRestartAtMs(playing, nowMs = 50_000 + scheduled + 10_000, earlyMs = 150)
        // Past the end it has wrapped by itself; just before its next end a restart is scheduled at that end
        assertNull(endAt)
        val nextEnd = playing.serverTimeMs + (scheduled - playing.positionMs) + scheduled
        assertNotNull(QueueTiming.loopRestartAtMs(playing, nextEnd - 100, earlyMs = 150))
    }

    @Test
    fun `a different video is not given another one's length`() {
        val durations = VideoDurations()
        durations.onVideoChanged(display, video)
        durations.report(display, video, player, mediaMs, entryMs = 0, nowMs = 1_000)
        assertEquals(0L, durations.onVideoChanged(display, "https://youtu.be/other"))
        // A report naming another video than the current one starts that video's own count
        assertEquals(30_000L, durations.report(display, "https://youtu.be/other", player, 30_000, entryMs = 0, nowMs = 1_500))
        assertEquals(30_000L, durations.onVideoChanged(display, "https://youtu.be/other"))
        durations.forget(display)
        assertEquals(0L, durations.onVideoChanged(display, "https://youtu.be/other"))
    }

    @Test
    fun `a restored timeline's length is the floor of its video`() {
        val durations = VideoDurations()
        durations.seed(display, video, mediaMs)
        assertEquals(mediaMs, durations.mediaMsOf(display, video))
        assertNull(durations.report(display, video, player, 30_000, entryMs = 0, nowMs = 1_000))
        assertEquals(mediaMs, durations.onVideoChanged(display, video))
    }
}
