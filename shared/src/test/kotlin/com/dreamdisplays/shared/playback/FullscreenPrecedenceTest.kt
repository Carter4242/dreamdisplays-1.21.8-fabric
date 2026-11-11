package com.dreamdisplays.shared.playback

import com.dreamdisplays.shared.playback.FullscreenPrecedence.Candidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FullscreenPrecedenceTest {
    private val older = Candidate("a", 1_000)
    private val newer = Candidate("b", 2_000)

    @Test
    fun `a player entering a newer session's radius is taken over from the older one`() {
        assertEquals("b", FullscreenPrecedence.pick(listOf(older, newer), watching = "a"))
    }

    @Test
    fun `an older session never takes a player from a newer one`() {
        assertEquals("b", FullscreenPrecedence.pick(listOf(newer, older), watching = "b"))
    }

    @Test
    fun `after the session they watched stops, the newest remaining one gets them, whatever the order`() {
        val oldest = Candidate("c", 500)
        assertEquals("b", FullscreenPrecedence.pick(listOf(oldest, newer, older), watching = null))
        assertEquals("b", FullscreenPrecedence.pick(listOf(older, oldest, newer), watching = null))
    }

    @Test
    fun `sessions started together keep the one being watched`() {
        val restoredA = Candidate("a", 0)
        val restoredB = Candidate("b", 0)
        assertEquals("b", FullscreenPrecedence.pick(listOf(restoredA, restoredB), watching = "b"))
        assertEquals("a", FullscreenPrecedence.pick(listOf(restoredB, restoredA), watching = null))
    }

    @Test
    fun `no targeting session picks nothing`() {
        assertNull(FullscreenPrecedence.pick(emptyList(), watching = "a"))
    }
}
