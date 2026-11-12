package com.dreamdisplays.core.activity

import com.dreamdisplays.core.protocol.common.ZERO_UUID
import com.dreamdisplays.core.protocol.common.packets.ActivityEntryWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityLogTest {
    private val display = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
    private val other = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val alice = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val bob = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002")

    private fun entry(kind: ActivityKind, actor: UUID = alice, from: Long = 0, to: Long = 0) =
        ActivityEntryWire(kind = kind.wire, actorId = actor, actorName = "p", fromMs = from, toMs = to)

    @Test
    fun `keeps the newest rows up to its capacity, newest first`() {
        var cap = 3
        val log = ActivityLog { cap }
        repeat(5) { log.record(display, entry(ActivityKind.PLAY), 1_000L * it) }
        val rows = log.entries(display)
        assertEquals([5L, 4L, 3L], rows.map { it.seq })
        assertEquals([4_000L, 3_000L, 2_000L], rows.map { it.atMs })
        // Another display has its own ring and numbering
        log.record(other, entry(ActivityKind.PAUSE), 0)
        assertEquals([1L], log.entries(other).map { it.seq })
        // A lower capacity shows fewer at once; 0 turns recording off
        cap = 2
        assertEquals(2, log.entries(display).size)
        cap = 0
        assertNull(log.record(display, entry(ActivityKind.PLAY), 9_000))
        assertTrue(log.entries(display).isEmpty())
    }

    @Test
    fun `forget drops a deleted display`() {
        val log = ActivityLog { 100 }
        log.record(display, entry(ActivityKind.PLAY), 0)
        log.record(other, entry(ActivityKind.PLAY), 0)
        assertTrue(log.forget(display))
        assertTrue(log.entries(display).isEmpty())
        log.retainOnly(setOf())
        assertTrue(log.entries(other).isEmpty())
    }

    @Test
    fun `a drag of seeks is one row from where it started to where it ended`() {
        val log = ActivityLog { 100 }
        log.record(display, entry(ActivityKind.SEEK, from = 206_000, to = 300_000), 10_000)
        log.record(display, entry(ActivityKind.SEEK, from = 300_000, to = 900_000), 10_400)
        val last = log.record(display, entry(ActivityKind.SEEK, from = 900_000, to = 3_730_000), 11_000)!!
        val rows = log.entries(display)
        assertEquals(1, rows.size)
        assertEquals(1L, last.seq)
        assertEquals(206_000L, rows[0].fromMs)
        assertEquals(3_730_000L, rows[0].toMs)
        assertEquals(11_000L, rows[0].atMs)
        // Later than the window, another player, or something else in between: a row of its own
        log.record(display, entry(ActivityKind.SEEK, from = 0, to = 1), 11_000 + ActivityLog.COALESCE_MS + 1)
        log.record(display, entry(ActivityKind.SEEK, actor = bob, from = 1, to = 2), 13_600)
        log.record(display, entry(ActivityKind.PAUSE, actor = bob), 13_700)
        log.record(display, entry(ActivityKind.SEEK, actor = bob, from = 2, to = 3), 13_800)
        assertEquals(5, log.entries(display).size)
        // Automatic rows never merge
        val auto = ActivityEntryWire(kind = ActivityKind.SEEK.wire, actorId = ZERO_UUID)
        log.record(display, auto, 14_000)
        log.record(display, auto, 14_100)
        assertEquals(7, log.entries(display).size)
    }

    @Test
    fun `speed changes in a row by one player merge to the last speed`() {
        val log = ActivityLog { 100 }
        log.record(display, entry(ActivityKind.SPEED).copy(rate = 1.25), 0)
        log.record(display, entry(ActivityKind.SPEED).copy(rate = 1.5), 500)
        assertEquals([1.5], log.entries(display).map { it.rate })
    }

    @Test
    fun `strings are capped and cleaned`() {
        val log = ActivityLog { 100 }
        val row = log.record(
            display,
            entry(ActivityKind.VIDEO_SET).copy(title = "t\u0007".repeat(500), url = "u".repeat(5_000), actorName = "n".repeat(99)),
            0,
        )!!
        assertEquals(ActivityLimits.MAX_TITLE, row.title.length)
        assertTrue(row.title.endsWith("…") && '\u0007' !in row.title)
        assertEquals(ActivityLimits.MAX_URL, row.url.length)
        assertEquals(ActivityLimits.MAX_NAME, row.actorName.length)
    }

    @Test
    fun `persistence round trip keeps rows and numbering`() {
        val log = ActivityLog { 100 }
        log.record(display, entry(ActivityKind.SEEK, from = 1, to = 2), 5)
        log.record(display, entry(ActivityKind.VIDEO_SET).copy(title = "Song ✓", url = "https://youtu.be/x", console = true), 6)
        log.record(display, ActivityEntryWire(kind = ActivityKind.LOOPED.wire), 7)
        val json = Json.encodeToString(ListSerializer(ActivityRecords.Display.serializer()), log.toRecords())
        val restored = ActivityLog { 100 }
        restored.load(Json.decodeFromString(ListSerializer(ActivityRecords.Display.serializer()), json))
        assertEquals(log.entries(display), restored.entries(display))
        // Numbering carries on after the restored rows
        assertEquals(4L, restored.record(display, entry(ActivityKind.PLAY), 8)!!.seq)
        // A smaller capacity at load keeps the newest; junk ids are skipped
        val small = ActivityLog { 2 }
        small.load(log.toRecords() + ActivityRecords.Display("not-a-uuid", 1, listOf(ActivityRecords.Entry(kind = 1))))
        assertEquals([3L, 2L], small.entries(display).map { it.seq })
    }
}
