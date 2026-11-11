package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.model.MediaFailureKind
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The media error a display shows, plus its automatic recovery.
 *
 * - Right after a retry, a new error is held back for [cooldown] rather than shown at once (so a
 *   retry does not flash the error panel), and shown afterwards if nothing cleared it in between.
 * - A non-fatal network error (the player already retried quietly and gave up) is retried again on
 *   its own, a few times with growing, jittered gaps, so a display that lost its host for a few
 *   minutes comes back without anyone pressing Retry.
 */
internal class MediaErrorGate(
    private val cooldown: Duration = 15.seconds,
    private val firstAutoRetry: Duration = 45.seconds,
    private val maxAutoRetries: Int = 5,
    private val time: TimeSource = TimeSource.Monotonic,
    private val random: Random = Random.Default,
) {
    @Volatile
    var current: DreamMediaException? = null
        private set

    @Volatile
    private var deferred: DreamMediaException? = null

    private var shownAt: TimeMark? = null
    private var retryAt: TimeMark? = null
    private var autoRetries = 0
    private var nextAutoRetry: Duration = jittered(firstAutoRetry)

    /** Records [value] as the display's error (null clears it). */
    @Synchronized
    fun set(value: DreamMediaException?) {
        if (value == null) {
            deferred = null
            current = null
            return
        }
        if (inCooldown()) {
            deferred = value
            return
        }
        show(value)
    }

    /** A retry (manual or automatic) has just started a fresh load. */
    @Synchronized
    fun markRetry() {
        retryAt = time.markNow()
    }

    /** The display got a new video or played successfully: the automatic-retry budget starts over. */
    @Synchronized
    fun resetAutoRetries() {
        autoRetries = 0
        nextAutoRetry = jittered(firstAutoRetry)
    }

    /**
     * Shows an error that was held back once its cooldown is over, and returns true when an automatic
     * retry is due now (the caller performs it and must call [markRetry]).
     */
    @Synchronized
    fun tick(): Boolean {
        deferred?.let { pending ->
            if (!inCooldown()) {
                deferred = null
                show(pending)
            }
        }
        val error = current ?: return false
        if (!isRecoverable(error) || autoRetries >= maxAutoRetries) return false
        val shown = shownAt ?: return false
        if (shown.elapsedNow() < nextAutoRetry) return false
        autoRetries++
        nextAutoRetry = jittered(firstAutoRetry * (1 shl autoRetries.coerceAtMost(4)).toDouble())
        return true
    }

    private fun show(value: DreamMediaException) {
        if (current == null) shownAt = time.markNow()
        current = value
    }

    private fun inCooldown(): Boolean = retryAt?.elapsedNow()?.let { it < cooldown } == true

    private fun jittered(base: Duration): Duration = base * (0.7 + random.nextDouble() * 0.6)

    companion object {
        /** Errors worth retrying unattended: passing network trouble, never a verdict on the link. */
        fun isRecoverable(error: DreamMediaException): Boolean =
            !error.isFatal && (error.kind == MediaFailureKind.NETWORK || error.kind == MediaFailureKind.TIMEOUT)
    }
}
