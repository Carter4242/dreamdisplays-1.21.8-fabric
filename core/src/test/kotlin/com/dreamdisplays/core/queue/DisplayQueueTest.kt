package com.dreamdisplays.core.queue

import com.dreamdisplays.api.playback.model.Timeline
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisplayQueueTest {
    private val alice = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val bob = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val noCaps = QueueLimits(0, 0)

    private fun item(id: String, by: UUID = alice, url: String = "https://youtu.be/$id") =
        QueueItem(id, url, "", "Title $id", 60_000, "", by, "name", 0)

    @Test
    fun `entries play in order and advancing makes the head current`() {
        val q = DisplayQueue()
        q.add(item("a"), noCaps)
        q.add(item("b"), noCaps)
        assertEquals("a", q.head()?.itemId)
        assertEquals("a", q.advance()?.itemId)
        assertEquals("a", q.current?.itemId)
        assertEquals(listOf("b"), q.items.map { it.itemId })
        assertEquals("b", q.advance()?.itemId)
        assertNull(q.advance())
        assertEquals("b", q.current?.itemId, "an empty queue leaves the last entry playing")
    }

    @Test
    fun `total and per player caps`() {
        val q = DisplayQueue()
        val limits = QueueLimits(maxItems = 3, maxPerPlayer = 2)
        assertEquals(QueueAddResult.ADDED, q.add(item("a1"), limits))
        assertEquals(QueueAddResult.ADDED, q.add(item("a2"), limits))
        assertEquals(QueueAddResult.PLAYER_LIMIT, q.add(item("a3"), limits))
        assertEquals(QueueAddResult.ADDED, q.add(item("a3"), limits, exemptFromPlayerLimit = true))
        assertEquals(QueueAddResult.FULL, q.add(item("b1", bob), limits))
        assertEquals(QueueAddResult.DUPLICATE, q.add(item("a1"), noCaps))
        q.remove("a1")
        assertEquals(QueueAddResult.ADDED, q.add(item("b1", bob), limits))
        assertEquals(2, q.countBy(alice))
        assertEquals(1, q.countBy(bob))
    }

    @Test
    fun `remove move and clear`() {
        val q = DisplayQueue(listOf(item("a"), item("b"), item("c")))
        assertTrue(q.move("c", 0))
        assertEquals(listOf("c", "a", "b"), q.items.map { it.itemId })
        assertTrue(q.move("c", 99))
        assertEquals(listOf("a", "b", "c"), q.items.map { it.itemId })
        assertFalse(q.move("c", 2), "already there")
        assertFalse(q.move("zz", 0))
        assertEquals("b", q.remove("b")?.itemId)
        assertNull(q.remove("b"))
        val revision = q.revision
        assertEquals(2, q.clear())
        assertTrue(q.revision > revision)
        assertEquals(0, q.clear())
    }

    @Test
    fun `loop puts the finished entry back at the end`() {
        val q = DisplayQueue(listOf(item("a"), item("b")))
        q.setLoop(true)
        assertEquals("a", q.advance()?.itemId)
        assertEquals("b", q.advance()?.itemId)
        assertEquals(listOf("a"), q.items.map { it.itemId })
        assertEquals("a", q.advance()?.itemId)
        assertEquals(listOf("b"), q.items.map { it.itemId })

        val single = DisplayQueue(listOf(item("x")))
        single.setLoop(true)
        assertEquals("x", single.advance()?.itemId)
        assertEquals("x", single.advance()?.itemId, "a one-entry loop replays itself")
    }

    @Test
    fun `a refused entry is dropped, not looped back`() {
        val q = DisplayQueue(listOf(item("a"), item("b"), item("c")))
        q.setLoop(true)
        assertEquals("a", q.advance()?.itemId)
        // b is refused when its turn comes: it goes, and c is tried next
        val refused = q.advance()!!
        assertEquals("b", refused.itemId)
        assertTrue(q.discardCurrent(refused))
        assertEquals("c", q.advance()?.itemId)
        assertEquals(listOf("a"), q.items.map { it.itemId }, "b never comes round again")
        assertEquals("a", q.advance()?.itemId)
        assertEquals(listOf("c"), q.items.map { it.itemId })
        assertEquals(false, q.discardCurrent(refused), "only the entry just taken")
    }

    @Test
    fun `a hand-set video is no queue entry`() {
        val q = DisplayQueue(listOf(item("a"), item("b")))
        q.setLoop(true)
        q.advance()
        q.onVideoSetByHand("https://youtu.be/a")
        assertEquals("a", q.current?.itemId, "the same url keeps the entry")
        q.onVideoSetByHand("https://example.com/other.mp4")
        assertNull(q.current)
        assertEquals("b", q.advance()?.itemId)
        assertEquals(emptyList(), q.items.map { it.itemId }, "nothing to re-queue after a hand-set video")
    }

    @Test
    fun `untrusted display text is cleaned`() {
        assertEquals("Hello world", QueueText.cleanTitle("  Hello\n‮world\u0000 "))
        assertEquals("Red", QueueText.cleanTitle("§cRed"))
        assertEquals("a b", QueueText.cleanTitle("a \t  b"))
        assertEquals(QueueText.MAX_TITLE, QueueText.cleanTitle("x".repeat(1000)).length)
        assertEquals("", QueueText.cleanThumbnail("http://i.ytimg.com/a.jpg"))
        assertEquals("", QueueText.cleanThumbnail("https://a b"))
        assertEquals("", QueueText.cleanThumbnail("https://" + "a".repeat(600)))
        assertEquals("https://i.ytimg.com/vi/x/mqdefault.jpg", QueueText.cleanThumbnail("https://i.ytimg.com/vi/x/mqdefault.jpg"))
        assertEquals(QueueText.MAX_DURATION_MS, QueueText.cleanDuration(Long.MAX_VALUE))
        assertEquals(0L, QueueText.cleanDuration(-5))
    }

    @Test
    fun `advance is due at the end of a playing timeline only`() {
        val start = 1_000_000L
        val t = Timeline.start(start, durationMs = 60_000, loop = true)
        assertEquals(60_000L, QueueTiming.msUntilEnd(t, start))
        assertFalse(QueueTiming.advanceDue(t, start + 59_000, earlyMs = 250))
        assertTrue(QueueTiming.advanceDue(t, start + 59_800, earlyMs = 250))
        // The server ticked late and the loop already wrapped: still due, however late
        assertTrue(QueueTiming.advanceDue(t, start + 61_000, earlyMs = 250))
        assertTrue(QueueTiming.advanceDue(t, start + 65_000, earlyMs = 250))
        // Paused, or no duration reported yet: never
        assertFalse(QueueTiming.advanceDue(t.withPaused(true, start + 59_900), start + 59_900, 250))
        assertNull(QueueTiming.msUntilEnd(Timeline.start(start, loop = true), start + 10))
        assertFalse(QueueTiming.advanceDue(Timeline.start(start, loop = true), start + 99_999, 250))
        // A brand-new video in its first seconds is not "just past a wrap"
        assertFalse(QueueTiming.advanceDue(t, start + 500, earlyMs = 250))
    }

    @Test
    fun `adding to an empty queue while a video plays never advances it`() {
        val start = 1_000_000L
        // Known duration, mid-video
        val t = Timeline.start(start, durationMs = 60_000, loop = true)
        val addAt = start + 20_000
        assertFalse(QueueTiming.startsOnAdd("https://youtu.be/playing"))
        assertFalse(QueueTiming.advanceDue(t, addAt, 250, armedAtMs = addAt))
        // Unknown duration (not reported yet / live): never, however long it has played
        val unknown = Timeline.start(start, loop = true)
        assertFalse(QueueTiming.advanceDue(unknown, start + 3_600_000, 250, armedAtMs = start + 3_600_000))
        // Added just after the video looped: that wrap happened before the entry existed, so it waits for the next end
        val justWrapped = start + 60_000 * 5 + 800
        assertFalse(QueueTiming.advanceDue(t, justWrapped, 250, armedAtMs = justWrapped))
        assertFalse(QueueTiming.advanceDue(t, justWrapped + 1_000, 250, armedAtMs = justWrapped))
        // ...and does advance at the real end of the current play-through
        assertTrue(QueueTiming.advanceDue(t, start + 60_000 * 6 - 100, 250, armedAtMs = justWrapped))
        // A wrap the server ticked late after the add still counts
        assertTrue(QueueTiming.advanceDue(t, start + 60_000 * 6 + 500, 250, armedAtMs = justWrapped))
    }

    @Test
    fun `the end follows the playback speed`() {
        val start = 1_000_000L
        val half = Timeline.start(start, durationMs = 60_000, loop = true, rate = 0.5)
        // 40 s of wall time at half speed is 20 s of content: far from the end
        assertEquals(40_000L, QueueTiming.msUntilEnd(half, start + 40_000))
        assertFalse(QueueTiming.advanceDue(half, start + 40_000, 250, armedAtMs = start))
        assertTrue(QueueTiming.advanceDue(half, start + 119_800, 250, armedAtMs = start))
        // A start scheduled slightly ahead has not begun yet
        val scheduled = Timeline(0, start + 500, paused = false, durationMs = 60_000, loop = true)
        assertEquals(60_000L, QueueTiming.msUntilEnd(scheduled, start))
    }

    @Test
    fun `a seek into the last second still advances at the end`() {
        val armed = 0L
        // Seeked to 8:58.091 of an 8:59 video, released at 10 s; the server ticks once a second
        val t = Timeline(538_091, 10_000, paused = false, durationMs = 539_000, loop = true)
        assertFalse(QueueTiming.advanceDue(t, 10_000, 250, armed))
        assertFalse(QueueTiming.advanceDue(t, 10_500, 250, armed))
        // The end (10.909 s) fell between two ticks and the loop wrapped silently: due at the next tick
        assertTrue(QueueTiming.advanceDue(t, 11_500, 250, armed))
        // Seeked to the very end: due at once
        assertTrue(QueueTiming.advanceDue(t.copy(positionMs = 539_000), 10_000, 250, armed))
        // Still in the hold before the start (anchored ahead): not yet
        assertFalse(QueueTiming.advanceDue(t.copy(serverTimeMs = 12_000), 11_500, 250, armed))
    }

    @Test
    fun `an end between ticks is never missed`() {
        val t = Timeline.start(0, durationMs = 60_000, loop = true)
        for (tickMs in listOf(50L, 100L, 1_000L, 5_000L)) {
            val ticks = generateSequence(0L) { it + tickMs }.takeWhile { it <= 70_000 }
            val first = ticks.first { QueueTiming.advanceDue(t, it, 250) }
            assertTrue(first in 59_750..60_000 + tickMs, "tick $tickMs first due at $first")
        }
    }

    @Test
    fun `double speed ends in half the wall time`() {
        val t = Timeline.start(0, durationMs = 60_000, loop = true, rate = 2.0)
        assertFalse(QueueTiming.advanceDue(t, 29_500, 250))
        // 200 ms of content left is 100 ms of wall time
        assertTrue(QueueTiming.advanceDue(t, 29_900, 250))
        assertTrue(QueueTiming.advanceDue(t, 30_700, 250))
        // Switched to 2x mid-video (re-anchored at 20 s, 20 s in): 40 s left take 20 s
        val mid = Timeline.start(0, durationMs = 60_000, loop = true).withRate(2.0, 20_000)
        assertFalse(QueueTiming.advanceDue(mid, 39_000, 250))
        assertTrue(QueueTiming.advanceDue(mid, 40_500, 250))
    }

    @Test
    fun `an entry added mid-video advances at that video's end`() {
        val t = Timeline.start(0, durationMs = 60_000, loop = true)
        val addedAt = 30_000L
        assertFalse(QueueTiming.advanceDue(t, addedAt, 250, addedAt))
        assertTrue(QueueTiming.advanceDue(t, 59_900, 250, addedAt))
        assertTrue(QueueTiming.advanceDue(t, 61_000, 250, addedAt))
        // Added in the very last moment before the end: that end still counts
        assertTrue(QueueTiming.advanceDue(t, 60_400, 250, 59_999))
    }

    @Test
    fun `an unknown duration falls back to the entry's and a live stream never advances`() {
        val unknown = Timeline.start(0, loop = true)
        assertFalse(QueueTiming.advanceDue(unknown, 61_000, 250))
        assertTrue(QueueTiming.advanceDue(unknown, 61_000, 250, durationMs = 60_000))
        assertFalse(QueueTiming.advanceDue(unknown, 30_000, 250, durationMs = 60_000))
        assertFalse(QueueTiming.advanceDue(unknown, 10_000_000, 250, durationMs = 0))
    }

    @Test
    fun `with nothing to advance to the video loops`() {
        val q = DisplayQueue()
        assertFalse(q.hasNext(), "empty queue, loop off: the display just loops its video")
        q.add(item("a"), noCaps)
        assertTrue(q.hasNext())
        q.advance()
        assertFalse(q.hasNext(), "the last entry plays and loops")
        q.setLoop(true)
        assertFalse(q.hasNext(), "loop on with only the playing entry: looping it is the same thing")
        q.add(item("b"), noCaps)
        assertTrue(q.hasNext())
    }

    @Test
    fun `adding to an idle display starts the entry at once`() {
        // Documented behaviour: with nothing on the display there is nothing to wait for
        assertTrue(QueueTiming.startsOnAdd(""))
        assertTrue(QueueTiming.startsOnAdd("  "))
    }
}

class QueueRecordsTest {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val serializer = kotlinx.serialization.builtins.ListSerializer(QueueRecords.Display.serializer())
    private val display = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val adder = UUID.fromString("00000000-0000-0000-0000-00000000000a")

    private fun item(id: String) = QueueItem(id, "https://youtu.be/$id", "en", "T $id", 1_000, "", adder, "Alice", 42)

    @Test
    fun `queues survive a save and load`() {
        val q = DisplayQueue(listOf(item("a"), item("b")))
        q.setLoop(true)
        q.advance()
        val text = json.encodeToString(serializer, QueueRecords.toRecords(mapOf(display to q)))
        val loaded = QueueRecords.fromRecords(json.decodeFromString(serializer, text))[display]!!
        assertEquals(listOf("b"), loaded.items.map { it.itemId })
        assertEquals(item("b"), loaded.items.single())
        assertEquals("a", loaded.current?.itemId)
        assertTrue(loaded.loop)
    }

    @Test
    fun `empty queues are not stored and bad records are skipped`() {
        assertEquals(emptyList(), QueueRecords.toRecords(mapOf(display to DisplayQueue())))
        val records = listOf(
            QueueRecords.Display("not-a-uuid"),
            QueueRecords.Display(
                display.toString(),
                items = listOf(
                    QueueRecords.Item("x", "https://a", addedBy = "nope"),
                    QueueRecords.Item("", "https://a", addedBy = adder.toString()),
                    QueueRecords.Item("ok", "https://a", addedBy = adder.toString()),
                ),
            ),
        )
        val loaded = QueueRecords.fromRecords(records)
        assertEquals(setOf(display), loaded.keys)
        assertEquals(listOf("ok"), loaded.getValue(display).items.map { it.itemId })
    }
}
