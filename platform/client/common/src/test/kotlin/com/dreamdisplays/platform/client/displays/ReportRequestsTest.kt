package com.dreamdisplays.platform.client.displays

import java.util.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportRequestsTest {
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()

    @Test
    fun `answers once per display and kind within the interval`() {
        val answered = HashMap<Pair<UUID, Int>, Long>()
        assertTrue(mayAnswer(answered, a, 1, nowMs = 10_000, intervalMs = 1_000))
        assertFalse(mayAnswer(answered, a, 1, nowMs = 10_500, intervalMs = 1_000))
        assertTrue(mayAnswer(answered, a, 2, nowMs = 10_500, intervalMs = 1_000))
        assertTrue(mayAnswer(answered, b, 1, nowMs = 10_500, intervalMs = 1_000))
        assertTrue(mayAnswer(answered, a, 1, nowMs = 11_000, intervalMs = 1_000))
    }

    @Test
    fun `a flood of distinct displays cannot grow the record without bound`() {
        val answered = HashMap<Pair<UUID, Int>, Long>()
        repeat(10_000) { mayAnswer(answered, UUID.randomUUID(), 1, nowMs = 1L, intervalMs = 1_000) }
        assertTrue(answered.size <= 4_097)
    }
}
