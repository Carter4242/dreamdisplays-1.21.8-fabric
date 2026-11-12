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

    /**
     * 11-11 20:07:07, 20:18:52, 20:22:06, 21:52:39: each time display 94a16f5a moved on to a queued video with a known
     * length, its first timeline had none ("its timeline had lost its video's length; restored it" in the same second,
     * from the entry's length) whenever no client reported before the next tick.
     */
    @Test
    fun `a queued video's timeline has the entry's length from the start`() {
        val durations = VideoDurations()
        before(durations)
        val next = "https://www.youtube.com/watch?v=queued"
        // A new URL: nothing settled, the entry says 4:00
        assertEquals(240_000L, durations.onVideoChanged(display, next, entryMs = 240_000))
        assertEquals(240_000L, durations.mediaMsOf(display, next))
        // Viewers' reports go on as before: the media's own length, never below the entry's
        assertEquals(240_400L, durations.report(display, next, cut, 240_400, 240_000, 1_000, present = viewers))
        assertNull(durations.report(display, next, others[0], 200_000, 240_000, 1_000, present = viewers))
        // No entry, nothing known: no length yet (clients report it)
        assertEquals(0L, durations.onVideoChanged(display, "https://youtu.be/fresh", entryMs = 0))
        // A replay keeps the larger of what was settled and the entry
        assertEquals(240_400L, durations.onVideoChanged(display, next, entryMs = 240_000))
    }

    @Test
    fun `a queued video that ended while the plugin was down moves on at once after the load`() {
        val added = 1_000_000L
        val item = QueueItem("i1", "https://youtu.be/next", "", "", 10_000, "", cut, "a", added)
        // 15 s video started at 1 000 000; unloaded at +12 s, loaded at +18 s (ended at +15.15 s while down)
        val timeline = Timeline(0, added, paused = false, durationMs = QueueTiming.scheduledDurationMs(15_000), loop = true)
        val loadedAt = added + 18_000
        val armed = QueueTiming.armedAfterRestore(listOf(item), loadedAt)
        assertEquals(added, armed)
        assertEquals(true, QueueTiming.advanceDue(timeline, loadedAt + 100, 300, armed))
        assertEquals(loadedAt + 100, QueueTiming.switchAtMs(timeline, loadedAt + 100, 300))
        // Armed at the load instead (as before), the end during the downtime did not count: it played again first
        assertEquals(false, QueueTiming.advanceDue(timeline, loadedAt + 100, 300, loadedAt))
        // No add time on record (an old save): the load time, as before
        assertEquals(loadedAt, QueueTiming.armedAfterRestore(listOf(item.copy(addedAtMs = 0)), loadedAt))
        assertEquals(loadedAt, QueueTiming.armedAfterRestore(emptyList(), loadedAt))
    }
}
