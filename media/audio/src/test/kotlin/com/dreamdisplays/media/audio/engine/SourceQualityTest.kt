package com.dreamdisplays.media.audio.engine

import com.dreamdisplays.api.media.audio.AcousticQuality
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Per-display acoustics tiers on top of the engine's global tier. */
class SourceQualityTest {
    @Test
    fun `a source tier wins over the global tier and can be cleared`() {
        val engine = AcousticsEngine(44100f)
        engine.setGlobalQuality(AcousticQuality.ADVANCED)
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val chainA = engine.registerSource(a) as AudioRenderChain
        val chainB = engine.registerSource(b) as AudioRenderChain
        engine.setSourceQuality(a, AcousticQuality.OFF)
        assertEquals(AcousticQuality.OFF, engine.qualityFor(a))
        assertEquals(AcousticQuality.OFF, chainA.qualityOverride)
        assertEquals(AcousticQuality.ADVANCED, engine.qualityFor(b))
        assertNull(chainB.qualityOverride)
        engine.setGlobalQuality(AcousticQuality.BASIC)
        assertEquals(AcousticQuality.OFF, engine.qualityFor(a))
        assertEquals(AcousticQuality.BASIC, engine.qualityFor(b))
        engine.setSourceQuality(a, null)
        assertEquals(AcousticQuality.BASIC, engine.qualityFor(a))
        assertNull(chainA.qualityOverride)
    }

    @Test
    fun `a tier set before registration applies, unregistering forgets it`() {
        val engine = AcousticsEngine(44100f)
        val id = UUID.randomUUID()
        engine.setSourceQuality(id, AcousticQuality.ULTRA)
        val chain = engine.registerSource(id) as AudioRenderChain
        assertEquals(AcousticQuality.ULTRA, chain.qualityOverride)
        engine.unregisterSource(id)
        assertEquals(engine.qualityFor(UUID.randomUUID()), engine.qualityFor(id))
        assertNull((engine.registerSource(id) as AudioRenderChain).qualityOverride)
    }
}
