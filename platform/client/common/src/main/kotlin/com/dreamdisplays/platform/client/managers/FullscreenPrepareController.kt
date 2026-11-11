package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.core.protocol.common.packets.FullscreenPrepare
import com.dreamdisplays.core.protocol.common.packets.FullscreenPrepareStatus
import com.dreamdisplays.platform.client.displays.DisplayPolicyCache
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.displays.ServerOverrides
import com.dreamdisplays.platform.client.net.ProtocolRouter
import com.dreamdisplays.shared.playback.FullscreenPrepareState
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds displays ready ahead of a fullscreen broadcast ([FullscreenPrepare]): the display stays loaded whatever the
 * distance, hidden and silent while out of sight, and is kept where the broadcast will start, so the broadcast's
 * [com.dreamdisplays.core.protocol.common.packets.FullscreenState] shows a picture that is already playing (or plays
 * on the spot from a held, buffered pause). Reports how far it got to the server ([FullscreenPrepareStatus]).
 */
object FullscreenPrepareController {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Extra time past the server's ttl before giving up on a preparation the server never cancelled. */
    private const val TTL_GRACE_MS = 15_000L

    /** Gap between two attempts to hold a paused display ready. */
    private const val WARM_RETRY_MS = 3_000L

    /** Re-open a held stream this often, so a connection left idle for minutes is not the one the start reads from. */
    private const val WARM_REFRESH_MS = 60_000L

    private class Entry(
        val prepareId: String,
        var quality: String,
        var expiresAtMs: Long,
    ) {
        var qualityApplied = false
        var reported: FullscreenPrepareState? = null
        var lastWarmAttemptMs = 0L
        var heldSinceMs = 0L

        /** Re-opening a held stream on schedule; it stays reported ready meanwhile. */
        var refreshing = false
    }

    private val entries = ConcurrentHashMap<UUID, Entry>()

    /** True while a preparation holds [displayId]. */
    fun isPrepared(displayId: UUID): Boolean = entries.containsKey(displayId)

    /** Applies a server [FullscreenPrepare]: starts holding the display, or lets it go. */
    fun handle(packet: FullscreenPrepare) {
        if (!packet.active) {
            release(packet.displayId, packet.prepareId)
            return
        }
        val now = System.currentTimeMillis()
        val expiresAt = if (packet.ttlMs > 0) now + packet.ttlMs + TTL_GRACE_MS else Long.MAX_VALUE
        val existing = entries[packet.displayId]
        if (existing != null && existing.prepareId == packet.prepareId) {
            // A re-delivery (or a re-prepare) of the same preparation keeps its progress
            existing.expiresAtMs = expiresAt
            if (existing.quality != packet.quality) {
                existing.quality = packet.quality
                existing.qualityApplied = false
            }
            // Report again, so a server that lost track of this client hears where it stands
            existing.reported = null
            return
        }
        entries[packet.displayId] = Entry(packet.prepareId, packet.quality, expiresAt)
        logger.info("Holding display {} ready for a fullscreen broadcast ({}).", packet.displayId, packet.prepareId)
        DisplayRegistry.screens[packet.displayId]?.refreshPrepareState()
    }

    /** The broadcast this display was held for has started: the fullscreen session owns it from here. */
    fun onFullscreenStarted(displayId: UUID) {
        if (entries.remove(displayId) != null) logger.info("Fullscreen broadcast started on prepared display {}.", displayId)
    }

    /** Checks progress, keeps paused displays held ready, and reports changes. Called once per client tick. */
    fun onClientTick() {
        if (entries.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((displayId, entry) in entries.entries.toList()) {
            if (now >= entry.expiresAtMs) {
                logger.info("Fullscreen preparation {} for display {} expired.", entry.prepareId, displayId)
                release(displayId, entry.prepareId)
                continue
            }
            val screen = DisplayRegistry.screens[displayId]
            val state = if (screen == null) {
                val switchedOff = !ClientStateManager.displaysEnabled || !ServerOverrides.displaysEnabled ||
                        !ServerOverrides.displayEnabled(DisplayPolicyCache.get(displayId))
                if (switchedOff) {
                    FullscreenPrepareState.DISABLED
                } else {
                    FullscreenPrepareState.LOADING
                }
            } else {
                progress(screen, entry, now)
            }
            if (state != entry.reported) {
                entry.reported = state
                ProtocolRouter.send(FullscreenPrepareStatus(entry.prepareId, displayId, state.wire))
            }
        }
    }

    private fun progress(screen: DisplayScreen, entry: Entry, now: Long): FullscreenPrepareState {
        if (!entry.qualityApplied) {
            entry.qualityApplied = true
            // Open at the broadcast's quality now, so starting it does not switch streams
            if (entry.quality.isNotEmpty()) {
                val quality = VideoQuality.parse(entry.quality)
                if (screen.quality != quality) screen.quality = quality
            }
        }
        if (screen.errored) return FullscreenPrepareState.FAILED
        if (!screen.videoStarted || screen.isWaitingForInitialTimeline) return FullscreenPrepareState.LOADING
        if (!screen.isPaused) {
            entry.heldSinceMs = 0L
            entry.refreshing = false
            // Playing hidden along the timeline: the overlay only has to appear
            return if (screen.isClockRunning()) FullscreenPrepareState.READY else FullscreenPrepareState.LOADING
        }
        // A paused live stream or an unseekable file cannot be held any closer than it is
        if (screen.isLive || !screen.canSeek()) return FullscreenPrepareState.READY

        if (screen.isWarmingUp) {
            // Re-opening a held stream keeps it counted ready: a start still finds it open or opening
            return if (entry.refreshing) FullscreenPrepareState.READY else FullscreenPrepareState.LOADING
        }
        if (screen.isHeldReady) {
            entry.refreshing = false
            if (entry.heldSinceMs == 0L) entry.heldSinceMs = now
            if (now - entry.heldSinceMs >= WARM_REFRESH_MS) {
                entry.refreshing = true
                entry.heldSinceMs = 0L
                entry.lastWarmAttemptMs = now
                screen.holdReadyForBroadcast()
            }
            return FullscreenPrepareState.READY
        }
        entry.refreshing = false
        entry.heldSinceMs = 0L
        if (now - entry.lastWarmAttemptMs >= WARM_RETRY_MS) {
            entry.lastWarmAttemptMs = now
            screen.holdReadyForBroadcast()
        }
        return FullscreenPrepareState.LOADING
    }

    private fun release(displayId: UUID, prepareId: String) {
        val entry = entries[displayId] ?: return
        if (prepareId.isNotEmpty() && entry.prepareId != prepareId) return
        entries.remove(displayId)
        logger.info("Released display {} held for a fullscreen broadcast ({}).", displayId, entry.prepareId)
        val screen = DisplayRegistry.screens[displayId] ?: return
        // A broadcast-only display nobody started: nothing else will ever show it
        if (screen.virtual && !screen.isPopoutActive) {
            DisplayRegistry.unregisterScreen(screen)
            return
        }
        // A world display goes back to the normal distance rules on the next tick
        screen.refreshPrepareState()
    }

    /** Drops every preparation (disconnect / backend switch). */
    fun reset() {
        entries.clear()
    }
}
