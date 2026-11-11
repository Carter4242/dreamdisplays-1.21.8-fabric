package com.dreamdisplays.core.queue

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DurationReportsTest {
    private val url = "https://youtu.be/a"

    private fun accepts(ms: Long, reportUrl: String = url, reportGen: Long = 7, sinceChange: Long = 10_000) =
        DurationReports.accepts(ms, reportUrl, reportGen, url, currentGen = 7, msSinceVideoChange = sinceChange)

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
}
