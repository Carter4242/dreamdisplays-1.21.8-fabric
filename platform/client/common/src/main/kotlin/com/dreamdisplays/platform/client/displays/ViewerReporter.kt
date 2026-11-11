package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.core.protocol.common.packets.ViewerReport
import com.dreamdisplays.core.protocol.common.packets.ViewerState
import com.dreamdisplays.platform.client.Initializer
import java.util.*
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Sends the server a [ViewerReport] for every loaded display every few seconds, and as soon as a display fails or
 * recovers. Only runs for servers that sent a server-wide policy (they set the interval; 0 turns reports off).
 * Ticked on the client thread.
 */
object ViewerReporter {
    private const val MIN_GAP_MS = 1_000L
    private const val MAX_MESSAGE = 160

    private class Track {
        var lastSentMs = 0L
        var errored = false
        var buffering = false
        var stalls = 0
        var pendingEdge = false
    }

    private val tracks = HashMap<UUID, Track>()

    fun tick() {
        val interval = ServerOverrides.settings.reportIntervalMs
        if (interval <= 0L) {
            tracks.clear()
            return
        }
        val now = System.currentTimeMillis()
        val seen = HashSet<UUID>()
        for (screen in DisplayRegistry.getScreens()) {
            if (screen.isDormant || screen.videoUrl.isNullOrEmpty()) continue
            seen += screen.uuid
            val track = tracks.getOrPut(screen.uuid) { Track() }
            val buffering = isBuffering(screen)
            if (buffering && !track.buffering) track.stalls++
            track.buffering = buffering
            val errored = screen.errored
            if (errored != track.errored) track.pendingEdge = true
            track.errored = errored

            val sinceLast = now - track.lastSentMs
            val due = sinceLast >= interval || (track.pendingEdge && sinceLast >= MIN_GAP_MS)
            if (!due) continue
            runCatching { Initializer.sendPacket(build(screen, track)) }
            track.lastSentMs = now
            track.stalls = 0
            track.pendingEdge = false
        }
        tracks.keys.retainAll(seen)
    }

    fun reset() = tracks.clear()

    private fun isBuffering(screen: DisplayScreen): Boolean =
        !screen.errored && screen.videoStarted && !screen.isPaused && !screen.isClockRunning()

    private fun build(screen: DisplayScreen, track: Track): ViewerReport {
        val sync = screen.timelineFollower.takeReport()
        val error = screen.mediaError
        val state = when {
            error != null -> ViewerState.ERROR
            !screen.videoStarted -> ViewerState.LOADING
            screen.isPaused -> ViewerState.PAUSED
            track.buffering -> ViewerState.BUFFERING
            else -> ViewerState.PLAYING
        }
        return ViewerReport(
            id = screen.uuid,
            state = state.wire,
            syncing = sync.active,
            driftMs = sync.driftMs.roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
            rate = sync.ratio,
            latencyCompMs = sync.latencyCompMs.roundToInt(),
            action = sync.action,
            seeks = sync.seeks,
            stalls = track.stalls,
            buffering = track.buffering,
            positionMs = screen.currentTimeNanos / 1_000_000L,
            qualityHeight = runCatching { screen.playbackQuality.targetHeight ?: 0 }.getOrDefault(0),
            errorKind = error?.kind?.name ?: "",
            errorMessage = (error?.message ?: "").take(MAX_MESSAGE),
        )
    }
}
