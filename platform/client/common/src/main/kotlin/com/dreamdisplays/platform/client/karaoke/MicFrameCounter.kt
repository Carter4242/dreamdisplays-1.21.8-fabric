package com.dreamdisplays.platform.client.karaoke

/**
 * Numbers Simple Voice Chat's mic frames the way it does, from its add-on events alone: it numbers every frame it sends
 * and every end-of-burst (stop) packet, from 0 per mic thread. A stop goes out on the first poll that sends nothing
 * after frames were sent ([onPoll] sees no [onFrame] since the last one), and also when voice chat is disabled
 * mid-burst: its mic thread then sends the stop without a poll and polls nothing until re-enabled, so the first poll
 * after such a gap counts it when voice chat was seen disabled meanwhile ([onDisabledSeen]).
 *
 * Mic-thread only, except [onDisabledSeen].
 */
class MicFrameCounter {
    private var nextSeq = 0L
    private var hasSent = false
    private var sentSincePoll = false
    private var lastPollNanos = 0L

    @Volatile
    private var disabledSeen = false

    /** The frame [onFrame] last numbered starts a new burst. */
    var newBurst = false
        private set

    /** Any thread: voice chat is disabled right now. */
    fun onDisabledSeen() {
        disabledSeen = true
    }

    /** Voice chat polled one mic frame at [nowNanos] (every 20 ms of mic audio, sent or not). */
    fun onPoll(nowNanos: Long) {
        val idle = nowNanos - lastPollNanos > IDLE_GAP_NANOS
        lastPollNanos = nowNanos
        if (hasSent && (!sentSincePoll || idle && disabledSeen)) {
            nextSeq++
            hasSent = false
        }
        if (idle) disabledSeen = false
        sentSincePoll = false
    }

    /** Voice chat sends a mic frame now: its number. */
    fun onFrame(): Long {
        newBurst = !hasSent
        hasSent = true
        sentSincePoll = true
        return nextSeq++
    }

    /** A new mic thread numbers from 0 again. */
    fun reset() {
        nextSeq = 0L
        hasSent = false
        sentSincePoll = false
        newBurst = false
    }

    private companion object {
        /** Longer than this between polls (normally 20 ms apart) and the mic thread was idle. */
        const val IDLE_GAP_NANOS = 60_000_000L
    }
}
