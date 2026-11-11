package com.dreamdisplays.media.player.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioSinkEndKindTest {
    @Test
    fun `an exception in the pump is a fault whether or not PCM flowed`() {
        val npe = NullPointerException()
        assertEquals(AudioSink.EndKind.FAULT, AudioSink.endKind(npe, pumped = false, exitCode = null))
        assertEquals(AudioSink.EndKind.FAULT, AudioSink.endKind(npe, pumped = true, exitCode = 0))
    }

    @Test
    fun `FFmpeg exiting non-zero mid-stream is a fault, a clean exit is a plain end`() {
        assertEquals(AudioSink.EndKind.FAULT, AudioSink.endKind(null, pumped = true, exitCode = -22))
        assertEquals(AudioSink.EndKind.FAULT, AudioSink.endKind(null, pumped = true, exitCode = 1))
        assertEquals(AudioSink.EndKind.ENDED, AudioSink.endKind(null, pumped = true, exitCode = 0))
        assertEquals(AudioSink.EndKind.ENDED, AudioSink.endKind(null, pumped = true, exitCode = null))
    }

    @Test
    fun `a half that never pumped and did not fail reports nothing`() {
        assertNull(AudioSink.endKind(null, pumped = false, exitCode = null))
    }

    @Test
    fun `a natural end waits for the line's queued sound, bounded`() {
        val bps = 44_100 * 4
        assertEquals(0L, AudioSink.tailPlayOutBudgetNanos(0, bps))
        val fiftyMs = bps / 20
        assertTrue(AudioSink.tailPlayOutBudgetNanos(fiftyMs, bps) >= 50_000_000L, "The last 50 ms would be cut.")
        assertTrue(AudioSink.tailPlayOutBudgetNanos(bps * 10, bps) <= 1_000_000_000L, "A broken line must not hold the end.")
    }
}
