package com.dreamdisplays.platform.client.ui.widgets

/**
 * A two-click guard for a button whose action reaches everyone: the first click arms it, a second within
 * [windowMs] runs the action. A stray click only arms it, and the arming lapses on its own.
 */
class ArmedClick(private val windowMs: Long) {
    private var armedUntilMs = 0L

    /** A click at [nowMs]: true when it confirms (run the action now), false when it only armed the guard. */
    fun press(nowMs: Long): Boolean {
        if (nowMs < armedUntilMs) {
            armedUntilMs = 0L
            return true
        }
        armedUntilMs = nowMs + windowMs
        return false
    }

    /** True while a second click would confirm. */
    fun isArmed(nowMs: Long): Boolean = nowMs < armedUntilMs

    /** Drops a pending arming (the button went away). */
    fun disarm() {
        armedUntilMs = 0L
    }
}
