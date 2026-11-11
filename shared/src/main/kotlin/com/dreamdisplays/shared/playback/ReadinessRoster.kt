package com.dreamdisplays.shared.playback

import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * What a client says about one display (`DisplayReadiness` packet).
 *
 * Travels on the wire as its [ordinal] int; ordinals are append-only.
 */
enum class ReadinessState {
    /** Loaded but not held ready (playing, loading, re-opening, or failed). */
    NOT_READY,

    /** Paused on a decoded frame with its stream and sound open: a resume at the reported position plays at once. */
    READY,

    /** Not loaded or not played on this client (out of range, over its display budget, displays off): never waited for. */
    ABSENT;

    /** The append-only wire value for this state. */
    val wire: Int get() = ordinal

    companion object {
        private val byWire = entries.associateBy { it.ordinal }

        /** The state for [wire], or [NOT_READY] for unknown values. */
        fun fromWire(wire: Int): ReadinessState = byWire[wire] ?: NOT_READY
    }
}

/**
 * Which clients hold which display ready to play at once, and where. Fed by client reports; read by anything that
 * wants to start a display without a fixed hold: a start can go the moment [waitingFor] comes back empty, and
 * [leadMs] says how far ahead to schedule it so every viewer begins on the same frame.
 *
 * Platform-neutral and thread-safe.
 */
class ReadinessRoster(val toleranceMs: Long = DEFAULT_TOLERANCE_MS) {
    /** One client's last report on one display. */
    data class Report(val state: ReadinessState, val positionMs: Long, val latencyMs: Int)

    private val reports = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Report>>()

    /** Records [playerId]'s report on [displayId]. */
    fun report(displayId: UUID, playerId: UUID, state: ReadinessState, positionMs: Long, latencyMs: Int) {
        reports.getOrPut(displayId) { ConcurrentHashMap() }[playerId] =
            Report(state, positionMs.coerceAtLeast(0L), latencyMs.coerceIn(0, MAX_LATENCY_MS))
    }

    /** [playerId]'s last report on [displayId], or null if it never sent one. */
    fun reportOf(displayId: UUID, playerId: UUID): Report? = reports[displayId]?.get(playerId)

    /** True if [playerId] holds [displayId] ready within [toleranceMs] of [positionMs]. */
    fun isReadyAt(displayId: UUID, playerId: UUID, positionMs: Long): Boolean {
        val report = reportOf(displayId, playerId) ?: return false
        return report.state == ReadinessState.READY && abs(report.positionMs - positionMs) <= toleranceMs
    }

    /** Of [players], those a start of [displayId] at [positionMs] still waits for: not ready there, and not absent. */
    fun waitingFor(displayId: UUID, positionMs: Long, players: Collection<UUID>): List<UUID> =
        players.filter { playerId ->
            val report = reportOf(displayId, playerId)
            report?.state != ReadinessState.ABSENT && !isReadyAt(displayId, playerId, positionMs)
        }

    /**
     * How far ahead to schedule a start of [displayId] for [players]: the largest one-way latency they reported plus
     * [slackMs], so the start reaches everyone before it is due; at most [capMs], and 0 when [capMs] is 0.
     */
    fun leadMs(displayId: UUID, players: Collection<UUID>, capMs: Long, slackMs: Long = DEFAULT_SLACK_MS): Long {
        if (capMs <= 0L) return 0L
        val worst = players.maxOfOrNull { reportOf(displayId, it)?.latencyMs ?: 0 } ?: 0
        return (worst + slackMs).coerceIn(0L, capMs)
    }

    /** Drops everything [playerId] reported (they left). */
    fun forgetPlayer(playerId: UUID) {
        reports.values.forEach { it.remove(playerId) }
    }

    /** Drops every report on [displayId] (it was removed). */
    fun forgetDisplay(displayId: UUID) {
        reports.remove(displayId)
    }

    /**
     * Drops the reports on [displayId] that belonged to its previous video or speed (a new video, a new speed), but
     * keeps ABSENT: a client that does not play the display does not play it on any video, and says so only once
     * (it reports again by itself when it starts playing the display).
     */
    fun forgetHeldStates(displayId: UUID) {
        reports.computeIfPresent(displayId) { _, byPlayer ->
            byPlayer.entries.removeIf { it.value.state != ReadinessState.ABSENT }
            byPlayer.takeIf { it.isNotEmpty() }
        }
    }

    /** Of [players], those with no report at all on [displayId]. */
    fun unreported(displayId: UUID, players: Collection<UUID>): List<UUID> =
        players.filter { reportOf(displayId, it) == null }

    companion object {
        /** How far a held position may be from the start position and still count as ready there. */
        const val DEFAULT_TOLERANCE_MS = 300L

        /** Time the client needs between hearing a scheduled start and acting on it. */
        const val DEFAULT_SLACK_MS = 15L

        private const val MAX_LATENCY_MS = 2_000
    }
}
