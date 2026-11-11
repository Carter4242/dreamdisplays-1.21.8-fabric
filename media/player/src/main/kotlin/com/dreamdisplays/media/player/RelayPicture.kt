package com.dreamdisplays.media.player

/**
 * When a live relay stream's picture may stay up. A relay stream may be someone's screen share, so its picture goes
 * from every surface the moment the stream ends: closed, at its end, reconnecting, errored. While the connection is
 * open and playing, a picture that does not change is still the share as it is (a still window sends no new frames)
 * and stays: the relay cuts a share's connections the moment the share ends, and the display's URL changes with it.
 */
internal object RelayPicture {
    /**
     * True when the picture must not be shown: the stream is not [connected] (ended, reconnecting, errored), has not
     * shown a frame yet ([lastFrameNanos] 0), or is [paused] and its last frame is older than [maxAgeNanos] (a paused
     * reader may not notice the relay cutting it, and nothing else would take its frame down).
     */
    fun stale(connected: Boolean, paused: Boolean, lastFrameNanos: Long, nowNanos: Long, maxAgeNanos: Long): Boolean = when {
        !connected -> true
        lastFrameNanos <= 0L -> true
        paused -> nowNanos - lastFrameNanos > maxAgeNanos
        else -> false
    }

    /**
     * What the stall watchdog counts as a relay stream making progress: a new frame or new sound, whichever came last.
     * A still picture with its sound still playing is a live connection, not a stall to reconnect from.
     */
    fun progressNanos(lastFrameNanos: Long, lastSoundNanos: Long): Long = maxOf(lastFrameNanos, lastSoundNanos)
}
