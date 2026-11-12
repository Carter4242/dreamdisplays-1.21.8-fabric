package com.dreamdisplays.platform.server.policy

import com.dreamdisplays.api.display.data.DisplayCustomDataLimits
import com.dreamdisplays.core.display.policy.PushedDefaults
import com.dreamdisplays.core.display.policy.PushedSetting
import com.dreamdisplays.platform.server.datatypes.display.DisplayData

/**
 * Server side of `/display defaults`: the [PushedDefaults] of a display, kept in its persisted custom data under
 * [PushedDefaults.KEY]. That entry travels unchanged in every `DisplayInfo` ([DisplayPolicies.wireCustomData] sends
 * the stored entries), so a change is saved and re-sent like a policy override ([DisplayPolicies.onOverrideChanged]).
 */
object DisplayDefaults {
    /** What is pushed on [display] (empty when nothing ever was, or what is stored is unreadable). */
    fun of(display: DisplayData): PushedDefaults =
        PushedDefaults.decode(display.customData[PushedDefaults.KEY]) ?: PushedDefaults.EMPTY

    /**
     * Pushes [setting] = canonical [value] on [display] at a new revision, then saves and re-sends it. Returns the new
     * state, or null (nothing changed) when it would not fit the custom-data limits.
     */
    fun push(display: DisplayData, setting: PushedSetting, value: String, nowMillis: Long = System.currentTimeMillis()): PushedDefaults? {
        val next = of(display).push(setting, value, nowMillis)
        return next.takeIf { store(display, it) }
    }

    /** Stops pushing [setting] (all when null) on [display]; false when it was not pushed. */
    fun clear(display: DisplayData, setting: PushedSetting?): Boolean {
        val current = of(display)
        val next = current.clear(setting)
        if (next == current) return false
        return store(display, next)
    }

    private fun store(display: DisplayData, next: PushedDefaults): Boolean {
        // The revision is kept even with nothing pushed, so a later push is still newer than any before it
        val bytes = next.encode()
        if (!DisplayCustomDataLimits.accepts(display.customData, PushedDefaults.KEY, bytes)) return false
        display.customData[PushedDefaults.KEY] = bytes
        DisplayPolicies.onOverrideChanged(display)
        return true
    }
}
