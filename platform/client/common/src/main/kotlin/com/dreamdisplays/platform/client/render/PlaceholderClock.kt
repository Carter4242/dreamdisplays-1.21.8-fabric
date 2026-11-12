package com.dreamdisplays.platform.client.render

import com.dreamdisplays.platform.client.displays.DisplayFace
import com.dreamdisplays.platform.client.displays.DisplayScreen
import java.util.WeakHashMap

/**
 * One display's placeholder animation time, so the bar never jumps between looks.
 *
 * Loading advances it with real time, starting from wherever it is: from an idle display that is the resting look, so
 * the first loading frame is the frozen bar. A display without a video lets each motion run on to its next rest (the
 * segment back to the middle, the breath to its middle shade) and stops there, so a cleared display settles into the
 * frozen bar instead of snapping to it. An error or a picture hides the bar and puts it back at rest, as does a gap of
 * more than [STALE_GAP_NANOS] between frames (the display was not drawn). One frame moves it by at most
 * [MAX_STEP_NANOS], so a hitch (setting a video often costs a frame or two) slows the bar instead of making it skip.
 */
internal class PlaceholderClock {
    /** Sweep animation time, within one sweep period. */
    var sweepNanos = 0L
        private set

    /** Breath animation time, within one breath period. */
    var breatheNanos = 0L
        private set

    private var lastNanos = 0L
    private var lastFace: DisplayFace? = null

    /**
     * Advances to real time [nanos] for a display showing [face]. The first loading frame after any other look holds
     * the current time (the resting look when coming from an idle display); motion counts from that frame.
     */
    fun advance(face: DisplayFace, nanos: Long) {
        val previous = lastFace
        val dt = when {
            previous == null -> Long.MAX_VALUE
            face == DisplayFace.LOADING && previous != DisplayFace.LOADING -> 0L
            else -> nanos - lastNanos
        }
        val step = dt.coerceAtMost(MAX_STEP_NANOS)
        lastFace = face
        lastNanos = nanos
        if (face == DisplayFace.PLAYING || face == DisplayFace.ERROR || dt !in 0..STALE_GAP_NANOS) {
            sweepNanos = 0L
            breatheNanos = 0L
            return
        }
        if (face == DisplayFace.LOADING) {
            sweepNanos = (sweepNanos + step) % PlaceholderLook.SWEEP_PERIOD_NANOS
            breatheNanos = (breatheNanos + step) % PlaceholderLook.BREATHE_PERIOD_NANOS
        } else {
            sweepNanos = settle(sweepNanos, step, PlaceholderLook.SWEEP_REST_NANOS) % PlaceholderLook.SWEEP_PERIOD_NANOS
            breatheNanos = settle(breatheNanos, step, PlaceholderLook.BREATHE_REST_NANOS) % PlaceholderLook.BREATHE_PERIOD_NANOS
        }
    }

    /** True when both motions are at rest: the frozen idle look. */
    val atRest: Boolean
        get() = sweepNanos % PlaceholderLook.SWEEP_REST_NANOS == 0L &&
                breatheNanos % PlaceholderLook.BREATHE_REST_NANOS == 0L

    companion object {
        const val STALE_GAP_NANOS = 1_000_000_000L

        /** Most animation time one frame adds: real time at 20 fps and above. */
        const val MAX_STEP_NANOS = 50_000_000L

        /** [t] advanced by [dt] but not past the next multiple of [rest]; a time already at rest stays. */
        fun settle(t: Long, dt: Long, rest: Long): Long {
            if (t % rest == 0L) return t
            return minOf(t + dt, (t / rest + 1) * rest)
        }

        private val clocks = WeakHashMap<DisplayScreen, PlaceholderClock>()

        /** [screen]'s clock advanced to [nanos] for [face] (render thread). */
        fun of(screen: DisplayScreen, face: DisplayFace, nanos: Long): PlaceholderClock =
            clocks.getOrPut(screen) { PlaceholderClock() }.also { it.advance(face, nanos) }
    }
}
