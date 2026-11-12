package com.dreamdisplays.core.activity

import com.dreamdisplays.core.protocol.common.ZERO_UUID
import com.dreamdisplays.core.protocol.common.packets.ActivityEntryWire
import kotlinx.serialization.Serializable
import java.util.*
import kotlin.collections.ArrayDeque

/**
 * The last [capacity] Activity rows of every display, oldest dropped first. A seek (or speed change) by the same
 * player within [COALESCE_MS] of their previous one, with nothing else in between, updates that row instead of adding
 * one: a drag on the seek bar is one row from where it started to where it ended. Thread-safe.
 */
class ActivityLog(private val capacity: () -> Int) {
    private class Ring(var nextSeq: Long = 1L, val entries: ArrayDeque<ActivityEntryWire> = ArrayDeque())

    private val rings = HashMap<UUID, Ring>()

    /**
     * Adds [entry] (its seq and time are set here, its strings capped) to [displayId]'s rows at [nowMs]. Returns the
     * row as it now stands (new, or the earlier row it was merged into), or null when the log is off (capacity 0).
     */
    @Synchronized
    fun record(displayId: UUID, entry: ActivityEntryWire, nowMs: Long): ActivityEntryWire? {
        val cap = capacity()
        if (cap <= 0) return null
        val clean = ActivityLimits.clean(entry).copy(atMs = nowMs)
        val ring = rings.getOrPut(displayId) { Ring() }
        val last = ring.entries.lastOrNull()
        if (last != null && coalesces(last, clean)) {
            val merged = last.copy(atMs = nowMs, toMs = clean.toMs, rate = clean.rate)
            ring.entries[ring.entries.size - 1] = merged
            return merged
        }
        val added = clean.copy(seq = ring.nextSeq++)
        ring.entries.addLast(added)
        while (ring.entries.size > cap) ring.entries.removeFirst()
        return added
    }

    /** [displayId]'s rows, newest first (at most the current capacity). */
    @Synchronized
    fun entries(displayId: UUID): List<ActivityEntryWire> {
        val ring = rings[displayId] ?: return emptyList()
        return ring.entries.asReversed().take(capacity().coerceAtLeast(0))
    }

    /** Drops [displayId]'s rows (the display was deleted). True when it had any. */
    @Synchronized
    fun forget(displayId: UUID): Boolean = rings.remove(displayId) != null

    /** Drops every display's rows whose display is not in [keep]. */
    @Synchronized
    fun retainOnly(keep: Set<UUID>) {
        rings.keys.retainAll(keep)
    }

    @Synchronized
    fun clear() = rings.clear()

    /** Every display's rows, to persist ([load] reads them back). */
    @Synchronized
    fun toRecords(): List<ActivityRecords.Display> = rings.map { (id, ring) ->
        ActivityRecords.Display(id.toString(), ring.nextSeq, ring.entries.map(ActivityRecords::of))
    }

    /** Replaces everything with [records]; unreadable ones are skipped, each display's rows capped. */
    @Synchronized
    fun load(records: List<ActivityRecords.Display>) {
        rings.clear()
        val cap = capacity()
        if (cap <= 0) return
        for (record in records) {
            val id = runCatching { UUID.fromString(record.displayId) }.getOrNull() ?: continue
            val entries = record.entries.map(ActivityRecords::toWire).map(ActivityLimits::clean).takeLast(cap)
            if (entries.isEmpty()) continue
            val nextSeq = maxOf(record.nextSeq, (entries.maxOfOrNull { it.seq } ?: 0L) + 1L)
            rings[id] = Ring(nextSeq, ArrayDeque(entries))
        }
    }

    companion object {
        /** A seek or speed change this soon after the same player's previous one updates that row. */
        const val COALESCE_MS = 2_500L

        private val MERGED = setOf(ActivityKind.SEEK.wire, ActivityKind.SPEED.wire)

        internal fun coalesces(last: ActivityEntryWire, next: ActivityEntryWire): Boolean =
            next.kind in MERGED && last.kind == next.kind && next.actorId != ZERO_UUID && last.actorId == next.actorId &&
                last.console == next.console && next.atMs - last.atMs in 0..COALESCE_MS
    }
}

/** Length caps for Activity rows: the list goes to clients whole, so every string is bounded. */
object ActivityLimits {
    const val MAX_TITLE = 120
    const val MAX_URL = 300
    const val MAX_NAME = 32
    const val MAX_TEXT = 64

    fun clean(e: ActivityEntryWire): ActivityEntryWire = e.copy(
        actorName = text(e.actorName, MAX_NAME),
        title = text(e.title, MAX_TITLE),
        url = text(e.url, MAX_URL),
        text = text(e.text, MAX_TEXT),
        text2 = text(e.text2, MAX_TEXT),
        rate = if (e.rate.isFinite()) e.rate else 0.0,
    )

    private fun text(s: String, max: Int): String {
        if (s.isEmpty()) return s
        val cleaned = s.filter { !it.isISOControl() }.trim()
        return if (cleaned.length <= max) cleaned else cleaned.take(max - 1) + "…"
    }
}

/** JSON form of the Activity rows (`display-activity.json`). */
object ActivityRecords {
    @Serializable
    data class Display(val displayId: String, val nextSeq: Long = 1L, val entries: List<Entry> = emptyList())

    @Serializable
    data class Entry(
        val seq: Long = 0,
        val atMs: Long = 0,
        val kind: Int = 0,
        val actorId: String = "",
        val actorName: String = "",
        val title: String = "",
        val url: String = "",
        val fromMs: Long = 0,
        val toMs: Long = 0,
        val rate: Double = 0.0,
        val count: Int = 0,
        val flag: Boolean = false,
        val text: String = "",
        val text2: String = "",
        val console: Boolean = false,
    )

    fun of(e: ActivityEntryWire): Entry = Entry(
        e.seq, e.atMs, e.kind, if (e.actorId == ZERO_UUID) "" else e.actorId.toString(), e.actorName, e.title, e.url,
        e.fromMs, e.toMs, e.rate, e.count, e.flag, e.text, e.text2, e.console,
    )

    fun toWire(e: Entry): ActivityEntryWire = ActivityEntryWire(
        e.seq, e.atMs, e.kind, runCatching { UUID.fromString(e.actorId) }.getOrDefault(ZERO_UUID), e.actorName, e.title,
        e.url, e.fromMs, e.toMs, e.rate, e.count, e.flag, e.text, e.text2, e.console,
    )
}
