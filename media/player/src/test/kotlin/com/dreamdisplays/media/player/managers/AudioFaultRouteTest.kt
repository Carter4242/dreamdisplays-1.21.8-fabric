package com.dreamdisplays.media.player.managers

import com.dreamdisplays.media.player.managers.AudioFaultRoute.Route
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioFaultRouteTest {
    private val s = 1_000_000_000L

    private fun route(
        deviceChanged: Boolean = false, live: Boolean = false, nearEnd: Boolean = false, noAudio: Boolean = false,
        budget: AudioFaultBudget = AudioFaultBudget(), now: Long = 1L,
    ) = AudioFaultRoute.of(deviceChanged, live, nearEnd, noAudio) { budget.allowRestart(now) }

    @Test
    fun `clicking through the game's sound devices never runs out the fault budget`() {
        val budget = AudioFaultBudget(maxRestarts = 3, windowNanos = 10 * s)
        for (i in 1..8) assertEquals(Route.RESTART_IN_PLACE, route(deviceChanged = true, budget = budget, now = i * s))
        // The budget is untouched: a real fault right after still restarts in place
        assertEquals(Route.RESTART_IN_PLACE, route(budget = budget, now = 9 * s))
    }

    @Test
    fun `a device change on a live stream or near a video's end reopens the sound instead of failing`() {
        assertEquals(Route.RESTART_LIVE, route(deviceChanged = true, live = true))
        assertEquals(Route.RESTART_IN_PLACE, route(deviceChanged = true, nearEnd = true))
    }

    @Test
    fun `real faults keep their usual routes`() {
        assertEquals(Route.FAILURE_PATH, route(live = true))
        assertEquals(Route.FAILURE_PATH, route(nearEnd = true))
        assertEquals(Route.FAILURE_PATH, route(noAudio = true))
        assertEquals(Route.FAILURE_PATH, route(deviceChanged = true, noAudio = true))
        val budget = AudioFaultBudget(maxRestarts = 3, windowNanos = 10 * s)
        repeat(3) { assertEquals(Route.RESTART_IN_PLACE, route(budget = budget, now = (it + 1) * s)) }
        assertEquals(Route.FAILURE_PATH, route(budget = budget, now = 4 * s))
    }
}
