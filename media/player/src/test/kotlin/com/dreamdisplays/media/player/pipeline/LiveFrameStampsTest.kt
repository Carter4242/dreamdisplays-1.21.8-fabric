package com.dreamdisplays.media.player.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveFrameStampsTest {
    @Test
    fun `stats lines read as frame number and microseconds`() {
        assertEquals(14L to 1_073_741_824_033_000L, LiveFrameStamps.parseLine("14 1073741824033 1/1000"))
        assertEquals(2L to 66_666L, LiveFrameStamps.parseLine("2 2 1/30"))
        assertNull(LiveFrameStamps.parseLine("garbage"))
        assertNull(LiveFrameStamps.parseLine("1 2 1/0"))
    }

    @Test
    fun `a shown frame is matched to its stamp, an unknown one is not`() {
        val s = LiveFrameStamps("test-display")
        listOf("0 1000 1/1000", "1 1033 1/1000").forEach { line -> LiveFrameStamps.parseLine(line)?.let { feed(s, it) } }
        s.onPresented(1, 555L)
        s.onPresented(7, 777L)
        assertEquals(listOf(1_033_000L to 555L), s.recent())
        // Frame 7's stamp arrives after it was shown (the stats file lags the picture): matched then
        feed(s, 7L to 1_233_000L)
        assertEquals(listOf(1_033_000L to 555L, 1_233_000L to 777L), s.recent())
    }

    private fun feed(s: LiveFrameStamps, stamp: Pair<Long, Long>) {
        val m = LiveFrameStamps::class.java.getDeclaredMethod("parse", String::class.java)
        m.isAccessible = true
        m.invoke(s, "${stamp.first} ${stamp.second / 1000} 1/1000")
    }
}
