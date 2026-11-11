package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.core.protocol.common.packets.KaraokeClockPing
import com.dreamdisplays.core.share.ShareTimeline

/**
 * This client's view of the server's clock, as the server measured it from the karaoke clock pings it sends sharers
 * and their viewers ([KaraokeClockPing.clientOffsetNanos]). Screen share timestamps and their presentation times
 * meet on it (see [ShareTimeline]).
 */
object ShareClock {
    /** This client's `System.nanoTime` minus the server's, or null before the server knew it. */
    @Volatile
    var offsetNanos: Long? = null
        private set

    fun onPing(ping: KaraokeClockPing) {
        if (ping.clientOffsetKnown) offsetNanos = ping.clientOffsetNanos
    }

    /** Disconnected: the next server has its own clock. */
    fun reset() {
        offsetNanos = null
    }

    /** The server's clock now (µs), or null while unknown. */
    fun serverMicrosNow(): Long? = offsetNanos?.let { ShareTimeline.serverMicros(System.nanoTime(), it) }
}
