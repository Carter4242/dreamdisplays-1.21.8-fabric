package com.dreamdisplays.media.player.managers

/**
 * How often a broken audio half may be restarted in place: up to [maxRestarts] faults spaced less than [windowNanos]
 * apart. A fault that keeps coming back (an expired URL, a bug hit on every chunk) then goes to the full session
 * recovery instead of looping. Pure; one caller thread.
 */
internal class AudioFaultBudget(
    private val maxRestarts: Int = 3,
    private val windowNanos: Long = 10_000_000_000L,
) {
    private var streak = 0
    private var lastNanos = 0L

    /** Records a fault at [nowNanos]; true while an in-place restart is still allowed for it. */
    fun allowRestart(nowNanos: Long): Boolean {
        streak = if (lastNanos != 0L && nowNanos - lastNanos < windowNanos) streak + 1 else 1
        lastNanos = nowNanos
        return streak <= maxRestarts
    }

    fun reset() {
        streak = 0
        lastNanos = 0L
    }
}

/** What a broken audio half of a playing session gets (see `MediaPlayer.handleAudioFault`). */
internal object AudioFaultRoute {
    enum class Route {
        /** Restarted in place at the playhead. */
        RESTART_IN_PLACE,

        /** A live stream's sound reopened at its live edge, the picture playing on. */
        RESTART_LIVE,

        /** The usual audio-failure path (reported; a recurring fault ends in a session restart). */
        FAILURE_PATH,
    }

    /**
     * [deviceChanged]: the game's sound output moved to another device; nothing broke, so it never counts against
     * [budgetAllows] and is never reported, live or near the end alike: the sound just reopens on the new device (a
     * player clicking through the game's device list changes it several times in a few seconds).
     */
    fun of(deviceChanged: Boolean, live: Boolean, nearEnd: Boolean, noAudioStream: Boolean, budgetAllows: () -> Boolean): Route =
        when {
            noAudioStream -> Route.FAILURE_PATH
            deviceChanged -> if (live) Route.RESTART_LIVE else Route.RESTART_IN_PLACE
            live || nearEnd || !budgetAllows() -> Route.FAILURE_PATH
            else -> Route.RESTART_IN_PLACE
        }
}
