package com.dreamdisplays.core.queue

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DurationReportsTest {
    private val url = "https://youtu.be/a"

    private fun accepts(
        ms: Long, reportUrl: String = url, reportGen: Long = 7, sinceChange: Long = 10_000, previousMs: List<Long> = [],
    ) = DurationReports.accepts(ms, reportUrl, reportGen, url, currentGen = 7, msSinceVideoChange = sinceChange, previousMs)

    @Test
    fun `a short clip's real length counts`() {
        assertTrue(accepts(1_500))
        assertTrue(accepts(500))
        assertFalse(accepts(499))
        assertFalse(accepts(25L * 60 * 60 * 1_000))
    }

    @Test
    fun `a report about another video is ignored`() {
        assertFalse(accepts(60_000, reportUrl = "https://youtu.be/b"))
        assertFalse(accepts(60_000, reportGen = 6))
    }

    @Test
    fun `a report that names its video counts right after a change`() {
        assertTrue(accepts(60_000, sinceChange = 10))
        assertTrue(accepts(60_000, reportUrl = "", reportGen = 7, sinceChange = 10))
    }

    @Test
    fun `an unnamed report right after a change with the previous video's length was that video's`() {
        val previous = listOf(60_000L, 59_400L)
        assertFalse(accepts(60_000, reportUrl = "", reportGen = 0, sinceChange = 100, previousMs = previous))
        assertFalse(accepts(60_900, reportUrl = "", reportGen = 0, sinceChange = 0, previousMs = previous))
        assertFalse(accepts(58_500, reportUrl = "", reportGen = 0, sinceChange = 249, previousMs = previous))
        assertTrue(accepts(60_000, reportUrl = "", reportGen = 0, sinceChange = 250, previousMs = previous))
        assertTrue(accepts(60_000, reportUrl = "", reportGen = 0, sinceChange = Long.MAX_VALUE, previousMs = previous))
    }

    @Test
    fun `an unnamed report right after a change is the new video's when the previous one's length was other or unknown`() {
        // A protocol-11 client that resolved the link before opens it and reports within tens of ms, and only once
        assertTrue(accepts(15_000, reportUrl = "", reportGen = 0, sinceChange = 49))
        assertTrue(accepts(15_000, reportUrl = "", reportGen = 0, sinceChange = 49, previousMs = [0L]))
        assertTrue(accepts(15_000, reportUrl = "", reportGen = 0, sinceChange = 49, previousMs = [10_000L, 9_909L]))
    }
}
