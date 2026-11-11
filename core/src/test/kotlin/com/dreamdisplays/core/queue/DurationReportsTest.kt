package com.dreamdisplays.core.queue

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DurationReportsTest {
    private val url = "https://youtu.be/a"

    private fun accepts(ms: Long, reportUrl: String = url, reportGen: Long = 7, sinceChange: Long = 10_000) =
        DurationReports.accepts(ms, reportUrl, reportGen, url, currentGen = 7, msSinceVideoChange = sinceChange)

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
    fun `an unnamed report right after a change was the previous video's`() {
        assertFalse(accepts(60_000, reportUrl = "", reportGen = 0, sinceChange = 100))
        assertTrue(accepts(60_000, reportUrl = "", reportGen = 0, sinceChange = 250))
        assertTrue(accepts(60_000, reportUrl = "", reportGen = 0, sinceChange = Long.MAX_VALUE))
    }
}
