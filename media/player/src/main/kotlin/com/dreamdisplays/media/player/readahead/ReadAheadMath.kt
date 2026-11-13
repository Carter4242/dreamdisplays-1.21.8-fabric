package com.dreamdisplays.media.player.readahead

/** The pure arithmetic of the read-ahead buffer, kept apart so it can be tested without sockets. */
internal object ReadAheadMath {
    /**
     * Parses a single-range `Range` header against a file of [total] bytes into an inclusive byte
     * range, or null when it cannot be satisfied (multi-range, malformed, or starting past the end).
     * No header means the whole file.
     */
    fun parseRange(header: String?, total: Long): LongRange? {
        if (total <= 0L) return null
        if (header == null) return 0L..<total
        if (!header.startsWith("bytes=", ignoreCase = true)) return null
        val spec = header.substring(6).trim()
        if (spec.contains(',')) return null
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val fromText = spec.substring(0, dash).trim()
        val toText = spec.substring(dash + 1).trim()
        if (fromText.isEmpty()) {
            // Suffix form: the last N bytes
            val suffix = toText.toLongOrNull()?.takeIf { it > 0L } ?: return null
            return maxOf(0L, total - suffix)..<total
        }
        val from = fromText.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        if (from >= total) return null
        val to = if (toText.isEmpty()) total - 1 else (toText.toLongOrNull() ?: return null)
        if (to < from) return null
        return from..minOf(to, total - 1)
    }

    /**
     * How many bytes to keep downloaded ahead of playback: [seconds] at the file's average bitrate
     * (its size over its duration), within [minBytes]..[maxBytes]. With no duration known the
     * bitrate is assumed to be [fallbackBytesPerSecond].
     */
    fun windowBytes(
        seconds: Int, contentLength: Long, durationNanos: Long?,
        minBytes: Long, maxBytes: Long, fallbackBytesPerSecond: Long,
    ): Long {
        val bytesPerSecond = if (durationNanos != null && durationNanos > 0L && contentLength > 0L) {
            (contentLength.toDouble() / (durationNanos / 1e9)).toLong().coerceAtLeast(1L)
        } else {
            fallbackBytesPerSecond
        }
        return (seconds.toLong() * bytesPerSecond).coerceIn(minBytes, maxBytes)
    }

    /** Index of the block holding byte [pos]. */
    fun blockOf(pos: Long, blockSize: Int): Long = pos / blockSize

    /** Size of block [index] in a file of [total] bytes (the last one is usually short). */
    fun blockLength(index: Long, blockSize: Int, total: Long): Int =
        minOf(blockSize.toLong(), total - index * blockSize).coerceAtLeast(0L).toInt()

    /**
     * Order in which cached blocks are dropped when the buffer is full, best candidate first: blocks
     * playback has already passed (beyond the [keepBehind] blocks kept for small backward seeks),
     * oldest first; then blocks far ahead past the window end, farthest first; then the kept-behind
     * blocks; last the [pinned] ones (the file's header, which every reopen reads again). Blocks inside
     * the window ahead of [anchor] are never offered.
     */
    fun evictionOrder(
        cached: Collection<Long>, anchor: Long, keepBehind: Long, windowEnd: Long,
        pinned: (Long) -> Boolean = { false },
    ): List<Long> {
        val free = cached.filterNot(pinned)
        val behind = free.filter { it < anchor - keepBehind }.sorted()
        val beyond = free.filter { it > windowEnd }.sortedDescending()
        val kept = free.filter { it >= anchor - keepBehind && it < anchor }.sorted()
        val lastResort = cached.filter { pinned(it) && (it < anchor || it > windowEnd) }.sortedDescending()
        return behind + beyond + kept + lastResort
    }

    /**
     * Whether block [index] of a file of [total] bytes lies in its first or last [pinBytes]: where an MP4 keeps its
     * index (`moov`, megabytes for a feature film), which every decoder that opens the file reads before seeking.
     */
    fun inHeader(index: Long, blockSize: Int, total: Long, pinBytes: Long): Boolean {
        if (pinBytes <= 0L) return false
        val start = index * blockSize
        return start < pinBytes || start + blockSize > total - pinBytes
    }
}
