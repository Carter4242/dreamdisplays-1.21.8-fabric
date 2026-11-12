package com.dreamdisplays.core.diagnostics

import java.util.*

/**
 * How much of the client error log each player may fill: [maxBytes] per [windowMs] window (starting with their first
 * line in it). Past that their lines are dropped and counted, and the count comes back once, as a [Suppressed], when
 * the window is over (their next line after it, or [forget] when they leave), for one "suppressed N reports" line.
 * The report rate limit alone lets one modified client write megabytes in minutes and rotate everyone else's lines
 * (the evening's display commands included) out of the file; this keeps that to its own share. Thread-safe.
 */
class ReportByteBudget(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxPlayers: Int = 4096,
) {
    /** Lines of one player dropped in the window [sinceMs] .. [untilMs]. */
    data class Suppressed(val reports: Int, val bytes: Long, val sinceMs: Long, val untilMs: Long)

    /** Whether the line may be written, and the previous window's [Suppressed] to log first when one just ended. */
    data class Decision(val allowed: Boolean, val ended: Suppressed?)

    private class Window(val startMs: Long) {
        var usedBytes = 0L
        var droppedReports = 0
        var droppedBytes = 0L

        fun suppressed(windowMs: Long): Suppressed? =
            if (droppedReports == 0) null else Suppressed(droppedReports, droppedBytes, startMs, startMs + windowMs)
    }

    private val windows = HashMap<UUID, Window>()

    /** Counts a line of [bytes] from [playerId] at [nowMs] against their budget. */
    @Synchronized
    fun admit(playerId: UUID, bytes: Int, nowMs: Long): Decision {
        var window = windows[playerId]
        var ended: Suppressed? = null
        if (window == null || nowMs - window.startMs >= windowMs || nowMs < window.startMs) {
            ended = window?.suppressed(windowMs)
            if (window == null && windows.size >= maxPlayers) prune(nowMs)
            window = Window(nowMs).also { windows[playerId] = it }
        }
        if (window.usedBytes + bytes > maxBytes) {
            window.droppedReports++
            window.droppedBytes += bytes
            return Decision(false, ended)
        }
        window.usedBytes += bytes
        return Decision(true, ended)
    }

    /** Forgets [playerId] (they left); returns what was suppressed in their current window, if anything. */
    @Synchronized
    fun forget(playerId: UUID): Suppressed? = windows.remove(playerId)?.suppressed(windowMs)

    private fun prune(nowMs: Long) {
        // Ended windows with nothing dropped are the same as none; with still too many, start afresh rather than grow
        windows.values.removeIf { nowMs - it.startMs >= windowMs && it.droppedReports == 0 }
        if (windows.size >= maxPlayers) windows.clear()
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 64L * 1024
        const val DEFAULT_WINDOW_MS: Long = 10 * 60_000L

        /** [s]'s length in UTF-8, without encoding it (a lone surrogate counts 3 where the encoder writes 1). */
        fun utf8Length(s: String): Int {
            var n = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                n += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
                    else -> 3
                }
                i++
            }
            return n
        }
    }
}
