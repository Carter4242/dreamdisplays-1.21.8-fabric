package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.core.protocol.common.packets.DisplayReadiness
import com.dreamdisplays.core.protocol.common.packets.MediaGen
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import com.dreamdisplays.shared.playback.ReadinessRoster
import com.dreamdisplays.shared.playback.ReadinessState
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Server end of display readiness: which clients hold which display ready to play at once (paused on a decoded frame,
 * stream and sound open) and where. Clients that support it ([com.dreamdisplays.core.protocol.common.packets.ClientHello.supportsReadiness])
 * report through [DisplayReadiness] packets; clients that do not are never waited for and simply follow the timeline.
 *
 * [TimelineManager] uses it to end a start hold (resume, seek, restart, new video) the moment every viewer is ready,
 * and to schedule the start [leadMs] ahead so they all begin on the same frame. Other features that want a display to
 * start on time (a queue switching to its next item, a scheduled event) use the same calls:
 * - [viewersOf]: whom a start of a display waits for;
 * - [waitingFor]: which of them are not yet ready at a position (empty = start now);
 * - [leadMs]: how far ahead to anchor the start;
 * - [addListener]: hear when a report changes, to re-check [waitingFor];
 * - [markReady]: vouch for a client (e.g. it preloaded the next video), so a start need not wait for its report.
 * Starting itself goes through [TimelineManager.startWhenReady].
 */
object ReadinessTracker {
    private val roster = ReadinessRoster()
    private val listeners = CopyOnWriteArrayList<(UUID) -> Unit>()
    private val latencyMs = ConcurrentHashMap<UUID, Int>()

    /** Largest scheduled-start lead in ms (0 = start on receipt); the platform points this at `display.start_lead_max_ms`. */
    var startLeadMaxMs: () -> Long = { 150L }

    /** True if [playerId]'s client reports readiness. */
    fun supports(playerId: UUID): Boolean = V2PlayerTracker.helloOf(playerId)?.supportsReadiness == true

    /** Applies a client's report and tells the listeners. */
    fun onReport(playerId: UUID, packet: DisplayReadiness) {
        // Only displays that exist: made-up ids would grow the roster without bound
        val display = DisplayManager.getDisplayData(packet.id)
        if (display == null && !FullscreenBroadcastManager.knowsDisplay(packet.id)) return
        latencyMs[playerId] = packet.latencyMs
        val state = ReadinessState.fromWire(packet.state)
        // Held ready on the video the display had before: not ready for this one. Absent holds for any video.
        if (state != ReadinessState.ABSENT && display != null && !MediaGen.matches(packet.mediaGen, display.mediaGen)) return
        roster.report(packet.id, playerId, state, packet.positionMs, packet.latencyMs)
        listeners.forEach { it(packet.id) }
    }

    /**
     * Records that [playerId] holds [displayId] ready at [positionMs] without a report from it, because the caller
     * knows better (the queue: the client preloaded the video that is about to become the display's).
     */
    fun markReady(displayId: UUID, playerId: UUID, positionMs: Long) {
        roster.report(displayId, playerId, ReadinessState.READY, positionMs, latencyMs[playerId] ?: 0)
    }

    /** Calls [listener] with the display id whenever a client reports on it. */
    fun addListener(listener: (displayId: UUID) -> Unit) {
        listeners.add(listener)
    }

    /** Of [players], those that report readiness and have displays on: the ones a start can wait for. */
    fun viewersOf(players: Collection<UUID>): List<UUID> =
        players.distinct().filter { supports(it) && PlayerManager.isDisplaysEnabled(it) }

    /** Of [players], those a start of [displayId] at [positionMs] still waits for (see [ReadinessRoster.waitingFor]). */
    fun waitingFor(displayId: UUID, positionMs: Long, players: Collection<UUID>): List<UUID> =
        roster.waitingFor(displayId, positionMs, viewersOf(players))

    /** True if [playerId] holds [displayId] ready at [positionMs]. */
    fun isReady(displayId: UUID, playerId: UUID, positionMs: Long): Boolean = roster.isReadyAt(displayId, playerId, positionMs)

    /** How far ahead to schedule a start of [displayId] so [players] all begin together (see [ReadinessRoster.leadMs]). */
    fun leadMs(displayId: UUID, players: Collection<UUID>): Long =
        roster.leadMs(displayId, viewersOf(players), startLeadMaxMs())

    /** [playerId]'s last report on [displayId], or null. */
    fun reportOf(displayId: UUID, playerId: UUID): ReadinessRoster.Report? = roster.reportOf(displayId, playerId)

    fun forgetPlayer(playerId: UUID) {
        latencyMs.remove(playerId)
        roster.forgetPlayer(playerId)
    }

    fun forgetDisplay(displayId: UUID) = roster.forgetDisplay(displayId)
}
