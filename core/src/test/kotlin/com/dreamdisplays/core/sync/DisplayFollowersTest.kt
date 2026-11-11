package com.dreamdisplays.core.sync

import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayFollowersTest {
    private val near = UUID(0, 1)
    private val fullscreen = UUID(0, 2)
    private val pinner = UUID(0, 3)
    private val both = UUID(0, 4)

    @Test
    fun `far fullscreen followers get the description forced, players in range get none from here`() {
        val targets = DisplayFollowers.infoTargets(nearby = listOf(near), fullscreen = listOf(near, fullscreen), pinners = emptyList())
        assertEquals(mapOf(fullscreen to true), targets)
    }

    @Test
    fun `pinners get the description unforced, unless they also follow a fullscreen session`() {
        val targets = DisplayFollowers.infoTargets(
            nearby = listOf(near), fullscreen = listOf(both), pinners = listOf(near, pinner, both),
        )
        assertEquals(mapOf(pinner to false, both to true), targets)
    }

    @Test
    fun `far receivers are every follower out of range, once each`() {
        val far = DisplayFollowers.farReceivers(
            nearby = listOf(near), fullscreen = listOf(fullscreen, both, near), pinners = listOf(both, pinner),
        )
        assertEquals(listOf(fullscreen, both, pinner), far)
    }

    @Test
    fun `nobody far means nothing extra is sent`() {
        assertEquals(emptyMap(), DisplayFollowers.infoTargets(listOf(near), emptyList(), emptyList()))
        assertEquals(emptyList(), DisplayFollowers.farReceivers(listOf(near), emptyList(), emptyList()))
    }

    @Test
    fun `far followers' duration reports count, strangers' don't`() {
        val stranger = UUID(0, 9)
        assertTrue(DisplayFollowers.mayReportDuration(near, listOf(near), emptyList(), emptyList()))
        assertTrue(DisplayFollowers.mayReportDuration(fullscreen, emptyList(), listOf(fullscreen), emptyList()))
        assertTrue(DisplayFollowers.mayReportDuration(pinner, emptyList(), emptyList(), listOf(pinner)))
        assertFalse(DisplayFollowers.mayReportDuration(stranger, listOf(near), listOf(fullscreen), listOf(pinner)))
    }

    @Test
    fun `a broadcast nobody stands near is still watched, a pin alone does not keep it running`() {
        assertTrue(DisplayFollowers.watched(nearby = emptyList(), fullscreen = listOf(fullscreen)))
        assertTrue(DisplayFollowers.watched(nearby = listOf(near), fullscreen = emptyList()))
        assertFalse(DisplayFollowers.watched(nearby = emptyList(), fullscreen = emptyList()))
    }
}
