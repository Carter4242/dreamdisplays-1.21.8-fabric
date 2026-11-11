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
     * display's video changed was measured on the previous video: it was on its way when the change happened. A
     * client could not have opened the new video and measured it in that time.
     */
    const val LEGACY_STALE_WINDOW_MS = 250L

    /**
     * True when a report of [durationMs] for [reportUrl] / [reportGen] (empty / 0: not named) is about the display's
     * current video ([currentUrl] / [currentGen]), which changed [msSinceVideoChange] ago.
     */
    fun accepts(
        durationMs: Long, reportUrl: String, reportGen: Long, currentUrl: String, currentGen: Long, msSinceVideoChange: Long,
    ): Boolean {
        if (durationMs !in MIN_MS..MAX_MS) return false
        if (reportUrl.isNotEmpty() && reportUrl != currentUrl) return false
        if (!MediaGen.matches(reportGen, currentGen)) return false
        val named = reportUrl.isNotEmpty() || reportGen != 0L
        return named || msSinceVideoChange !in 0 until LEGACY_STALE_WINDOW_MS
    }
}
