package com.dreamdisplays.platform.client.render

import java.io.File

/**
 * Startup cleanup of the thumbnail disk cache: an entry is otherwise only deleted when the same thumbnail is asked for
 * again after it expired, so every thumbnail ever shown stayed on disk.
 */
internal object ThumbnailCacheSweep {
    /** A cache file as the sweep sees it. */
    data class Entry(val name: String, val modifiedMs: Long, val bytes: Long)

    private const val STRAY_TMP_MS = 60L * 60L * 1_000L

    /**
     * The files to delete: expired ones (older than [ttlMs]), write leftovers (`.tmp`) older than an hour, and then the
     * oldest until the rest fit in [maxBytes].
     */
    fun evictions(entries: List<Entry>, nowMs: Long, ttlMs: Long, maxBytes: Long): List<String> {
        val doomed = LinkedHashSet<String>()
        for (e in entries) {
            val age = nowMs - e.modifiedMs
            if (e.name.endsWith(".tmp")) {
                if (age > STRAY_TMP_MS) doomed += e.name
            } else if (age > ttlMs) {
                doomed += e.name
            }
        }
        var kept = entries.filter { it.name !in doomed && !it.name.endsWith(".tmp") }.sumOf { it.bytes }
        for (e in entries.filter { it.name !in doomed && !it.name.endsWith(".tmp") }.sortedBy { it.modifiedMs }) {
            if (kept <= maxBytes) break
            doomed += e.name
            kept -= e.bytes
        }
        return doomed.toList()
    }

    /** Applies [evictions] to [dir]; returns how many files went. Never throws. */
    fun sweep(dir: File, nowMs: Long, ttlMs: Long, maxBytes: Long): Int = runCatching {
        val files = dir.listFiles()?.filter { it.isFile } ?: return 0
        val entries = files.map { Entry(it.name, it.lastModified(), it.length()) }
        evictions(entries, nowMs, ttlMs, maxBytes).count { File(dir, it).delete() }
    }.getOrDefault(0)
}
