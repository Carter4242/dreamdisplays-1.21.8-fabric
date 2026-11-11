package com.dreamdisplays.core.karaoke

/**
 * Which of a singer's mic frames one re-encoding stream (decode -> gain -> encode) takes. Opus decoders and encoders
 * carry state from frame to frame, so they must see a burst in order: a frame that arrives after a newer one of the
 * same burst (reordered or duplicated on the way) is dropped, and the receiver conceals one missing frame, where
 * coding it late would garble the frames after it too. A new burst ([BurstAnchor]'s id) starts the codecs afresh.
 *
 * Not thread-safe; the caller synchronises.
 */
class KaraokeCodecOrder {
    enum class Admit {
        /** The next frame of the current burst: code it. */
        TAKE,

        /** The first frame of a newer burst: reset the codecs, then code it. */
        NEW_BURST,

        /** Late or repeated: not coded and not sent. */
        DROP,
    }

    private var burst = Int.MIN_VALUE
    private var lastSeq = Long.MIN_VALUE

    fun admit(burstId: Int, seq: Long): Admit = when {
        burstId < burst -> Admit.DROP
        burstId > burst -> {
            burst = burstId
            lastSeq = seq
            Admit.NEW_BURST
        }
        seq <= lastSeq -> Admit.DROP
        else -> {
            lastSeq = seq
            Admit.TAKE
        }
    }
}
