package com.dreamdisplays.media.player.managers

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Watches whether frames are arriving. If no frame arrives within the applicable threshold, calls [onStall] and stops itself;
 * the caller decides how to recover.
 */
internal class StreamWatchdog(
    private val debugLabel: String,
    private val isSessionActive: () -> Boolean,
    private val getLastFrameNanos: () -> Long,
    private val stallThresholdMs: Long = 45_000L,
    private val startupThresholdMs: Long = 20_000L,
    private val checkIntervalMs: Long = 1_000L,
    private val onStall: () -> Unit,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var job: Job? = null
    private var deliveredAFrame = false
    private var lastSeenStamp = 0L
    private var silentSinceNanos = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("MediaPlayer-watchdog"))

    @Volatile
    private var activeStallMs = stallThresholdMs

    @Volatile
    private var activeStartupMs = startupThresholdMs

    /**
     * Start watchdog. A live relay stream passes its own short [Thresholds] ([Thresholds.LIVE_EDGE]): it should
     * reconnect within a couple of seconds, not wait out a VOD's patience.
     */
    fun start(thresholds: Thresholds? = null) {
        stop()
        activeStallMs = thresholds?.stallMs ?: stallThresholdMs
        activeStartupMs = thresholds?.startupMs ?: startupThresholdMs
        val interval = thresholds?.checkIntervalMs ?: checkIntervalMs
        deliveredAFrame = false
        lastSeenStamp = getLastFrameNanos()
        silentSinceNanos = System.nanoTime()
        job = scope.launch {
            delay(interval.milliseconds)
            // Stops at the first stall: recovery is the caller's job, and it restarts the watchdog
            while (isActive && check()) {
                delay(interval.milliseconds)
            }
        }
    }

    /** Per-start override of the stall limits. */
    data class Thresholds(val stallMs: Long, val startupMs: Long, val checkIntervalMs: Long) {
        companion object {
            /** A live relay stream: frames normally arrive every few tens of ms, and the relay answers at once. */
            val LIVE_EDGE = Thresholds(stallMs = 2_000L, startupMs = 6_000L, checkIntervalMs = 250L)
        }
    }

    /** Stop watchdog. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private fun check(): Boolean {
        return runCatching {
            if (!isSessionActive()) {
                silentSinceNanos = System.nanoTime()
                return true
            }
            val stamp = getLastFrameNanos()
            if (stamp != lastSeenStamp) {
                lastSeenStamp = stamp
                deliveredAFrame = true
                silentSinceNanos = stamp
            }
            val silenceMs = (System.nanoTime() - silentSinceNanos) / 1_000_000L
            if (silenceMs < (if (deliveredAFrame) activeStallMs else activeStartupMs)) return true
            val what = if (deliveredAFrame) "No frames for $silenceMs ms" else "No first frame after $silenceMs ms"
            logger.warn("$debugLabel $what. Restarting...")
            onStall()
            false
        }.getOrDefault(true)
    }
}
