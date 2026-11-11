package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import java.util.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerMediaGensTest {
    @Test
    fun `a timeline from an older server never asks for the display`() {
        assertFalse(timelineNeedsDescription(0L, null))
        assertFalse(timelineNeedsDescription(0L, 7L))
    }

    @Test
    fun `a timeline for the described video is followed as is`() {
        assertFalse(timelineNeedsDescription(7L, 7L))
    }

    @Test
    fun `a timeline for another video asks for the display`() {
        assertTrue(timelineNeedsDescription(8L, 7L))
    }

    @Test
    fun `a timeline for a display never described asks for it`() {
        assertTrue(timelineNeedsDescription(8L, null))
    }

    private val id = UUID(9, 9)

    @AfterTest
    fun cleanUp() = ServerMediaGens.reset()

    @Test
    fun `talk about the video on screen carries its generation while the next one waits to start`() {
        ServerMediaGens.onInfo(DisplayInfo(id, url = "a", mediaGen = 7L))
        assertEquals(7L, ServerMediaGens.onScreen(id))
        // The queue moved on: B is described, but A plays until B's scheduled start
        assertTrue(ServerMediaGens.onInfo(DisplayInfo(id, url = "b", mediaGen = 8L)))
        ServerMediaGens.hold(id, ServerMediaGens.beforeLatest(id))
        assertEquals(7L, ServerMediaGens.onScreen(id))
        assertEquals(8L, ServerMediaGens.of(id)) // descriptions are still asked about by the latest one
        ServerMediaGens.release(id)
        assertEquals(8L, ServerMediaGens.onScreen(id))
    }

    @Test
    fun `a switch replaced before it happened still leaves the first video's generation`() {
        ServerMediaGens.onInfo(DisplayInfo(id, url = "a", mediaGen = 7L))
        ServerMediaGens.onInfo(DisplayInfo(id, url = "b", mediaGen = 8L))
        ServerMediaGens.hold(id, ServerMediaGens.beforeLatest(id))
        ServerMediaGens.onInfo(DisplayInfo(id, url = "c", mediaGen = 9L))
        val shown = ServerMediaGens.beforeLatest(id) // read before the B switch is let go
        ServerMediaGens.release(id)
        ServerMediaGens.hold(id, shown)
        assertEquals(7L, ServerMediaGens.onScreen(id))
    }

    @Test
    fun `nothing held means the described generation, and forgetting clears the hold`() {
        assertEquals(0L, ServerMediaGens.onScreen(id))
        ServerMediaGens.onInfo(DisplayInfo(id, url = "a", mediaGen = 7L))
        ServerMediaGens.onInfo(DisplayInfo(id, url = "b", mediaGen = 8L))
        ServerMediaGens.hold(id, 7L)
        ServerMediaGens.forget(id)
        assertEquals(0L, ServerMediaGens.onScreen(id))
    }
}
