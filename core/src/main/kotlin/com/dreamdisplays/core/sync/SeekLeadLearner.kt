package com.dreamdisplays.core.sync

import kotlin.math.abs

/**
 * Learns how far ahead to aim a seek / start of one kind so it lands on the live point (the stream's restart cost).
 *
 * Each landing implies a cost: the lead it was aimed with plus how far behind it landed. A cost near the current lead
 * nudges it (bounded step, so one slow seek can't throw the next off). A cost far from it (a reopen seek taking 1.8 s
 * where 0.4 s was expected) is only taken once another recent landing agrees within [AGREE_MS], or when it is the
 * landing of a re-aim (the episode's last seek, accepted as is). Landings around a game stall over [MAX_STALL_MS] say
 * nothing about the cost; one tens of seconds off is a new target (stale target, other video), not a sample.
 */
class SeekLeadLearner(initialLeadMs: Long, private val maxLeadMs: Long) {
    enum class Verdict { LEARNED, UNCONFIRMED, STALLED, NEW_TARGET }

    @Volatile
    var leadMs: Long = initialLeadMs.coerceIn(0L, maxLeadMs)
        private set

    private val recentCosts = ArrayDeque<Double>()

    /** The cost implied by a landing [landingDriftMs] off after a seek aimed [leadUsedMs] ahead, clamped to the cap. */
    fun impliedLeadMs(leadUsedMs: Long, landingDriftMs: Double): Long =
        (leadUsedMs + landingDriftMs).toLong().coerceIn(0L, maxLeadMs)

    @Synchronized
    fun onLanding(leadUsedMs: Long, landingDriftMs: Double, stallMs: Double, isReaim: Boolean = false): Verdict {
        if (isNewTarget(landingDriftMs)) return Verdict.NEW_TARGET
        if (stallMs > MAX_STALL_MS) return Verdict.STALLED
        val cost = leadUsedMs + landingDriftMs
        val agreeing = recentCosts.filter { abs(it - cost) <= AGREE_MS }
        recentCosts.addLast(cost)
        while (recentCosts.size > WINDOW) recentCosts.removeFirst()
        val off = cost - leadMs
        leadMs = when {
            agreeing.isNotEmpty() && abs(off) > MAX_STEP_MS -> (agreeing + cost).average().toLong()
            abs(off) <= MAX_PLAIN_OFF_MS -> leadMs + (off * GAIN).toLong().coerceIn(-MAX_STEP_MS, MAX_STEP_MS)
            isReaim -> leadMs + (off * GAIN).toLong()
            else -> return Verdict.UNCONFIRMED
        }.coerceIn(0L, maxLeadMs)
        return Verdict.LEARNED
    }

    companion object {
        /** Landings whose implied costs are this close agree with each other. */
        const val AGREE_MS = 300.0

        /** How many recent landings are kept to look for agreement. */
        const val WINDOW = 3

        /** A cost this far from the lead is only learned when confirmed (or a re-aim's). */
        const val MAX_PLAIN_OFF_MS = 1_000.0

        /** Largest unconfirmed step. */
        const val MAX_STEP_MS = 500L

        const val GAIN = 0.7

        /** A game stall longer than this around a seek makes its landing meaningless for the lead. */
        const val MAX_STALL_MS = 250.0

        /** A landing further off than this is a new target, never a restart cost. */
        const val NEW_TARGET_MS = 5_000.0

        fun isNewTarget(landingDriftMs: Double): Boolean = abs(landingDriftMs) > NEW_TARGET_MS
    }
}
