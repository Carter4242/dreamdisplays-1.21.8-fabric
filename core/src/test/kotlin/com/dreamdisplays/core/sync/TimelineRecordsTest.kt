package com.dreamdisplays.core.sync

import com.dreamdisplays.api.playback.model.Timeline
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A plugin reload or restart brings every display back where it was, paused ones paused. */
class TimelineRecordsTest {
    private val id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
    private val url = "https://youtu.be/abc"
    private val json = Json { ignoreUnknownKeys = true }

    private fun roundTrip(saved: TimelineRecords.Saved): TimelineRecords.Saved =
        json.decodeFromString(
            TimelineRecords.Saved.serializer(),
            json.encodeToString(TimelineRecords.Saved.serializer(), saved),
        )

    @Test
    fun `a playing timeline counts the time spent unloaded, at its speed`() {
        val playing = Timeline(60_000, 1_000_000, paused = false, durationMs = 600_000, loop = true, rate = 1.5)
        val saved = roundTrip(TimelineRecords.save(id, url, playing, holding = false, mediaMs = 599_850, nowMs = 1_010_000))
        assertEquals(599_850L, saved.mediaMs)
        val restored = TimelineRecords.restore(saved, nowMs = 1_030_000)
        assertFalse(restored.paused)
        assertEquals(1.5, restored.rate)
        // 30 s since the anchor at 1.5x: 45 s on
        assertEquals(105_000L, restored.positionAt(1_030_000))
        assertEquals(playing.positionAt(1_030_000), restored.positionAt(1_030_000))
    }

    @Test
    fun `a paused timeline stays paused where it was`() {
        val paused = Timeline(42_000, 1_000_000, paused = true, durationMs = 600_000, loop = true)
        val restored = TimelineRecords.restore(
            roundTrip(TimelineRecords.save(id, url, paused, holding = false, mediaMs = 0, nowMs = 1_005_000)),
            nowMs = 9_000_000,
        )
        assertTrue(restored.paused)
        assertEquals(42_000L, restored.positionAt(9_000_000))
    }

    @Test
    fun `a start hold comes back playing from its held position`() {
        val held = Timeline(0, 1_000_000, paused = true, loop = true)
        val saved = TimelineRecords.save(id, url, held, holding = true, mediaMs = 0, nowMs = 1_002_000)
        val restored = TimelineRecords.restore(saved, nowMs = 1_007_000)
        assertFalse(restored.paused)
        assertEquals(5_000L, restored.positionAt(1_007_000))
    }

    @Test
    fun `a looping video wraps over the gap, one with no known duration resumes after a long one`() {
        val looping = Timeline(0, 0, paused = false, durationMs = 100_000, loop = true)
        assertEquals(50_000L, TimelineRecords.restore(TimelineRecords.save(id, url, looping, false, 0, 10_000), 250_000).positionAt(250_000))

        val unknown = Timeline(0, 0, paused = false, loop = true)
        val saved = TimelineRecords.save(id, url, unknown, holding = false, mediaMs = 0, nowMs = 30_000)
        // A short gap (a hot swap) counts
        assertEquals(90_000L, TimelineRecords.restore(saved, 90_000).positionAt(90_000))
        // A long one resumes at 30 s
        val later = 30_000 + TimelineRecords.MAX_UNKNOWN_DURATION_GAP_MS + 1
        assertEquals(30_000L, TimelineRecords.restore(saved, later).positionAt(later))
    }
}
