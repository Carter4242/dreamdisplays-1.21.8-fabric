package com.dreamdisplays.platform.client.render

import kotlin.math.sin

/**
 * The screen drawn while no picture is up: a dark backdrop with a bar near the bottom. Loading animates it (the
 * backdrop breathes, a blue segment sweeps the track); a display without a video shows the same look at rest, the
 * segment in the middle and the backdrop at the middle of its breath; an error is a red backdrop and track with no
 * segment.
 *
 * Both motions are functions of an animation time ([PlaceholderClock]), and animation time 0 is the resting look, so
 * loading that starts from an idle display picks up exactly where the frozen bar is.
 */
internal object PlaceholderLook {
    const val TRACK_X0 = 0.06f
    const val TRACK_X1 = 0.94f
    const val SEGMENT_WIDTH = 0.28f
    const val SWEEP_PERIOD_NANOS = 1_300_000_000L
    const val BREATHE_PERIOD_NANOS = 2_000_000_000L

    /** The sweep is at rest (segment centred) at every multiple of this animation time. */
    const val SWEEP_REST_NANOS = SWEEP_PERIOD_NANOS

    /** The breath is at rest (mid shade) at every multiple of this animation time: each zero of its sine. */
    const val BREATHE_REST_NANOS = BREATHE_PERIOD_NANOS / 2

    /** Sweep phase (0..1 across the track and off it) at animation time 0: the segment centred on the track. */
    private const val REST_SWEEP_PHASE = 0.5

    /** Gray level of the backdrop at rest: the middle of the loading breath (8..14). */
    val IDLE_SHADE: Int = backdropShade(0L)

    /** Gray level of a non-error backdrop at breath animation time [breatheNanos]. */
    fun backdropShade(breatheNanos: Long): Int {
        val t = Math.floorMod(breatheNanos, BREATHE_PERIOD_NANOS).toDouble() / BREATHE_PERIOD_NANOS
        val breathe = (sin(t * 2.0 * Math.PI).toFloat() + 1f) * 0.5f
        return (8 + breathe * 6f).toInt()
    }

    /**
     * The blue segment's span on the track at sweep animation time [sweepNanos] as `[x0, x1]`, or null the instant
     * the sweep is outside the track. Time 0 is the segment resting in the middle.
     */
    fun segment(sweepNanos: Long): FloatArray? {
        val phase = (REST_SWEEP_PHASE + Math.floorMod(sweepNanos, SWEEP_PERIOD_NANOS).toDouble() / SWEEP_PERIOD_NANOS) % 1.0
        val travel = (TRACK_X1 - TRACK_X0) + SEGMENT_WIDTH
        val start = TRACK_X0 - SEGMENT_WIDTH + travel * phase.toFloat()
        val x0 = start.coerceIn(TRACK_X0, TRACK_X1)
        val x1 = (start + SEGMENT_WIDTH).coerceIn(TRACK_X0, TRACK_X1)
        return if (x1 > x0) floatArrayOf(x0, x1) else null
    }
}
