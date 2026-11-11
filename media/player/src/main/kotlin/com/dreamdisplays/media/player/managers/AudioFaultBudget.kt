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
