package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.core.protocol.common.packets.RemotePlaybackToggle
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * One-shot scheduled play / pause, set via `/display schedule`.
 */
object ScheduledPlaybackManager {
    private val logger = LoggerFactory.getLogger(javaClass)
    private lateinit var transport: PlaybackTransport
    private val jobs = ConcurrentHashMap<UUID, Job>()

    /** Wires the platform transport. */
    fun init(transport: PlaybackTransport) {
        this.transport = transport
    }

    /**
     * Schedules [display] to apply [action] (play/pause) at [at], replacing any existing schedule
     * for it. Persists the new [DisplayData.scheduledStart]/[DisplayData.scheduledAction] immediately.
     */
    fun schedule(display: DisplayData, at: Instant, action: PlaybackAction) {
        jobs.remove(display.id)?.cancel()
        display.scheduledStart = at
        display.scheduledAction = action
        transport.saveDisplay(display)
        jobs[display.id] = launchCountdown(display.id, at)
    }

    /** Cancels [displayId]'s pending schedule, if any, clearing and persisting the fields. */
    fun cancel(displayId: UUID): Boolean {
        val job = jobs.remove(displayId) ?: return false
        job.cancel()
        DisplayManager.getDisplayData(displayId)?.let { display ->
            display.scheduledStart = null
            display.scheduledAction = null
            transport.saveDisplay(display)
        }
        return true
    }

    /** Drops every countdown without touching storage: the server stopped; the next one re-arms its own. */
    fun forgetAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    /** Forgets a removed display's schedule, without touching storage (the display row is already gone). */
    fun onDisplayRemoved(displayId: UUID) {
        jobs.remove(displayId)?.cancel()
    }

    /**
     * Re-arms every loaded display's persisted schedule. A start already in the past (missed while
     * the server was offline) is cleared silently rather than fired immediately. Call once at
     * startup, after displays are loaded and [transport] is bound.
     */
    fun restoreOnStartup() {
        val now = Clock.System.now()
        for (display in DisplayManager.getDisplays()) {
            val at = display.scheduledStart ?: continue
            if (at <= now) {
                display.scheduledStart = null
                display.scheduledAction = null
                transport.saveDisplay(display)
                continue
            }
            jobs[display.id] = launchCountdown(display.id, at)
        }
    }

    private fun launchCountdown(displayId: UUID, at: Instant): Job =
        ServerCoroutines.io.launch {
            val remaining = at - Clock.System.now()
            if (remaining > Duration.ZERO) delay(remaining)
            transport.runOnMainThread { fire(displayId, at) }
        }

    /**
     * The countdown for [at] ran out. Only while that is still [displayId]'s schedule: one cancelled or replaced after
     * its countdown already handed this to the main thread does nothing.
     */
    private fun fire(displayId: UUID, at: Instant) {
        val display = DisplayManager.getDisplayData(displayId) ?: return
        if (display.scheduledStart != at) return
        jobs.remove(displayId)
        val action = display.scheduledAction ?: PlaybackAction.PLAY
        display.scheduledStart = null
        display.scheduledAction = null
        transport.saveDisplay(display)

        when (display.mode) {
            PlaybackMode.SYNCED, PlaybackMode.BROADCAST -> {
                if (!TimelineManager.applyScheduled(display, action)) {
                    logger.warn("Scheduled $action for display $displayId (mode ${display.mode}) did not apply.")
                }
            }

            PlaybackMode.LOCAL, PlaybackMode.WATCH_PARTY ->
                transport.broadcast(display, RemotePlaybackToggle(displayId, action == PlaybackAction.PAUSE))
        }
    }
}
