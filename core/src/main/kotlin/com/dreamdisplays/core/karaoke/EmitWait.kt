package com.dreamdisplays.core.karaoke

/**
 * When the karaoke voice sender runs a frame due at some `System.nanoTime()`. Every receiver buffers voice chat audio
 * (100 ms by default) and starts a burst from its first frame, so a send a millisecond early is inaudible: everything
 * due within [DUE_EARLY_NANOS] goes out in the same wake, and only the last [SPIN_NANOS] before that are spun (a
 * timed wait can overshoot). Spinning milliseconds per send kept a core busy once a few listeners had different delays.
 */
object EmitWait {
    /** A frame this close to its time is sent now. */
    const val DUE_EARLY_NANOS = 1_000_000L

    /** Waits shorter than this are spun rather than slept. */
    const val SPIN_NANOS = 250_000L

    /** True when a frame due at [atNanos] goes out at [nowNanos]. */
    fun due(atNanos: Long, nowNanos: Long): Boolean = atNanos - nowNanos <= DUE_EARLY_NANOS

    /** How long to sleep before a frame due at [atNanos] (then spin the rest); 0 = spin (or send) now. */
    fun sleepNanos(atNanos: Long, nowNanos: Long): Long =
        (atNanos - DUE_EARLY_NANOS - SPIN_NANOS - nowNanos).coerceAtLeast(0L)
}
