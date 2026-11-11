package com.dreamdisplays.core.karaoke

import com.dreamdisplays.core.display.policy.DisplayPolicy
import java.util.*

/**
 * Server side of the karaoke track volume: while a display has singers its policy carries `audio.gain` =
 * `[karaoke] track_volume`, and `audio.singerGain` = `singer_track_volume`, which singers' clients use instead on the
 * display they sing on. Tracks which displays are
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

        const val DEFAULT_SINGER_TRACK_VOLUME = 1.25

        /**
         * [policy] with the track gains applied when [singing]: `audio.gain` = [trackVolume] when it lowers the
         * track, and `audio.singerGain` = [singerTrackVolume] (0..2) for the singers when it isn't 1.
         */
        fun apply(
            policy: DisplayPolicy, singing: Boolean, trackVolume: Double,
            singerTrackVolume: Double = 1.0,
        ): DisplayPolicy {
            if (!singing) return policy
            val volume = trackVolume.coerceIn(0.0, 1.0)
            val ducked = if (volume >= 1.0) policy else policy.withGain(volume)
            val singer = singerTrackVolume.coerceIn(0.0, 2.0)
            return if (singer == 1.0) ducked else ducked.withSingerGain(singer)
        }
    }
}
