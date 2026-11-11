package com.dreamdisplays.core.karaoke

import com.dreamdisplays.core.display.policy.DisplayPolicy
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class KaraokeTrackDuckTest {
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()

    @Test
    fun `displays are re-sent only when their first singer comes or their last goes`() {
        val duck = KaraokeTrackDuck()
        assertEquals(emptySet(), duck.update(emptySet()))
        assertEquals(setOf(a), duck.update(setOf(a)))     // first singer on a
        assertEquals(emptySet(), duck.update(setOf(a)))   // a second singer / no change
        assertEquals(setOf(b), duck.update(setOf(a, b)))
        assertEquals(setOf(a), duck.update(setOf(b)))     // last singer left a
        assertEquals(setOf(b), duck.update(emptySet()))
        assertEquals(emptySet(), duck.update(emptySet()))
    }

    @Test
    fun `clear forgets ducked displays`() {
        val duck = KaraokeTrackDuck()
        duck.update(setOf(a))
        duck.clear()
        assertEquals(setOf(a), duck.update(setOf(a)))
    }

    @Test
    fun `gain is the track volume while singing and absent otherwise`() {
        val base = DisplayPolicy(audio = DisplayPolicy.Audio(radiusBlocks = 32.0))
        assertEquals(0.8, KaraokeTrackDuck.apply(base, singing = true, trackVolume = 0.8).audio?.gain)
        assertEquals(32.0, KaraokeTrackDuck.apply(base, singing = true, trackVolume = 0.8).audio?.radiusBlocks)
        assertSame(base, KaraokeTrackDuck.apply(base, singing = false, trackVolume = 0.8))
        assertNull(KaraokeTrackDuck.apply(base, singing = true, trackVolume = 1.0).audio?.gain)
        assertEquals(0.0, KaraokeTrackDuck.apply(base, singing = true, trackVolume = -3.0).audio?.gain)
        assertEquals(1.0, KaraokeTrackDuck.DEFAULT_TRACK_VOLUME)
        assertSame(base, KaraokeTrackDuck.apply(base, singing = true, trackVolume = KaraokeTrackDuck.DEFAULT_TRACK_VOLUME))
    }
}
