package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.core.protocol.common.hasFeature
import com.dreamdisplays.core.protocol.common.packets.DisplayReadiness
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.displays.ServerOverrides
import com.dreamdisplays.platform.client.displays.TimelineFollower
import com.dreamdisplays.platform.client.net.ProtocolRouter
import com.dreamdisplays.shared.capability.ServerFeature
import com.dreamdisplays.shared.playback.ReadinessState
import net.minecraft.client.Minecraft
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Client end of display readiness: keeps paused synced displays *held ready* (stream open at the paused position,
 * start-up cushion decoded, sound primed; see [DisplayScreen.holdReadyForBroadcast]) so pressing play starts them at
 * once, and tells the server where each display is held ([DisplayReadiness]), so the server can end a start hold the
 * moment every viewer is ready and schedule the start for all of them at the same instant.
 *
 * What gets held, once per tick:
 * - every display a preparation holds ([DisplayScreen.isPrepared]; `/display prepare`, `/display fullscreen prepare`),
 *   for as long as it lasts, its stream re-opened every [REFRESH_MS] so it never starts on a connection gone stale;
 * - on a server that advertises [ServerFeature.DISPLAY_READINESS], the [AUTO_HELD] nearest paused displays that follow
 *   the server's timeline, for [AUTO_MAX_MS] after they paused (then they stay as they are, just not refreshed), unless
 *   the server turned that off (`client.autoWarm`).
 *
 * What gets reported: READY where held; UNHOLDABLE where this client will not hold the display (live, unseekable,
 * failed, or a paused display none of the rules above holds), so a start does not wait for it; NOT_READY while it
 * may still become ready (loading, playing, warming).
 *
 * The API another feature uses (e.g. a queue holding its next item ready): [holdOf] for where a display stands,
 * [requestHold] to hold one that the rules above would not, [release] to stop that again. Starting a held display is
 * the server's job (`TimelineManager.startWhenReady`), which this reports into.
 */
object DisplayWarmup {
    /** Where a display stands. */
    enum class Hold {
        /** Nothing to hold (playing, loading, live, or not seekable). */
        NONE,

        /** Opening its stream at the paused position. */
        WARMING,

        /** Held ready: a resume plays at once. */
        HELD,
    }

    /** Gap between two attempts to hold a display. */
    private const val RETRY_MS = 3_000L

    /** Re-open a held stream this often, so a start never reads from a connection left idle for minutes. */
    private const val REFRESH_MS = 60_000L

    /** How many paused displays (nearest first) are held without being asked to. */
    private const val AUTO_HELD = 2

    /** How long after pausing a display is held without being asked to. */
    private const val AUTO_MAX_MS = 10 * 60_000L

    /** Report a held position again once it moved by more than this. */
    private const val POSITION_REPORT_STEP_MS = 100L

    private class Track {
        var lastAttemptMs = 0L
        var heldSinceMs = 0L
        var refreshing = false
        var pausedSinceMs = 0L
        var reportedState: ReadinessState? = null
        var reportedPositionMs = 0L
    }

    private val tracks = ConcurrentHashMap<UUID, Track>()
    private val requested: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Displays this client told the server it does not play (so a start never waits for it on them). */
    private val reportedAbsent: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Where [screen] stands right now. */
    fun holdOf(screen: DisplayScreen): Hold = when {
        screen.isHeldReady -> Hold.HELD
        screen.isWarmingUp -> Hold.WARMING
        else -> Hold.NONE
    }

    /** Holds [displayId] ready whenever it is loaded and paused, until [release]d (on top of the automatic rules). */
    fun requestHold(displayId: UUID) {
        requested.add(displayId)
    }

    /** Ends a [requestHold]. */
    fun release(displayId: UUID) {
        requested.remove(displayId)
    }

    /** Keeps displays held and reports changes. Called once per client tick, before the preparation tick. */
    fun onClientTick() {
        val now = System.currentTimeMillis()
        val reports = ClientPacketManager.serverSnapshot.hasFeature(ServerFeature.DISPLAY_READINESS)
        val player = Minecraft.getInstance().player
        val screens = DisplayRegistry.getScreens().filter { !it.isDormant && (it.followsServerTimeline || it.isPrepared) }
        val autoHeld = if (reports && player != null && ServerOverrides.settings.autoWarm) {
            screens.filter { it.followsServerTimeline && it.isPaused && !it.isPrepared }
                .sortedBy { it.getDistanceToScreen(player.blockPosition()) }
                .take(AUTO_HELD)
                .mapTo(HashSet()) { it.uuid }
        } else {
            emptySet()
        }
        val seen = HashSet<UUID>()
        for (screen in screens) {
            seen += screen.uuid
            reportedAbsent.remove(screen.uuid)
            val track = tracks.getOrPut(screen.uuid) { Track() }
            if (!screen.isPaused) track.pausedSinceMs = 0L else if (track.pausedSinceMs == 0L) track.pausedSinceMs = now
            val persistent = screen.isPrepared || screen.uuid in requested
            val auto = screen.uuid in autoHeld && now - track.pausedSinceMs < AUTO_MAX_MS
            if (persistent || auto) maintain(screen, track, now)
            if (reports) report(screen, track, holds = persistent || auto)
        }
        tracks.keys.retainAll(seen)
        if (!reports) return
        // Known here but not played (beyond this client's distance, over its budget, parked): never wait for it on them
        val absent = DisplayRegistry.unloadedScreens.keys + DisplayRegistry.dormantScreens().map { it.uuid }
        for (displayId in absent) {
            if (displayId in seen || !reportedAbsent.add(displayId)) continue
            send(displayId, ReadinessState.ABSENT, 0L)
        }
    }

    private fun maintain(screen: DisplayScreen, track: Track, now: Long) {
        if (screen.errored || !screen.videoStarted || screen.isWaitingForInitialTimeline) return
        if (!screen.isPaused || screen.isLive || !screen.canSeek()) {
            track.heldSinceMs = 0L
            track.refreshing = false
            return
        }
        when (holdOf(screen)) {
            Hold.WARMING -> return
            Hold.HELD -> {
                track.refreshing = false
                if (track.heldSinceMs == 0L) track.heldSinceMs = now
                if (now - track.heldSinceMs >= REFRESH_MS) {
                    track.refreshing = true
                    track.heldSinceMs = 0L
                    track.lastAttemptMs = now
                    screen.holdReadyForBroadcast()
                }
            }

            Hold.NONE -> {
                track.refreshing = false
                track.heldSinceMs = 0L
                if (now - track.lastAttemptMs >= RETRY_MS) {
                    track.lastAttemptMs = now
                    screen.holdReadyForBroadcast()
                }
            }
        }
    }

    /** True while [screen] re-opens a held stream on schedule: a start still finds it open or opening. */
    internal fun isRefreshing(screen: DisplayScreen): Boolean =
        tracks[screen.uuid]?.refreshing == true && screen.isWarmingUp

    /** [holds]: one of the rules holds [screen] while it is paused. */
    private fun report(screen: DisplayScreen, track: Track, holds: Boolean) {
        val refreshing = isRefreshing(screen)
        val held = screen.isHeldReady || refreshing
        val state = readinessOf(
            held = held, errored = screen.errored,
            started = screen.videoStarted && !screen.isWaitingForInitialTimeline,
            holdable = !screen.isLive && screen.canSeek(), paused = screen.isPaused, holds = holds,
        )
        val position = when {
            refreshing -> track.reportedPositionMs // Still where it was held; the re-open lands there again
            held -> screen.syncPositionNanos / 1_000_000L
            else -> 0L
        }
        val moved = held && abs(position - track.reportedPositionMs) > POSITION_REPORT_STEP_MS
        if (state == track.reportedState && !moved) return
        track.reportedState = state
        track.reportedPositionMs = position
        send(screen.uuid, state, position)
    }

    private fun send(displayId: UUID, state: ReadinessState, positionMs: Long) {
        ProtocolRouter.send(
            DisplayReadiness(
                displayId, state.wire, positionMs, TimelineFollower.oneWayLatencyMs().roundToInt(),
                com.dreamdisplays.platform.client.displays.ServerMediaGens.onScreen(displayId),
            ),
        )
    }

    /**
     * Sends what this client holds again, unchanged or not: for [displayId], or every display when null. A loaded
     * display reports on the next tick; a display known here but not played reports ABSENT on the next tick; a display
     * this client knows nothing of is reported ABSENT at once. Used when the server asks (`ReportRequest`) and on every
     * server hello (a reloaded server has forgotten every report).
     */
    fun reReport(displayId: UUID?) {
        if (displayId == null) {
            tracks.values.forEach { it.reportedState = null }
            reportedAbsent.clear()
            return
        }
        tracks[displayId]?.let {
            it.reportedState = null
            return
        }
        reportedAbsent.remove(displayId)
        val known = DisplayRegistry.screens.containsKey(displayId) || DisplayRegistry.unloadedScreens.containsKey(displayId)
        if (!known && ClientPacketManager.serverSnapshot.hasFeature(ServerFeature.DISPLAY_READINESS)) {
            send(displayId, ReadinessState.ABSENT, 0L)
        }
    }

    /** Forgets everything (disconnect / backend switch). */
    fun reset() {
        tracks.clear()
        requested.clear()
        reportedAbsent.clear()
    }
}

/**
 * What a client reports about one loaded display: [held] ready -> READY; one it will never hold ([errored], a live or
 * unseekable stream once [started], or [paused] while no rule [holds] it) -> UNHOLDABLE, so starts do not wait for it;
 * anything that may still become ready (loading, playing, warming) -> NOT_READY.
 */
internal fun readinessOf(
    held: Boolean, errored: Boolean, started: Boolean, holdable: Boolean, paused: Boolean, holds: Boolean,
): ReadinessState = when {
    held -> ReadinessState.READY
    errored -> ReadinessState.UNHOLDABLE
    !started -> ReadinessState.NOT_READY
    !holdable -> ReadinessState.UNHOLDABLE
    paused && !holds -> ReadinessState.UNHOLDABLE
    else -> ReadinessState.NOT_READY
}
