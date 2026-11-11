package com.dreamdisplays.core.karaoke

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KaraokeCoSingerTest {
    private val ms = 1_000_000L
    private val levels = KaraokeCoSinger.Levels(
        singing = 0.03, idle = 0.075, idleAfterMs = 3_000, fadeDownMs = 200, fadeUpMs = 500,
    )

    @Test
    fun `singing hearer gets co_singer_volume, silent one the idle volume`() {
        val now = 10_000 * ms
        assertEquals(0.03, KaraokeCoSinger.target(true, now - 20 * ms, now, levels))
        assertEquals(0.075, KaraokeCoSinger.target(true, null, now, levels))
        assertEquals(0.075, KaraokeCoSinger.target(true, now - 5_000 * ms, now, levels))
        // Between songs everyone hears each other normally
        assertEquals(1.0, KaraokeCoSinger.target(false, now - 20 * ms, now, levels))
        assertEquals(1.0, KaraokeCoSinger.target(false, null, now, levels))
    }

    @Test
    fun `stays at co_singer_volume for the hold after the hearer stops`() {
        val last = 10_000 * ms
        assertEquals(0.03, KaraokeCoSinger.target(true, last, last + 400 * ms, levels))
        assertEquals(0.03, KaraokeCoSinger.target(true, last, last + 2_999 * ms, levels))
        assertEquals(0.03, KaraokeCoSinger.target(true, last, last + 3_000 * ms, levels))
        assertEquals(0.075, KaraokeCoSinger.target(true, last, last + 3_001 * ms, levels))
        assertEquals(0.075, KaraokeCoSinger.target(true, last, last + 3_000 * ms, levels.copy(idleAfterMs = 400)))
    }

    @Test
    fun `first frame starts at the target`() {
        val ramp = KaraokeCoSinger.Ramp()
        assertEquals(0.075 to 0.075, ramp.next(0.075, 0L, levels))
    }

    @Test
    fun `fades down over fade_down_ms in steps per frame`() {
        val ramp = KaraokeCoSinger.Ramp()
        var t = 0L
        ramp.next(0.075, t, levels)
        var prev = 0.075
        for (i in 1..10) {
            t += 20 * ms
            val (from, to) = ramp.next(0.03, t, levels)
            // Each frame continues exactly where the last ended: no step between frames
            assertEquals(prev, from, 1e-12)
            prev = to
            if (i >= 2) assertTrue(to > 0.03 && to < 0.075, "frame $i: $to")
        }
        // Started at 20 ms, so 200 ms later (frame 11) it is down
        t += 20 * ms
        assertEquals(0.03, ramp.next(0.03, t, levels).second, 1e-12)
        assertEquals(0.03 + (0.075 - 0.03) * 0.5, KaraokeCoSinger.Ramp(0.075).let {
            it.next(0.075, 0L, levels); it.next(0.03, 0L, levels); it.value(100 * ms)
        }, 1e-12)
    }

    @Test
    fun `fades up over fade_up_ms`() {
        val ramp = KaraokeCoSinger.Ramp(0.03)
        ramp.next(0.03, 0L, levels)
        ramp.next(0.075, 0L, levels)
        assertEquals(0.03 + 0.045 * 0.5, ramp.value(250 * ms), 1e-12)
        assertEquals(0.075, ramp.value(500 * ms), 1e-12)
    }

    @Test
    fun `reverses mid fade from where it is`() {
        val ramp = KaraokeCoSinger.Ramp(0.075)
        ramp.next(0.075, 0L, levels)
        ramp.next(0.03, 0L, levels)
        val mid = ramp.value(100 * ms)
        assertEquals(mid, ramp.next(0.075, 100 * ms, levels).second, 1e-12)
        assertEquals(mid + (0.075 - mid) * 0.5, ramp.value(350 * ms), 1e-12)
    }

    @Test
    fun `a new burst after a gap starts at the current value`() {
        val ramp = KaraokeCoSinger.Ramp()
        ramp.next(0.075, 0L, levels)
        ramp.next(0.03, 20 * ms, levels)
        val (from, to) = ramp.next(0.03, 1_000 * ms, levels)
        assertEquals(0.03, from)
        assertEquals(0.03, to)
    }

    @Test
    fun `ramped frame interpolates sample by sample`() {
        val pcm = ShortArray(4) { 10_000 }
        KaraokeVoiceGain.applyRamp(pcm, 1.0, 0.0)
        assertEquals(listOf<Short>(7_500, 5_000, 2_500, 0), pcm.toList())
        val flat = ShortArray(3) { 10_000 }
        KaraokeVoiceGain.applyRamp(flat, 0.5, 0.5)
        assertEquals(listOf<Short>(5_000, 5_000, 5_000), flat.toList())
    }
}
