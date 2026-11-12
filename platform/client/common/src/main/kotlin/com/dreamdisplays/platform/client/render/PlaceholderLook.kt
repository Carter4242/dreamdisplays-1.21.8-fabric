package com.dreamdisplays.platform.client.render

import com.dreamdisplays.platform.client.displays.DisplayFace
import kotlin.math.sin

/**
 * The screen drawn while no picture is up: a dark backdrop with a bar near the bottom. Loading animates it (the
 * backdrop breathes, a blue segment sweeps the track); a display without a video shows the same look frozen, the
 * segment resting in the middle; an error is a red backdrop and track with no segment.
 */
internal object PlaceholderLook {
    const val TRACK_X0 = 0.06f
    const val TRACK_X1 = 0.94f
    const val SEGMENT_WIDTH = 0.28f
    private const val SWEEP_PERIOD_NANOS = 1_300_000_000L
    private const val BREATHE_PERIOD_SECONDS = 2.0

    /** Gray level of the backdrop when it does not breathe: the middle of the loading breath (8..14). */
    const val IDLE_SHADE = 11

    /** Gray level of a non-error backdrop at [nanos]. */
    fun backdropShade(face: DisplayFace, nanos: Long): Int {
        if (face != DisplayFace.LOADING) return IDLE_SHADE
        val breathe = (sin(nanos / 1_000_000_000.0 / BREATHE_PERIOD_SECONDS * 2.0 * Math.PI).toFloat() + 1f) * 0.5f
        return (8 + breathe * 6f).toInt()
    }

    /**
     * The blue segment's span on the track at [nanos] as `[x0, x1]`, or null when none shows: an error (or a picture),
     * or the instant the sweep is outside the track.
     */
    fun segment(face: DisplayFace, nanos: Long): FloatArray? = when (face) {
        DisplayFace.IDLE -> floatArrayOf(0.5f - SEGMENT_WIDTH / 2f, 0.5f + SEGMENT_WIDTH / 2f)
        DisplayFace.LOADING -> {
            val phase = (Math.floorMod(nanos, SWEEP_PERIOD_NANOS)).toFloat() / SWEEP_PERIOD_NANOS
            val travel = (TRACK_X1 - TRACK_X0) + SEGMENT_WIDTH
            val start = TRACK_X0 - SEGMENT_WIDTH + travel * phase
            val x0 = start.coerceIn(TRACK_X0, TRACK_X1)
            val x1 = (start + SEGMENT_WIDTH).coerceIn(TRACK_X0, TRACK_X1)
            if (x1 > x0) floatArrayOf(x0, x1) else null
        }
        DisplayFace.ERROR, DisplayFace.PLAYING -> null
    }
}
