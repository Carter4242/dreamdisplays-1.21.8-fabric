package com.dreamdisplays.core.sync

import com.dreamdisplays.shared.playback.ReadinessRoster
import com.dreamdisplays.shared.playback.ReadinessState
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
    fun `a display nobody stands near is still watched from fullscreen or PiP`() {
        assertTrue(DisplayFollowers.watched(nearby = emptyList(), fullscreen = listOf(fullscreen)))
        assertTrue(DisplayFollowers.watched(nearby = listOf(near), fullscreen = emptyList()))
        assertTrue(DisplayFollowers.watched(nearby = emptyList(), fullscreen = emptyList(), pinViewers = listOf(pinner)))
        assertFalse(DisplayFollowers.watched(nearby = emptyList(), fullscreen = emptyList(), pinViewers = emptyList()))
    }

    @Test
    fun `a far fullscreen audience counts at a start even with nobody in range`() {
        assertEquals(listOf(fullscreen), DisplayFollowers.audience(nearby = emptyList(), fullscreen = listOf(fullscreen)))
    }

    @Test
    fun `the audience is everyone in range and every fullscreen follower once each`() {
        assertEquals(listOf(near, both, fullscreen), DisplayFollowers.audience(nearby = listOf(near, both), fullscreen = listOf(both, fullscreen)))
    }

    @Test
    fun `PiP viewers are the audience like players in range, once each`() {
        assertEquals(listOf(pinner), DisplayFollowers.audience(nearby = emptyList(), fullscreen = emptyList(), pinViewers = listOf(pinner)))
        assertEquals(
            listOf(near, fullscreen, pinner),
            DisplayFollowers.audience(nearby = listOf(near), fullscreen = listOf(fullscreen), pinViewers = listOf(near, pinner, fullscreen)),
        )
    }

    @Test
    fun `a pinner whose client reports the display absent no longer watches it`() {
        val stale = UUID(0, 5)
        val showing = DisplayFollowers.showing(listOf(pinner, stale)) { it == stale }
        assertEquals(listOf(pinner), showing)
    }

    @Test
    fun `a PiP viewer may act from anywhere, a stranger only from range`() {
        val stranger = UUID(0, 9)
        assertTrue(DisplayFollowers.present(pinner, inRange = false, pinViewers = listOf(pinner)))
        assertTrue(DisplayFollowers.present(near, inRange = true, pinViewers = emptyList()))
        assertFalse(DisplayFollowers.present(stranger, inRange = false, pinViewers = listOf(pinner)))
    }

    @Test
    fun `a start with only a PiP audience waits for it until it is ready, never for a stale pin`() {
        val display = UUID(1, 1)
        val stale = UUID(0, 5)
        val roster = ReadinessRoster()
        roster.report(display, stale, ReadinessState.ABSENT, 0L, 0)
        val viewers = DisplayFollowers.showing(listOf(pinner, stale)) { roster.reportOf(display, it)?.state == ReadinessState.ABSENT }
        val audience = DisplayFollowers.audience(nearby = emptyList(), fullscreen = emptyList(), pinViewers = viewers)
        assertEquals(listOf(pinner), roster.waitingFor(display, 30_000L, audience))
        roster.report(display, pinner, ReadinessState.READY, 30_000L, 20)
        assertEquals(emptyList(), roster.waitingFor(display, 30_000L, audience))
        // The stale pin is not waited for even when it is passed in (a hold asks it; its client answers absent)
        assertEquals(emptyList(), roster.waitingFor(display, 30_000L, listOf(pinner, stale)))
    }
}
