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

    @Test
    fun `observed ends lower an overstated header once everyone there has seen the end`() {
        val durations = VideoDurations()
        val other = UUID.fromString("00000000-0000-0000-0000-00000000bbbb")
        val viewers = listOf(player, other)
        durations.onVideoChanged(display, video)
        // Both headers say 6:00; the media is 3:00
        assertEquals(360_000L, durations.report(display, video, player, 360_000, 0, 1_000, present = viewers))
        assertNull(durations.report(display, video, other, 360_000, 0, 1_000, present = viewers))
        // One stream ends at 3:00: the other viewer still only knows the header, so nothing is cut short
        assertNull(durations.report(display, video, player, 180_020, 0, 181_000, observedEnd = true, present = viewers))
        assertEquals(360_000L, durations.mediaMsOf(display, video))
        // The other's ends there too: now it is 3:00 for everyone, and remembered for this video
        assertEquals(180_040L, durations.report(display, video, other, 180_040, 0, 181_050, observedEnd = true, present = viewers))
        // Their client re-sends its header length later (a report request): the measured end stays
        assertNull(durations.report(display, video, other, 360_000, 0, 200_000, present = viewers))
        assertEquals(180_040L, durations.mediaMsOf(display, video))
        assertEquals(180_040L, durations.onVideoChanged(display, video))
    }

    @Test
    fun `a viewer who reported a longer header and left no longer holds the video at that length`() {
        val durations = VideoDurations()
        val other = UUID.fromString("00000000-0000-0000-0000-00000000bbbb")
        durations.onVideoChanged(display, video)
        // Someone's client claims a day (a bogus header, or a modded client); the media is 3:00
        assertEquals(86_400_000L, durations.report(display, video, other, 86_400_000, 0, 1_000, present = listOf(player, other)))
        durations.report(display, video, player, 180_000, 0, 1_000, present = listOf(player, other))
        assertNull(durations.report(display, video, player, 180_030, 0, 181_000, observedEnd = true, present = listOf(player, other)))
        // While they stay, nothing changes; once they are gone the observed end settles it
        assertNull(durations.recheck(display, video, 0, listOf(player, other)))
        assertEquals(180_030L, durations.recheck(display, video, 0, listOf(player)))
        assertNull(durations.recheck(display, video, 0, listOf(player)))
        assertNull(durations.recheck(display, "https://youtu.be/other", 0, listOf(player)))
    }

    @Test
    fun `one early stream end never shortens the video for the others`() {
        val durations = VideoDurations()
        val other = UUID.fromString("00000000-0000-0000-0000-00000000bbbb")
        durations.onVideoChanged(display, video)
        durations.report(display, video, player, 600_000, 0, 1_000, present = listOf(player, other))
        durations.report(display, video, other, 600_000, 0, 1_000, present = listOf(player, other))
        // A cut download ends one stream at 1:00, within the report cooldown of its header report
        assertNull(durations.report(display, video, player, 60_000, 0, 1_500, observedEnd = true, present = listOf(player, other)))
        assertEquals(600_000L, durations.mediaMsOf(display, video))
        // An observed end longer than the header raises it at once, as any longer report does
        assertEquals(620_000L, durations.report(display, video, other, 620_000, 0, 5_000, observedEnd = true, present = listOf(player, other)))
        // Someone who reported and left does not count against a lower consensus of those still there
        val alone = VideoDurations()
        alone.onVideoChanged(display, video)
        alone.report(display, video, other, 600_000, 0, 1_000, present = listOf(player, other))
        assertEquals(300_000L, alone.report(display, video, player, 300_000, 0, 1_000, observedEnd = true, present = listOf(player)))
        // A queue entry's length is the least it runs, observed or not
        val queued = VideoDurations()
        queued.onVideoChanged(display, video)
        queued.report(display, video, player, 400_000, 240_000, 1_000, present = listOf(player))
        assertNull(queued.report(display, video, player, 200_000, 240_000, 5_000, observedEnd = true, present = listOf(player)))
        assertEquals(240_000L, queued.mediaMsOf(display, video))
    }
}
