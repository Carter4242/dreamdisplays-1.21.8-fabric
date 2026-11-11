package com.dreamdisplays.core.karaoke

/**
 * How loud a singer hears the other singers of a duet while a song plays: [Levels.singing] (`co_singer_volume`)
 * while they sing themselves, so the others' late voices don't throw them off, and [Levels.idle]
 * (`co_singer_idle_volume`) once they have sent no voice for [Levels.idleAfterMs]. Full volume between songs.
 */
object KaraokeCoSinger {
    data class Levels(
        val singing: Double = 0.15,
        val idle: Double = 0.075,
        val idleAfterMs: Long = 3_000L,
        val fadeDownMs: Long = 200L,
        val fadeUpMs: Long = 500L,
    )

    /**
     * The volume a hearing singer whose last voice frame arrived at [hearerLastVoiceNanos] (null: none yet) should
     * get the others at, at [nowNanos].
     */
    fun target(songPlaying: Boolean, hearerLastVoiceNanos: Long?, nowNanos: Long, levels: Levels): Double {
        val singing = hearerLastVoiceNanos != null && nowNanos - hearerLastVoiceNanos <= levels.idleAfterMs * 1_000_000L
        return KaraokeRouting.coSingerVolume(songPlaying, if (singing) levels.singing else levels.idle)
    }

    /**
     * One (hearer, other singer) stream's gain, faded linearly to each new target: down over [Levels.fadeDownMs],
     * up over [Levels.fadeUpMs]. [next] gives each frame's start and end gain so it is ramped sample by sample.
     * Not thread-safe; callers hold their own lock.
     */
    class Ramp(initial: Double? = null) {
        private var from = initial ?: Double.NaN
        private var to = from
        private var startNanos = 0L
        private var durationNanos = 0L
        private var lastValue = from
        private var lastNanos = 0L

        /** The gain at [nowNanos]. */
        fun value(nowNanos: Long): Double {
            if (from == to || durationNanos <= 0L) return to
            val progress = (nowNanos - startNanos).toDouble() / durationNanos
            if (progress >= 1.0) {
                from = to
                return to
            }
            return from + (to - from) * progress.coerceAtLeast(0.0)
        }

        /**
         * Heads to [target] (fading from where it is now) and returns the gain at the start and end of the frame
         * at [nowNanos]. The first frame, and the first after a gap (a new burst: nothing playing to click), starts
         * at the end value.
         */
        fun next(target: Double, nowNanos: Long, levels: Levels): Pair<Double, Double> {
            if (to.isNaN()) {
                from = target
                to = target
            } else if (target != to) {
                val current = value(nowNanos)
                from = current
                to = target
                startNanos = nowNanos
                durationNanos = (if (target < current) levels.fadeDownMs else levels.fadeUpMs).coerceAtLeast(0L) * 1_000_000L
            }
            val end = value(nowNanos)
            val start = if (lastValue.isNaN() || nowNanos - lastNanos > GAP_NANOS) end else lastValue
            lastValue = end
            lastNanos = nowNanos
            return start to end
        }
    }

    /** Longer than this between frames is a new burst. */
    const val GAP_NANOS = 100_000_000L
}
