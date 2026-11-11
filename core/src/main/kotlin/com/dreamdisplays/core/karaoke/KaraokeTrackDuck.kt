package com.dreamdisplays.core.karaoke

import com.dreamdisplays.core.display.policy.DisplayPolicy
import java.util.*

/**
 * Server side of the karaoke track volume: while a display has singers its policy carries `audio.gain` =
 * `[karaoke] track_volume` (singers' clients ignore it on the display they sing on). Tracks which displays are
 * ducked so their info is re-sent only when that flips.
 */
class KaraokeTrackDuck {
    private val ducked = HashSet<UUID>()

    /** Records the displays that have singers now and returns those whose ducking changed. */
    @Synchronized
    fun update(active: Set<UUID>): Set<UUID> {
        val changed = (active - ducked) + (ducked - active)
        ducked.clear()
        ducked.addAll(active)
        return changed
    }

    @Synchronized
    fun clear() = ducked.clear()

    companion object {
        const val DEFAULT_TRACK_VOLUME = 1.0

        /** [policy] with the track gain applied when [singing] (and [trackVolume] actually lowers it). */
        fun apply(policy: DisplayPolicy, singing: Boolean, trackVolume: Double): DisplayPolicy {
            if (!singing) return policy
            val volume = trackVolume.coerceIn(0.0, 1.0)
            return if (volume >= 1.0) policy else policy.withGain(volume)
        }
    }
}
