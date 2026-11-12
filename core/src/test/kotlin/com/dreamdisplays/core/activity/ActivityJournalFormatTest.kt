package com.dreamdisplays.core.activity

import com.dreamdisplays.core.protocol.common.packets.ActivityEntryWire
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneId
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityJournalFormatTest {
    private val display = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
    private val alice = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val utc = ZoneId.of("UTC")

    @Test
    fun `a player row has who, where, what and the old value`() {
        val row = ActivityEntryWire(
            atMs = 1_700_000_000_000, kind = ActivityKind.VIDEO_SET.wire, actorId = alice, actorName = "Alice",
            title = "Song \"✓\"", url = "https://youtu.be/x",
        )
        val line = ActivityJournalFormat.line(row, display, "lobby", ActivityJournalFormat.Place("world", 1, -2, 3), "https://old", zone = utc)
        assertTrue('\n' !in line)
        val obj = ActivityJournalFormat.parse(line)!!
        assertEquals("2023-11-14T22:13:20Z", obj["time"]!!.jsonPrimitive.content)
        assertEquals("video_set", obj["event"]!!.jsonPrimitive.content)
        assertEquals("player", obj["by"]!!.jsonPrimitive.content)
        assertEquals("Alice", obj["player"]!!.jsonPrimitive.content)
        assertEquals(alice.toString(), obj["uuid"]!!.jsonPrimitive.content)
        assertEquals("lobby", obj["displayName"]!!.jsonPrimitive.content)
        assertEquals("-2", obj["y"]!!.jsonPrimitive.content)
        assertEquals("Song \"✓\"", obj["title"]!!.jsonPrimitive.content)
        assertEquals("https://old", obj["old"]!!.jsonPrimitive.content)
        assertNull(obj["refused"])
        assertTrue(ActivityJournalFormat.matches(obj, display, null, null))
        assertTrue(ActivityJournalFormat.matches(obj, null, alice, null))
        assertTrue(ActivityJournalFormat.matches(obj, null, null, "alice"))
        assertFalse(ActivityJournalFormat.matches(obj, UUID.randomUUID(), null, "bob"))
        assertEquals(1_700_000_000_000L, ActivityJournalFormat.msOf(obj))
    }

    @Test
    fun `automatic, console and refused lines`() {
        val auto = ActivityJournalFormat.parse(
            ActivityJournalFormat.line(ActivityEntryWire(kind = ActivityKind.LOOPED.wire), display, null, null, zone = utc),
        )!!
        assertEquals("automatic", auto["by"]!!.jsonPrimitive.content)
        assertNull(auto["world"])
        val console = ActivityJournalFormat.parse(
            ActivityJournalFormat.line(ActivityEntryWire(kind = ActivityKind.MODE.wire, console = true, text = "synced"), display, null, null),
        )!!
        assertEquals("console", console["by"]!!.jsonPrimitive.content)
        val refused = ActivityJournalFormat.parse(
            ActivityJournalFormat.line(
                ActivityEntryWire(kind = ActivityKind.SEEK.wire, actorId = alice, actorName = "Alice", toMs = 5_000),
                display, null, null, refused = "not at the display", repeats = 4,
            ),
        )!!
        assertEquals("true", refused["refused"]!!.jsonPrimitive.content)
        assertEquals("not at the display", refused["reason"]!!.jsonPrimitive.content)
        assertEquals("4", refused["repeats"]!!.jsonPrimitive.content)
        assertEquals("kind_999", ActivityJournalFormat.eventName(999))
    }

    @Test
    fun `daily files and retention`() {
        val today = LocalDate.of(2026, 11, 12)
        assertEquals("activity-2026-11-12.log", ActivityJournalFormat.fileName(today))
        assertEquals(today, ActivityJournalFormat.dateOf("activity-2026-11-12.log"))
        assertNull(ActivityJournalFormat.dateOf("client-errors.log"))
        // 90 days kept: today and the 89 before it
        assertFalse(ActivityJournalFormat.expired("activity-2026-08-15.log", today, 90))
        assertTrue(ActivityJournalFormat.expired("activity-2026-08-14.log", today, 90))
        assertFalse(ActivityJournalFormat.expired("activity-2000-01-01.log", today, 0))
        assertFalse(ActivityJournalFormat.expired("notes.txt", today, 1))
        assertFalse(ActivityJournalFormat.expired("activity-2026-11-12.log", today, 1))
        assertTrue(ActivityJournalFormat.expired("activity-2026-11-11.log", today, 1))
    }
}
