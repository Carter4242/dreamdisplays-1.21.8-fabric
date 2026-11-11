package com.dreamdisplays.core.karaoke

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KaraokeVoiceGainTest {
    @Test
    fun `unity gain is a pass through`() {
        assertTrue(KaraokeVoiceGain.isUnity(1.0))
        val pcm = shortArrayOf(0, 1_000, -32_768, 32_767)
        KaraokeVoiceGain.apply(pcm, 1.0)
        assertContentEquals(shortArrayOf(0, 1_000, -32_768, 32_767), pcm)
    }

    @Test
    fun `quiet and normal singing is boosted exactly`() {
        assertEquals(1_400, KaraokeVoiceGain.sample(1_000, 1.4).toInt())
        assertEquals(-14_000, KaraokeVoiceGain.sample(-10_000, 1.4).toInt())
        assertEquals(150, KaraokeVoiceGain.sample(1_000, 0.15).toInt())
    }

    @Test
    fun `loud peaks are limited smoothly and never clip`() {
        var last = -1
        for (x in 0..32_767 step 64) {
            val pos = KaraokeVoiceGain.sample(x.toShort(), 1.4).toInt()
            val neg = KaraokeVoiceGain.sample((-x).toShort(), 1.4).toInt()
            assertTrue(pos in 0..32_767 && pos >= last, "x=$x -> $pos")
            assertEquals(-pos, neg, "symmetric at $x")
            last = pos
        }
        // Full-scale input stays below full scale at 1.4x and at the 3x maximum
        assertTrue(KaraokeVoiceGain.sample(32_767, 1.4) < 32_767)
        assertTrue(KaraokeVoiceGain.sample(Short.MIN_VALUE, 3.0) > Short.MIN_VALUE)
        // Continuous at the knee: no step where the limiter takes over
        val knee = (KaraokeVoiceGain.KNEE * 32_767 / 1.4).toInt()
        val below = KaraokeVoiceGain.sample((knee - 1).toShort(), 1.4).toInt()
        val above = KaraokeVoiceGain.sample((knee + 1).toShort(), 1.4).toInt()
        assertTrue(above - below in 1..4, "$below -> $above")
    }
}
