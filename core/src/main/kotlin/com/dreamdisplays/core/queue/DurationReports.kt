package com.dreamdisplays.core.queue

import com.dreamdisplays.core.protocol.common.packets.MediaGen

/** Which client duration reports (`ReportDuration`) a display's timeline takes. */
object DurationReports {
    /** Shortest length taken; bounds how often one bad report can restart a display for everyone. */
    const val MIN_MS = 2_000L

    const val MAX_MS = 24L * 60 * 60 * 1_000

    /**
     * True when a report of [durationMs] for [reportUrl] / [reportGen] (empty / 0: not named) is about the display's
     * current video ([currentUrl] / [currentGen]), which changed [msSinceVideoChange] ago (not used yet).
     */
    fun accepts(
        durationMs: Long, reportUrl: String, reportGen: Long, currentUrl: String, currentGen: Long,
        @Suppress("UNUSED_PARAMETER") msSinceVideoChange: Long,
    ): Boolean {
        if (durationMs !in MIN_MS..MAX_MS) return false
        if (reportUrl.isNotEmpty() && reportUrl != currentUrl) return false
        return MediaGen.matches(reportGen, currentGen)
    }
}
