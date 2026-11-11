package com.dreamdisplays.core.diagnostics

import java.util.*

/**
 * Client-side gate for [com.dreamdisplays.core.protocol.common.packets.ClientErrorReport]s: an identical report
 * (same display and [signature]) is sent once per [dedupeWindowMs] and counted while held back; on top of that at
 * most [perDisplayPerMinute] reports per display and [totalPerMinute] overall leave per minute. Not thread-safe;
 * callers serialize.
 */
class ReportThrottle(
    private val dedupeWindowMs: Long = 10 * 60_000L,
    private val perDisplayPerMinute: Int = 4,
    private val totalPerMinute: Int = 12,
    private val maxTracked: Int = 512,
) {
    private class Seen(var lastSentMs: Long, var suppressed: Int)

    private val seen = LinkedHashMap<String, Seen>(64, 0.75f, true)
    private val sentPerDisplay = HashMap<UUID, ArrayDeque<Long>>()
    private val sentTotal = ArrayDeque<Long>()

    /**
     * Decides whether a report for [displayId] with [signature] may go out at [nowMs]. Returns the number of identical
     * reports held back since this one was last sent (to put in the report), or null when it must be dropped.
     */
    fun admit(displayId: UUID, signature: String, nowMs: Long): Int? {
        val key = "$displayId|$signature"
        val entry = seen[key]
        if (entry != null && nowMs - entry.lastSentMs < dedupeWindowMs) {
            entry.suppressed++
            return null
        }
        val perDisplay = sentPerDisplay.getOrPut(displayId) { ArrayDeque() }
        prune(perDisplay, nowMs)
        prune(sentTotal, nowMs)
        if (perDisplay.size >= perDisplayPerMinute || sentTotal.size >= totalPerMinute) {
            // Rate-limited, not a duplicate: count it so the next one that gets through says so
            if (entry != null) entry.suppressed++
            return null
        }
        perDisplay.addLast(nowMs)
        sentTotal.addLast(nowMs)
        val suppressed = entry?.suppressed ?: 0
        seen[key] = Seen(nowMs, 0)
        while (seen.size > maxTracked) seen.remove(seen.keys.first())
        if (sentPerDisplay.size > maxTracked) sentPerDisplay.entries.removeIf { it.value.isEmpty() }
        return suppressed
    }

    fun reset() {
        seen.clear()
        sentPerDisplay.clear()
        sentTotal.clear()
    }

    private fun prune(times: ArrayDeque<Long>, nowMs: Long) {
        while (times.isNotEmpty() && nowMs - times.first() >= 60_000L) times.removeFirst()
    }

    companion object {
        private val LONG_NUMBER = Regex("""\d{3,}""")

        /**
         * Dedupe key for a report: stage, kind, the message with long numbers masked (timestamps, byte counts),
         * the retry number and whether it was final. A retry sequence (try 1, 2, final) reports each step; the same
         * sequence again within the window does not.
         */
        fun signature(type: String, stage: String, kind: String, message: String, retry: Int, final: Boolean): String =
            "$type|$stage|$kind|${LONG_NUMBER.replace(message.take(200), "#")}|$retry|$final"
    }
}
