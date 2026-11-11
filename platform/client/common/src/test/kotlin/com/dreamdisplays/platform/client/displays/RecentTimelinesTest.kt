package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.core.protocol.common.packets.DisplaySync
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecentTimelinesTest {
    private val id = UUID.randomUUID()
    private val url = "https://example.com/a.mp4"

    @Test
    fun `a display back in range replays the last timeline as a start that happened that long ago`() {
        val playing = DisplaySync(id, currentTimeMs = 15_000, serverTimeMs = 1_000, mediaGen = 4)
        val replayed = replayedTimeline(playing, url, ageMs = 2_400.0, url = url, mediaGen = 4)!!
        assertEquals(15_000, replayed.currentTimeMs)
        assertEquals(-2_400, replayed.startInMs)
        assertEquals(1_000, replayed.serverTimeMs) // The server's own answer, stamped later, still wins
        val paused = playing.copy(isPaused = true)
        assertEquals(paused, replayedTimeline(paused, url, 9_000.0, url, 4))
    }

    @Test
    fun `a timeline of another video or one too old is not replayed`() {
        val playing = DisplaySync(id, currentTimeMs = 15_000, serverTimeMs = 1_000, mediaGen = 4)
        assertNull(replayedTimeline(playing, url, 1_000.0, "https://example.com/b.mp4", 4))
        assertNull(replayedTimeline(playing, url, 1_000.0, url, 5))
        assertNull(replayedTimeline(playing, url, RecentTimelines.MAX_AGE_MS + 1, url, 4))
        // Older servers stamp no generation: the URL alone decides
        assertEquals(-1_000, replayedTimeline(playing.copy(mediaGen = 0), url, 1_000.0, url, 0)!!.startInMs)
    }

    @Test
    fun `a stream that opened at its own start is corrected before it is shown, one opened where primed is not`() {
        assertTrue(joinIsFarOff(driftMs = 15_000.0, coldLeadMs = 600))
        assertTrue(joinIsFarOff(driftMs = -4_000.0, coldLeadMs = 600))
        assertFalse(joinIsFarOff(driftMs = -650.0, coldLeadMs = 600))
        assertFalse(joinIsFarOff(driftMs = 400.0, coldLeadMs = 600))
    }
}
