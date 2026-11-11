package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.shared.playback.ReadinessState
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadinessReportTest {
    private fun state(
        held: Boolean = false, errored: Boolean = false, started: Boolean = true, holdable: Boolean = true,
        paused: Boolean = true, holds: Boolean = true,
    ) = readinessOf(held, errored, started, holdable, paused, holds)

    @Test
    fun `held is ready`() {
        assertEquals(ReadinessState.READY, state(held = true))
        assertEquals(ReadinessState.READY, state(held = true, holds = false))
    }

    @Test
    fun `what will never be held says so, so starts do not wait for it`() {
        assertEquals(ReadinessState.UNHOLDABLE, state(errored = true))
        assertEquals(ReadinessState.UNHOLDABLE, state(errored = true, started = false))
        assertEquals(ReadinessState.UNHOLDABLE, state(holdable = false)) // live / unseekable
        assertEquals(ReadinessState.UNHOLDABLE, state(holdable = false, paused = false))
        assertEquals(ReadinessState.UNHOLDABLE, state(holds = false)) // paused beyond the displays it holds
    }

    @Test
    fun `what may still become ready is not ready`() {
        assertEquals(ReadinessState.NOT_READY, state(started = false)) // loading: live / seekable not known yet
        assertEquals(ReadinessState.NOT_READY, state(started = false, holdable = false))
        assertEquals(ReadinessState.NOT_READY, state()) // held by a rule, still warming
        assertEquals(ReadinessState.NOT_READY, state(paused = false, holds = false)) // playing: a hold pauses it first
    }
}
