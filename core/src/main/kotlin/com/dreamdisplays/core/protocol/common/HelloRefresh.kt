package com.dreamdisplays.core.protocol.common

/**
 * Decides when a client should send its `ClientHello` again after the first handshake, so a server that lost
 * track of it (its plugin was reloaded while the player stayed online) picks it back up without a relog.
 *
 * Two triggers:
 * - **Server greeting.** A `ServerHello` marked `greeting` is the server asking who is there (a freshly enabled
 *   plugin greets everyone listening on the channel): always answer it, negotiated or not, at most once per
 *   [greetingGapNanos]. Older servers do not mark greetings: a `ServerHello` on a negotiated connection that is not
 *   the answer to a hello sent moments ago is taken as one.
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
    private val greetingGapNanos: Long = 2_000_000_000L,
) {
    private var lastPacketNanos = Long.MIN_VALUE
    private var lastHelloNanos = Long.MIN_VALUE
    private var lastGreetingAnsweredNanos = Long.MIN_VALUE
    private var attempts = 0

    /** Number of silence-triggered hellos sent since the server last spoke. */
    val silenceAttempts: Int get() = attempts

    /** Records that a hello was just sent (the initial one or any refresh). */
    fun onHelloSent(now: Long) {
        lastHelloNanos = now
    }

    /**
     * Records a packet from the server. Returns true when the caller should answer with a hello now: a `ServerHello`
     * marked as a [greeting], or (from servers that do not mark them) an unsolicited one on an already negotiated
     * connection.
     */
    fun onPacket(now: Long, isServerHello: Boolean, alreadyNegotiated: Boolean, greeting: Boolean = false): Boolean {
        lastPacketNanos = now
        attempts = 0
        if (isServerHello && greeting) {
            // Bounded, so a server that wrongly greets in reply to every hello costs one hello per gap, not a loop
            if (lastGreetingAnsweredNanos != Long.MIN_VALUE && now - lastGreetingAnsweredNanos < greetingGapNanos) return false
            lastGreetingAnsweredNanos = now
            lastHelloNanos = now
            return true
        }
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
        lastGreetingAnsweredNanos = Long.MIN_VALUE
        attempts = 0
    }
}
