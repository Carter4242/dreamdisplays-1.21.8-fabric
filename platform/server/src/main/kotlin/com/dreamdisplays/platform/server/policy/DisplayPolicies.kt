package com.dreamdisplays.platform.server.policy

import com.dreamdisplays.api.display.data.DisplayCustomDataLimits
import com.dreamdisplays.core.display.policy.DisplayPolicy
import com.dreamdisplays.core.karaoke.KaraokeTrackDuck
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import org.slf4j.LoggerFactory

/**
 * Server side of the display policy channel.
 *
 * The effective policy of a display is its own override (stored in its persisted custom data under
 * [DisplayPolicy.KEY]) merged over the server-wide defaults from the `[policy.*]` config tables. Every
 * `DisplayInfo` carries the effective policy under that same key ([wireCustomData]), so clients never see the
 * raw override and pick up config changes as soon as displays are re-sent (e.g. after `/display reload`).
 * Fields that only work server-wide (most of `client`, all of `debug`) are left out of `DisplayInfo` and reach
 * clients through [ClientDiagnostics] instead.
 */
object DisplayPolicies {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Server-wide defaults; the platform points this at its live config. */
    @Volatile
    var defaults: () -> DisplayPolicy = { DisplayPolicy.EMPTY }

    /** Karaoke track volume for [display] while it has singers (`audio.gain`), else null; set by the platform. */
    @Volatile
    var trackVolume: (DisplayData) -> Double? = { null }

    /** Persists [DisplayData] and re-sends it to viewers after an override change; set by the platform. */
    @Volatile
    var onOverrideChanged: (DisplayData) -> Unit = {}

    /** The display's own override, or null when it has none (or what is stored is unreadable). */
    fun override(display: DisplayData): DisplayPolicy? = DisplayPolicy.decode(display.customData[DisplayPolicy.KEY])

    /** What clients are told for [display]: its override merged over the server defaults. */
    fun effective(display: DisplayData): DisplayPolicy {
        val merged = (override(display) ?: DisplayPolicy.EMPTY).mergedOver(defaults())
        val track = trackVolume(display) ?: return merged
        return KaraokeTrackDuck.apply(merged, singing = true, trackVolume = track)
    }

    /**
     * Sets (or with a null [value], clears) one override field of [display], e.g. `sync.deadBandMs` = 30,
     * then persists and re-sends the display. Returns false, changing nothing, for an unknown path or a
     * value of the wrong type.
     */
    fun setOverride(display: DisplayData, path: String, value: Any?): Boolean {
        val updated = DisplayPolicy.withField(override(display), path, value) ?: return false
        return setOverride(display, updated)
    }

    /**
     * Replaces the whole override of [display] with [updated] (an empty policy removes it), then persists and
     * re-sends the display. Returns false, changing nothing, if it would not fit the custom-data limits.
     */
    fun setOverride(display: DisplayData, updated: DisplayPolicy): Boolean {
        if (updated.isEmpty) {
            display.customData.remove(DisplayPolicy.KEY)
        } else {
            val bytes = updated.encode()
            if (!DisplayCustomDataLimits.accepts(display.customData, DisplayPolicy.KEY, bytes)) return false
            display.customData[DisplayPolicy.KEY] = bytes
        }
        onOverrideChanged(display)
        return true
    }

    /** Removes every override of [display], then persists and re-sends it. */
    fun clearOverride(display: DisplayData) {
        if (display.customData.remove(DisplayPolicy.KEY) != null) onOverrideChanged(display)
    }

    /**
     * The custom data to put in a `DisplayInfo` for [display]: the stored entries with the policy key carrying
     * the effective policy (omitted when it sets nothing). Falls back to the stored data unchanged if adding
     * the policy would break the custom-data limits, since clients drop all custom data that does.
     */
    fun wireCustomData(display: DisplayData): Map<String, ByteArray> {
        val stored = display.customData.toMap()
        val policy = runCatching { effective(display).forDisplay() }.getOrElse {
            logger.warn("Display policy for {} could not be built.", display.id, it)
            return stored
        }
        if (policy.isEmpty) return stored - DisplayPolicy.KEY
        val wire = stored + (DisplayPolicy.KEY to policy.encode())
        if (DisplayCustomDataLimits.isValid(wire)) return wire
        logger.warn("Display {} custom data has no room for its policy; sending it without.", display.id)
        return stored - DisplayPolicy.KEY
    }
}
