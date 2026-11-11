package com.dreamdisplays.platform.client.karaoke

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MicFrameCounterTest {
    private val ms = 1_000_000L

    /** Voice chat's own count, as its mic thread numbers packets. */
    private class Wire {
        var next = 0L
        var sentAudio = false
        fun frame() = next++.also { sentAudio = true }
        fun stopIfNeeded() {
            if (sentAudio) next++
            sentAudio = false
        }
    }

    @Test
    fun aPollWithoutAFrameCountsTheStopPacket() {
        val counter = MicFrameCounter()
        val wire = Wire()
        var t = 0L
        repeat(3) { counter.onPoll(t); t += 20 * ms; assertEquals(wire.frame(), counter.onFrame()) }
        counter.onPoll(t); t += 20 * ms; wire.stopIfNeeded()
        counter.onPoll(t); t += 20 * ms
        counter.onPoll(t)
        assertEquals(wire.frame(), counter.onFrame())
        assertTrue(counter.newBurst)
    }

    @Test
    fun disablingVoiceChatMidBurstCountsItsStopPacket() {
        val counter = MicFrameCounter()
        val wire = Wire()
        var t = 0L
        repeat(5) { counter.onPoll(t); t += 20 * ms; assertEquals(wire.frame(), counter.onFrame()) }
        // Disabled: the mic thread flushes a stop without a poll, then polls nothing for 2 s
        wire.stopIfNeeded()
        counter.onDisabledSeen()
        t += 2_000 * ms
        // Re-enabled with push-to-talk still held
        counter.onPoll(t)
        assertEquals(wire.frame(), counter.onFrame())
        assertTrue(counter.newBurst)
        counter.onPoll(t + 20 * ms)
        assertEquals(wire.frame(), counter.onFrame())
        assertFalse(counter.newBurst)
    }

    @Test
    fun aStallWhileVoiceChatStaysEnabledCountsNothing() {
        val counter = MicFrameCounter()
        val wire = Wire()
        counter.onPoll(0)
        assertEquals(wire.frame(), counter.onFrame())
        // A 300 ms hitch: polls resume with the frames buffered meanwhile, no stop was sent
        counter.onPoll(300 * ms)
        assertEquals(wire.frame(), counter.onFrame())
        assertFalse(counter.newBurst)
    }

    @Test
    fun disabledWhileSilentCountsNothing() {
        val counter = MicFrameCounter()
        val wire = Wire()
        counter.onPoll(0)
        assertEquals(wire.frame(), counter.onFrame())
        counter.onPoll(20 * ms); wire.stopIfNeeded()
        counter.onDisabledSeen()
        wire.stopIfNeeded()
        counter.onPoll(3_000 * ms)
        assertEquals(wire.frame(), counter.onFrame())
    }
}
