package com.dreamdisplays.core.protocol.common

/**
 * Decides when a client should send its `ClientHello` again after the first handshake, so a server that lost
 * track of it (its plugin was reloaded while the player stayed online) picks it back up without a relog.
 *
 * Two triggers:
 * - **Unsolicited server hello.** A `ServerHello` that is not the answer to a hello sent moments ago means the
 *   server is asking who is there (a freshly enabled server greets everyone listening on the channel): answer it.
 * - **Silence.** The server sends display updates about every second while displays are in range. When the
 *   client has displays loaded but nothing at all has arrived for [silenceNanos], the server has probably
 *   forgotten it: say hello again, backing off (doubling from [silenceNanos]) for at most [maxAttempts] tries.
 *   A server that still knows the client ignores the extra hello, so a false alarm costs one small packet.
 *
 * Any server packet resets the backoff. All times are monotonic nanos supplied by the caller; not thread-safe.
 */
class HelloRefresh(
    private val silenceNanos: Long = 15_000_000_000L,
    private val answerWindowNanos: Long = 10_000_000_000L,
    private val maxAttempts: Int = 5,
) {
    private var lastPacketNanos = Long.MIN_VALUE
    private var lastHelloNanos = Long.MIN_VALUE
    private var attempts = 0

    /** Number of silence-triggered hellos sent since the server last spoke. */
    val silenceAttempts: Int get() = attempts

    /** Records that a hello was just sent (the initial one or any refresh). */
    fun onHelloSent(now: Long) {
        lastHelloNanos = now
    }

    /**
     * Records a packet from the server. Returns true when it is an unsolicited `ServerHello` on an already
     * negotiated connection, i.e. the caller should answer with a hello now.
     */
    fun onPacket(now: Long, isServerHello: Boolean, alreadyNegotiated: Boolean): Boolean {
        lastPacketNanos = now
        attempts = 0
        if (!isServerHello || !alreadyNegotiated) return false
        if (lastHelloNanos != Long.MIN_VALUE && now - lastHelloNanos < answerWindowNanos) return false
        lastHelloNanos = now
        return true
    }

    /**
     * Called periodically. Returns true when a hello should be sent because the server has gone silent while
     * [displaysLoaded]. Silence only counts while displays are loaded and the connection is [negotiated].
     */
    fun tick(now: Long, negotiated: Boolean, displaysLoaded: Boolean): Boolean {
        if (!negotiated || !displaysLoaded || lastPacketNanos == Long.MIN_VALUE) {
            // Nothing is expected from the server right now: start measuring silence from here
            lastPacketNanos = now
            return false
        }
        if (attempts >= maxAttempts) return false
        if (now - lastPacketNanos < silenceNanos) return false
        val wait = silenceNanos shl attempts.coerceAtMost(20)
        if (lastHelloNanos != Long.MIN_VALUE && now - lastHelloNanos < wait) return false
        attempts++
        lastHelloNanos = now
        return true
    }

    /** Forgets everything (disconnect). */
    fun reset() {
        lastPacketNanos = Long.MIN_VALUE
        lastHelloNanos = Long.MIN_VALUE
        attempts = 0
    }
}
