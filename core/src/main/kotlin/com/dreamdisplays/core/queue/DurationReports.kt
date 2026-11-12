package com.dreamdisplays.core.queue

import com.dreamdisplays.core.protocol.common.packets.MediaGen

/** Which client duration reports (`ReportDuration`) a display's timeline takes. */
object DurationReports {
    /**
     * Shortest length taken. Anything a viewer can watch counts; the floor only stops a junk report from restarting a
     * display several times a second (the largest report wins anyway, so a real length overrides a short one).
     */
    const val MIN_MS = 500L

    const val MAX_MS = 24L * 60 * 60 * 1_000

    /**
     * A report naming neither URL nor media generation (protocol-11 clients) that arrives this soon after the
     * display's video changed may have been measured on the previous video: it was on its way when the change happened.
     * It may as well be the new video's: a client that resolved that link before opens it again in well under this.
     */
    const val LEGACY_STALE_WINDOW_MS = 250L

    /** An unnamed report in [LEGACY_STALE_WINDOW_MS] this close (ms) to a length known for the previous video was that video's. */
    const val LEGACY_STALE_MATCH_MS = 1_000L

    /**
     * True when a report of [durationMs] for [reportUrl] / [reportGen] (empty / 0: not named) is about the display's
     * current video ([currentUrl] / [currentGen]), which changed [msSinceVideoChange] ago. [previousMs]: the lengths
     * known for the video it played before (settled and reported); an unnamed report right after the change is only
     * taken for that video's when it matches one of them. Protocol-11 clients report a video once, so a report dropped
     * here is never sent again: dropping one on the new video would leave the display without its length for good.
     */
    fun accepts(
        durationMs: Long, reportUrl: String, reportGen: Long, currentUrl: String, currentGen: Long, msSinceVideoChange: Long,
        previousMs: Collection<Long> = emptyList(),
    ): Boolean {
        if (durationMs !in MIN_MS..MAX_MS) return false
        if (reportUrl.isNotEmpty() && reportUrl != currentUrl) return false
        if (!MediaGen.matches(reportGen, currentGen)) return false
        val named = reportUrl.isNotEmpty() || reportGen != 0L
        if (named || msSinceVideoChange !in 0 until LEGACY_STALE_WINDOW_MS) return true
        return previousMs.none { it > 0L && kotlin.math.abs(it - durationMs) <= LEGACY_STALE_MATCH_MS }
    }
}
