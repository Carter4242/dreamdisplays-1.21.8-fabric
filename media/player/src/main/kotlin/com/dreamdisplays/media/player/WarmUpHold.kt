package com.dreamdisplays.media.player

/**
 * Parks a warm-up session ([MediaPlayer.warmUpPaused]) the instant its playout begins, on the pipeline thread, at the
 * position it was opened for. Parking from the control thread instead (after the first-frame callback is scheduled
 * there) let it play on in the meantime and landed late by that delay times the playback speed: 800 ms at 2x on a
 * slow machine, enough for the follower to snap it and resume cold, or for the held position to miss the server's
 * readiness tolerance.
 *
 * The control thread [arm]s it for each warm-up session and [disarm]s it whenever the warm-up ends (held, played,
 * paused, stopped); the two sides are serialized, so a play racing the first frame never leaves the session parked
 * unseen. Thread-safe.
 */
internal class WarmUpHold {
    private val lock = Any()
    private var target = NONE
    private var held = false

    /** The warm-up session about to start opens at [targetNanos]: park it there when its playout begins. */
    fun arm(targetNanos: Long): Unit = synchronized(lock) {
        target = targetNanos.coerceAtLeast(0L)
        held = false
    }

    /**
     * Pipeline thread, as playout begins: parks the session through [park] at the armed target position, never at
     * wherever the clock got to; true when it is now held. Does nothing (false) unless armed, and only once per arm.
     */
    fun onPlayoutStart(park: (atNanos: Long) -> Boolean): Boolean = synchronized(lock) {
        val at = target
        if (at == NONE) return false
        target = NONE
        held = park(at)
        held
    }

    /** Control thread: the warm-up is over; true when [onPlayoutStart] parked its session (the caller now owns that). */
    fun disarm(): Boolean = synchronized(lock) {
        target = NONE
        held.also { held = false }
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
