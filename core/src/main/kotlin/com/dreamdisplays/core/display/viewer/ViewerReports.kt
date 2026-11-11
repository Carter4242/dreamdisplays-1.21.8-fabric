package com.dreamdisplays.core.display.viewer

import com.dreamdisplays.core.protocol.common.packets.ViewerReport
import com.dreamdisplays.core.protocol.common.packets.ViewerState
import java.util.*
import kotlin.math.abs

/** One viewer's latest [ViewerReport] on one display, as the server keeps it. */
data class ViewerEntry(
    val playerId: UUID,
    val playerName: String,
    val report: ViewerReport,
    val receivedAtMs: Long,
) {
    val state: ViewerState get() = ViewerState.fromWire(report.state)

    /** Shows an error right now. */
    val erroring: Boolean get() = state == ViewerState.ERROR || report.errorKind.isNotEmpty()

    /** Its drift numbers are meaningful (follows the server timeline and is not failing). */
    val inSync: Boolean get() = report.syncing && !erroring

    fun ageMs(nowMs: Long): Long = (nowMs - receivedAtMs).coerceAtLeast(0L)
}

/** Totals over every viewer of a display. Drift figures cover only viewers that follow the server timeline. */
data class ViewerSummary(
    val viewers: Int,
    val syncing: Int,
    /** Largest |drift| among syncing viewers, or null when none sync. */
    val maxAbsDriftMs: Int?,
    /** Furthest-ahead to furthest-behind syncing viewer, or null with fewer than two. */
    val spreadMs: Int?,
    val erroring: Int,
    val buffering: Int,
    val stalls: Int,
    val seeks: Int,
) {
    companion object {
        fun of(entries: Collection<ViewerEntry>): ViewerSummary {
            val drifts = entries.filter { it.inSync }.map { it.report.driftMs }
            return ViewerSummary(
                viewers = entries.size,
                syncing = drifts.size,
                maxAbsDriftMs = drifts.maxOfOrNull { abs(it) },
                spreadMs = if (drifts.size >= 2) drifts.max() - drifts.min() else null,
                erroring = entries.count { it.erroring },
                buffering = entries.count { it.report.buffering },
                stalls = entries.sumOf { it.report.stalls },
                seeks = entries.sumOf { it.report.seeks },
            )
        }
    }
}

/**
 * Latest report per (display, viewer), in memory only.
 *
 * Rate limited per viewer and display: a report arriving within [minIntervalMs] of the last accepted one is
 * dropped, and one viewer can have at most [maxDisplaysPerPlayer] displays tracked at once, so a misbehaving
 * client cannot grow this without bound. Reports are cleaned ([sanitized]) before they are kept.
 */
class ViewerReportStore(
    private val minIntervalMs: Long = 1_000L,
    private val maxDisplaysPerPlayer: Int = 64,
) {
    private val byDisplay = HashMap<UUID, HashMap<UUID, ViewerEntry>>()
    private val byPlayer = HashMap<UUID, HashMap<UUID, Long>>()

    /** Keeps [report] from [playerId] unless rate limited; returns whether it was kept. */
    @Synchronized
    fun offer(playerId: UUID, playerName: String, report: ViewerReport, nowMs: Long): Boolean {
        val accepted = byPlayer.getOrPut(playerId) { HashMap() }
        val last = accepted[report.id]
        if (last != null && nowMs - last < minIntervalMs) return false
        if (last == null && accepted.size >= maxDisplaysPerPlayer) return false
        accepted[report.id] = nowMs
        byDisplay.getOrPut(report.id) { HashMap() }[playerId] =
            ViewerEntry(playerId, playerName, sanitized(report), nowMs)
        return true
    }

    /** Fresh reports on [displayId] (received within [maxAgeMs]), nearest-to-server first. */
    @Synchronized
    fun viewers(displayId: UUID, nowMs: Long, maxAgeMs: Long): List<ViewerEntry> =
        byDisplay[displayId].orEmpty().values
            .filter { it.ageMs(nowMs) <= maxAgeMs }
            .sortedWith(compareBy({ !it.erroring }, { -abs(it.report.driftMs) }, { it.playerName }))

    /** Displays that have at least one report. */
    @Synchronized
    fun displays(): Set<UUID> = byDisplay.keys.toSet()

    @Synchronized
    fun forgetPlayer(playerId: UUID) {
        val displays = byPlayer.remove(playerId) ?: return
        displays.keys.forEach { id -> removeEntry(id, playerId) }
    }

    @Synchronized
    fun forgetDisplay(displayId: UUID) {
        val viewers = byDisplay.remove(displayId) ?: return
        viewers.keys.forEach { byPlayer[it]?.remove(displayId) }
    }

    /** Drops reports older than [maxAgeMs] (the viewer stopped reporting: display unloaded or out of range). */
    @Synchronized
    fun prune(nowMs: Long, maxAgeMs: Long) {
        val stale = byDisplay.flatMap { (displayId, viewers) ->
            viewers.values.filter { it.ageMs(nowMs) > maxAgeMs }.map { displayId to it.playerId }
        }
        stale.forEach { (displayId, playerId) ->
            removeEntry(displayId, playerId)
            byPlayer[playerId]?.let { it.remove(displayId); if (it.isEmpty()) byPlayer.remove(playerId) }
        }
    }

    private fun removeEntry(displayId: UUID, playerId: UUID) {
        val viewers = byDisplay[displayId] ?: return
        viewers.remove(playerId)
        if (viewers.isEmpty()) byDisplay.remove(displayId)
    }

    companion object {
        private const val MAX_KIND = 24
        private const val MAX_MESSAGE = 160
        private const val MAX_DRIFT_MS = 86_400_000
        private val colorCodes = Regex("[&§][0-9a-fk-orx#]", RegexOption.IGNORE_CASE)

        /** [report] with its numbers clamped and its text shortened and stripped of control and color codes. */
        fun sanitized(report: ViewerReport): ViewerReport = report.copy(
            driftMs = report.driftMs.coerceIn(-MAX_DRIFT_MS, MAX_DRIFT_MS),
            rate = report.rate.takeIf { it.isFinite() }?.coerceIn(0.0, 10.0) ?: 1.0,
            latencyCompMs = report.latencyCompMs.coerceIn(0, 60_000),
            action = report.action.coerceIn(0, 3),
            seeks = report.seeks.coerceIn(0, 10_000),
            stalls = report.stalls.coerceIn(0, 10_000),
            positionMs = report.positionMs.coerceAtLeast(0L),
            qualityHeight = report.qualityHeight.coerceIn(0, 16_384),
            errorKind = clean(report.errorKind, MAX_KIND),
            errorMessage = clean(report.errorMessage, MAX_MESSAGE),
        )

        private fun clean(text: String, max: Int): String =
            colorCodes.replace(text.filter { !it.isISOControl() }, "").take(max)
    }
}
