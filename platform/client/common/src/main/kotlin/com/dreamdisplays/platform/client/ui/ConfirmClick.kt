package com.dreamdisplays.platform.client.ui

/** Two-click confirmation: the first click arms for [windowMs], a second click inside the window confirms. */
class ConfirmClick(private val windowMs: Long = 3_000L) {
    private var armedUntilMs = 0L

    fun isArmed(nowMs: Long): Boolean = nowMs < armedUntilMs

    /** Returns true when this click confirms; otherwise it arms (or re-arms after a timeout). */
    fun click(nowMs: Long): Boolean {
        if (isArmed(nowMs)) {
            armedUntilMs = 0L
            return true
        }
        armedUntilMs = nowMs + windowMs
        return false
    }

    fun disarm() {
        armedUntilMs = 0L
    }
}
