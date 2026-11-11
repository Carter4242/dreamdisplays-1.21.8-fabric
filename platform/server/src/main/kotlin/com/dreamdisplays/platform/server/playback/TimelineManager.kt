package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.shared.playback.PlaybackPermissions
import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.core.protocol.common.toSync
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.managers.ActionThrottle
import com.dreamdisplays.platform.server.managers.DisplayManager
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the authoritative playback clock for every `SYNCED` and `BROADCAST` display. Clients never report their own
 * position back as truth.
 */
object TimelineManager {
    private const val PERIODIC_BROADCAST_MS = 5_000L
    private const val MAX_DURATION_MS = 24L * 60 * 60 * 1_000
    private const val MIN_DURATION_MS = 2_000L
    private val reportDurationThrottle = ActionThrottle()
    private const val REPORT_DURATION_COOLDOWN_MS = 2_000L
    private val commandThrottle = ActionThrottle()
    private const val COMMAND_COOLDOWN_MS = 100L
    private lateinit var transport: PlaybackTransport
    private val timelines = ConcurrentHashMap<UUID, Timeline>()
    private val lastBroadcast = ConcurrentHashMap<UUID, Long>()
    private val startHolds = ConcurrentHashMap<UUID, Long>()
    private const val START_HOLD_MS = 5_000L
    private val RESYNC_PARAM = Regex("""([?&])ddsync=(\d+)""")

    internal const val MAX_SEEK_MS = 24L * 60 * 60 * 1_000

    /** resync hold length; the platform points this at its live config (`display.resync_hold_seconds`). */
    var resyncHoldMs: () -> Long = { 10_000L }

    /** Wires the platform transport and seeds timelines for already-loaded displays. */
    fun init(transport: PlaybackTransport) {
        this.transport = transport
        DisplayManager.getDisplays().forEach(::ensureTimeline)
    }

    /**
     * Applies a client playback intent to a `SYNCED` display. Returns true when it was permitted, applied, and rebroadcast;
     * false otherwise.
     */
    fun onCommand(display: DisplayData, senderId: UUID, action: PlaybackAction, positionMs: Long): Boolean {
        if (display.mode != PlaybackMode.SYNCED || WatchPartyManager.hasSession(display.id)) return false
        val ctx = PlaybackContexts.of(
            display, senderId, transport.isAdmin(senderId),
            { transport.isTerritoryMember(display, senderId) },
        )
        if (!PlaybackPermissions.canPlayPause(ctx)) return false
        if (!commandThrottle.tryAcquire(senderId, COMMAND_COOLDOWN_MS)) return false
        if (senderId !in transport.nearbyPlayerIds(display)) return false

        val now = transport.nowMs()
        startHolds.remove(display.id)
        val current = timelines[display.id] ?: Timeline.start(now)
        val updated = when (action) {
            PlaybackAction.PLAY -> current.withPaused(false, now)
            PlaybackAction.PAUSE -> current.withPaused(true, now)
            PlaybackAction.SEEK -> current.seekedTo(clampSeek(positionMs, display), now)
            PlaybackAction.RESTART -> Timeline.start(now)
        }
        // a resume, seek or restart reloads every viewer at the new position and holds it paused until
        // they have all buffered, so they start together instead of each correcting its own drift.
        val resumes = action == PlaybackAction.PLAY && current.paused
        val jumps = action == PlaybackAction.SEEK || action == PlaybackAction.RESTART
        if (resumes || jumps) {
            resyncAndHold(display, updated, now, holdThenPlay = resumes || action == PlaybackAction.RESTART || !current.paused)
            return true
        }
        timelines[display.id] = updated
        broadcast(display, updated)
        return true
    }

    /**
     * resync. The stock client only corrects drift above 1.5 s behind / 3.5 s ahead and backs off after slow
     * seeks, so viewers drift apart and stay there. Changing a display's URL makes every client drop its player,
     * request the timeline and open the video already positioned at it, so: park the timeline paused at
     * [target], bump a harmless `ddsync` query parameter so clients reload, then (when [holdThenPlay]) release
     * the hold after [resyncHoldMs] through the existing start-hold tick, starting everyone from the same frame.
     */
    private fun resyncAndHold(display: DisplayData, target: Timeline, now: Long, holdThenPlay: Boolean) {
        val held = target.withPaused(true, now)
        timelines[display.id] = held
        if (holdThenPlay) startHolds[display.id] = now + resyncHoldMs()
        bumpResyncMarker(display)
        broadcast(display, held)
    }

    private fun bumpResyncMarker(display: DisplayData) {
        val url = display.url
        if (url.isBlank()) return
        val hashAt = url.indexOf('#')
        val base = if (hashAt >= 0) url.substring(0, hashAt) else url
        val fragment = if (hashAt >= 0) url.substring(hashAt) else ""
        val next = if (RESYNC_PARAM.find(base)?.groupValues?.get(2) == "1") 2 else 1
        val stripped = base.replace(RESYNC_PARAM) { m -> if (m.groupValues[1] == "?") "?" else "" }
            .replace("?&", "?").removeSuffix("?")
        val separator = if ('?' in stripped) "&" else "?"
        display.url = "$stripped${separator}ddsync=$next$fragment"
        transport.runOnMainThread {
            transport.saveDisplay(display)
            transport.nearbyPlayerIds(display).forEach { transport.sendDisplayInfo(it, display, forced = false) }
        }
    }

    /**
     * Server-initiated play / pause for [display], bypassing the sender / nearby checks [onCommand]
     * applies to client intents — used by [ScheduledPlaybackManager] when a scheduled action fires.
     */
    fun applyScheduled(display: DisplayData, action: PlaybackAction): Boolean {
        if (display.mode != PlaybackMode.SYNCED && display.mode != PlaybackMode.BROADCAST) return false
        if (action != PlaybackAction.PLAY && action != PlaybackAction.PAUSE) return false
        val now = transport.nowMs()
        startHolds.remove(display.id)
        val current = ensureTimeline(display) ?: Timeline.start(now, durationMs = durationMsOf(display), loop = true)
        val updated = current.withPaused(action == PlaybackAction.PAUSE, now)
        timelines[display.id] = updated
        broadcast(display, updated)
        return true
    }

    /** Sends the current timeline to one player (RequestSync reply / late-join catch-up). */
    fun sendCurrent(display: DisplayData, playerId: UUID) {
        val timeline = ensureTimeline(display) ?: return
        transport.sendTo(playerId, timeline.toSync(display.id, display.mode, transport.nowMs()))
    }

    /** Re-initializes the clock after a base-mode change and broadcasts the result. */
    fun onModeChanged(display: DisplayData, positionMs: Long = -1) {
        timelines.remove(display.id)
        startHolds.remove(display.id)
        val now = transport.nowMs()
        val durationMs = durationMsOf(display)
        val timeline = when (display.mode) {
            PlaybackMode.SYNCED, PlaybackMode.BROADCAST ->
                Timeline(positionMs.coerceAtLeast(0), now, paused = false, durationMs = durationMs, loop = true)

            else -> null
        }
        if (timeline != null) {
            timelines[display.id] = timeline
            broadcast(display, timeline)
        }
    }

    /** Resets the clock to 0 when the video changes (`SYNCED` / `BROADCAST` only), held paused for [START_HOLD_MS]. */
    fun onVideoChanged(display: DisplayData) {
        if (display.mode != PlaybackMode.SYNCED && display.mode != PlaybackMode.BROADCAST) return
        display.duration = null // Fresh video
        val now = transport.nowMs()
        val fresh = Timeline.start(now, paused = true, loop = true)
        timelines[display.id] = fresh
        startHolds[display.id] = now + START_HOLD_MS
        broadcast(display, fresh)
    }

    /**
     * Applies a client-reported media duration for [display]'s current video.
     *
     * First-report-wins: a no-op once [DisplayData.duration] is already set.
     */
    fun onDurationReported(display: DisplayData, senderId: UUID, durationMs: Long) {
        if (display.mode != PlaybackMode.SYNCED && display.mode != PlaybackMode.BROADCAST) return
        if (durationMs !in MIN_DURATION_MS..MAX_DURATION_MS) return
        if ((display.duration ?: 0L) > 0L) return
        if (senderId !in transport.nearbyPlayerIds(display)) return
        if (!reportDurationThrottle.tryAcquire(display.id, REPORT_DURATION_COOLDOWN_MS)) return

        display.duration = durationMs * 1_000_000L
        val current = timelines[display.id] ?: return
        timelines[display.id] = current.copy(durationMs = durationMs, loop = true)
        broadcast(display, timelines.getValue(display.id))
    }

    /** Forgets a removed display. */
    fun remove(displayId: UUID) {
        timelines.remove(displayId)
        lastBroadcast.remove(displayId)
        startHolds.remove(displayId)
    }

    /**
     * Periodic keep-alive so late joiners and drifting clients stay corrected.
     *
     * Called once per second.
     */
    fun tick() {
        if (timelines.isEmpty()) return
        val now = transport.nowMs()
        releaseStartHolds(now)
        for ((displayId, timeline) in timelines) {
            if (now - (lastBroadcast[displayId] ?: 0L) < PERIODIC_BROADCAST_MS) continue
            val display = DisplayManager.getDisplayData(displayId) ?: continue
            broadcast(display, timeline)
        }
    }

    private fun releaseStartHolds(now: Long) {
        for ((displayId, startAt) in startHolds) {
            if (now < startAt) continue
            startHolds.remove(displayId)
            if (WatchPartyManager.hasSession(displayId)) continue
            val timeline = timelines[displayId] ?: continue
            val display = DisplayManager.getDisplayData(displayId) ?: continue
            val started = timeline.withPaused(false, now)
            timelines[displayId] = started
            broadcast(display, started)
        }
    }

    internal fun clampSeek(positionMs: Long, display: DisplayData): Long {
        val durationMs = durationMsOf(display)
        return positionMs.coerceIn(0, if (durationMs > 0) durationMs else MAX_SEEK_MS)
    }

    private fun durationMsOf(display: DisplayData): Long = display.duration?.let { it / 1_000_000L } ?: 0L

    private fun ensureTimeline(display: DisplayData): Timeline? {
        if (WatchPartyManager.hasSession(display.id)) return null
        val durationMs = durationMsOf(display)
        return when (display.mode) {
            PlaybackMode.SYNCED, PlaybackMode.BROADCAST ->
                timelines.getOrPut(display.id) {
                    Timeline.start(
                        transport.nowMs(),
                        durationMs = durationMs,
                        loop = true
                    )
                }

            else -> {
                timelines.remove(display.id); null
            }
        }
    }

    private fun broadcast(display: DisplayData, timeline: Timeline) {
        val now = transport.nowMs()
        lastBroadcast[display.id] = now
        transport.broadcast(display, timeline.toSync(display.id, display.mode, now))
    }
}
