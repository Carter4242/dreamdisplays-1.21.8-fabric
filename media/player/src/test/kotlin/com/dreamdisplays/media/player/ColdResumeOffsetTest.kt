package com.dreamdisplays.media.player

import kotlin.test.Test
import kotlin.test.assertEquals

class ColdResumeOffsetTest {
    @Test
    fun `a first play after the first open was skipped for a pause opens at the primed clock position`() {
        assertEquals(15_000L, coldResumeOffsetNanos(ended = false, everStarted = false, primedNanos = 15_000L, originNanos = 0L))
    }

    @Test
    fun `nothing primed, a stream that ran before, or one that ended keep their usual start`() {
        assertEquals(0L, coldResumeOffsetNanos(ended = false, everStarted = false, primedNanos = -1L, originNanos = 0L))
        assertEquals(7_000L, coldResumeOffsetNanos(ended = false, everStarted = true, primedNanos = 15_000L, originNanos = 7_000L))
        assertEquals(0L, coldResumeOffsetNanos(ended = true, everStarted = false, primedNanos = 15_000L, originNanos = 7_000L))
    }
}
