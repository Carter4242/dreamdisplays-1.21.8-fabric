package com.dreamdisplays.core.queue

import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.core.sync.TimelineRecords
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Display 94a16f5a, 11-11 22:30 (Pacific): a 14+ minute video set by hand at 22:21:33, four viewers. The plugin was
 * hot-swapped at 22:30:16 with the video ~8:40 in; the new instance restored the timeline, the clients handshook again,
 * and the display "reached the end of its video" and restarted from 0 for everyone. One viewer's client had seen its
 * stream end early (a cut download) and re-sent that observed end with its first report to the new instance, before
 * anyone else's: the restored length had no reporters, so that one observed end settled the length below where the
 * clock already was.
 */
class RestoredLengthTest {
    private val display = UUID.fromString("94a16f5a-0000-0000-0000-000000000001")
    private val cut = UUID.fromString("00000000-0000-0000-0000-00000000aaaa")
    private val others = (1..3).map { UUID.fromString("00000000-0000-0000-0000-00000000bbb$it") }
    private val viewers = others + cut
    private val video = "https://www.youtube.com/watch?v=pZ19rNcKA7I"
    private val headerMs = 900_000L
    private val cutAtMs = 500_000L

    /** The old instance: everyone reported the header, then [cut]'s stream ended early. */
    private fun before(durations: VideoDurations) {
        durations.onVideoChanged(display, video)
        viewers.forEach { durations.report(display, video, it, headerMs, 0, 10_000, present = viewers) }
        // The cut stream's end never shortens it while the others say otherwise
        assertNull(durations.report(display, video, cut, cutAtMs, 0, 300_000, observedEnd = true, present = viewers))
        assertEquals(headerMs, durations.mediaMsOf(display, video))
    }

    @Test
    fun `an early stream end re-sent first after a reload does not shorten the restored length`() {
        val old = VideoDurations()
        before(old)
        // Saved at the swap, restored by the next instance
        val start = 1_000_000L
        val saved = TimelineRecords.save(
            display, video, Timeline(0, start, paused = false, durationMs = QueueTiming.scheduledDurationMs(headerMs), loop = true),
            holding = false, mediaMs = old.mediaMsOf(display, video), nowMs = start + 520_000,
        )
        val now = start + 529_000
        val restored = TimelineRecords.restore(saved, now)
        val durations = VideoDurations()
        durations.seed(display, video, saved.mediaMs)

        // The cut viewer's client answers the new hello first, with its observed end
        assertNull(durations.report(display, video, cut, cutAtMs, 0, now, observedEnd = true, present = viewers))
        assertEquals(headerMs, durations.mediaMsOf(display, video))
        // Nothing for tickEnds to restart: the clock is well inside the video
        assertNull(QueueTiming.loopRestartAtMs(restored, now, 150))
        // The others' header reports follow; still the header's length
        others.forEach { assertNull(durations.report(display, video, it, headerMs, 0, now + 50, present = viewers)) }
        assertEquals(headerMs, durations.mediaMsOf(display, video))
        // And the cut viewer's next re-send changes nothing either
        assertNull(durations.recheck(display, video, 0, viewers))
    }

    @Test
    fun `after a reload observed ends still settle the length once everyone there has reported`() {
        val durations = VideoDurations()
        // Headers say 6:00, the media is 3:00; restored at the header length
        durations.seed(display, video, 360_000)
        val two = others.take(2)
        assertNull(durations.report(display, video, two[0], 180_020, 0, 1_000, observedEnd = true, present = two))
        assertEquals(360_000L, durations.mediaMsOf(display, video))
        assertEquals(180_040L, durations.report(display, video, two[1], 180_040, 0, 1_100, observedEnd = true, present = two))
        // Alone at a restored display, a viewer's observed end is the length as it would have been before the reload
        val alone = VideoDurations()
        alone.seed(display, video, 360_000)
        assertEquals(180_020L, alone.report(display, video, cut, 180_020, 0, 1_000, observedEnd = true, present = listOf(cut)))
        // Someone who left after the reload no longer holds a restored length up (recheck)
        val left = VideoDurations()
        left.seed(display, video, 360_000)
        assertNull(left.report(display, video, cut, 180_020, 0, 1_000, observedEnd = true, present = listOf(cut, others[0])))
        assertEquals(180_020L, left.recheck(display, video, 0, listOf(cut)))
    }

    @Test
    fun `a video replayed with its known length is not cut by one viewer's old observed end`() {
        val durations = VideoDurations()
        before(durations)
        durations.onVideoChanged(display, "https://youtu.be/next")
        // A looping queue (or an ended share) brings the video back: known length, nobody has reported it yet
        assertEquals(headerMs, durations.onVideoChanged(display, video))
        assertNull(durations.report(display, video, cut, cutAtMs, 0, 2_000_000, observedEnd = true, present = viewers))
        assertEquals(headerMs, durations.mediaMsOf(display, video))
    }
}
