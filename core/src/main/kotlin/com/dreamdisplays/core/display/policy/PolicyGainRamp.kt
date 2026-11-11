package com.dreamdisplays.core.display.policy

/**
 * Client side of `audio.gain`: moves the applied gain to a new target linearly over [rampNanos] instead of in one
 * step, so a change (karaoke starting or ending) never clicks. Times are [System.nanoTime] values.
 */
class PolicyGainRamp(private val rampNanos: Long = DEFAULT_RAMP_NANOS, initial: Double = 1.0) {
    private var from = initial
    private var to = initial
    private var startNanos = 0L
    private var lastPolled = initial

    /** The gain to target: 1 while this player sings on the display (singers hear the track as normal). */
    val target: Double @Synchronized get() = to

    /** Starts ramping from the current value to [target] (clamped 0..2); no-op when it already heads there. */
    @Synchronized
    fun retarget(target: Double, nowNanos: Long) {
        val t = target.coerceIn(0.0, 2.0)
        if (t == to) return
        from = value(nowNanos)
        to = t
        startNanos = nowNanos
    }

    /** The gain to apply at [nowNanos]. */
    @Synchronized
    fun value(nowNanos: Long): Double {
        if (from == to) return to
        val progress = (nowNanos - startNanos).toDouble() / rampNanos
        if (progress >= 1.0 || rampNanos <= 0) {
            from = to
            return to
        }
        return from + (to - from) * progress.coerceAtLeast(0.0)
    }

    /** True when [value] moved since the last poll, so the volume needs re-applying. */
    @Synchronized
    fun pollChanged(nowNanos: Long): Boolean {
        val v = value(nowNanos)
        if (v == lastPolled) return false
        lastPolled = v
        return true
    }

    companion object {
        const val DEFAULT_RAMP_NANOS = 300_000_000L

        /** The gain a player applies to a display with [policy]: none while they sing on it, else `audio.gain`. */
        fun targetFor(policy: DisplayPolicy?, singing: Boolean): Double =
            if (singing) 1.0 else policy?.audio?.resolvedGain ?: 1.0
    }
}
