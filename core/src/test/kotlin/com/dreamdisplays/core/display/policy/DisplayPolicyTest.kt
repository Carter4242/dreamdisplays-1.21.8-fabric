package com.dreamdisplays.core.display.policy

import com.dreamdisplays.api.display.data.DisplayCustomDataLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisplayPolicyTest {
    private val full = DisplayPolicy(
        sync = DisplayPolicy.Sync(true, 40, 1.0, 1500, 8000),
        audio = DisplayPolicy.Audio(48.0, DisplayPolicy.Falloff.SQUARED, 0.8, 1.0),
        visibility = DisplayPolicy.Visibility(96.0),
        quality = DisplayPolicy.Quality(720),
        network = DisplayPolicy.Network(30),
    )

    private fun decode(text: String) = DisplayPolicy.decode(text.toByteArray())

    @Test
    fun `encode and decode round trip`() {
        assertEquals(full, DisplayPolicy.decode(full.encode()))
        assertEquals(DisplayPolicy.EMPTY, DisplayPolicy.decode(DisplayPolicy.EMPTY.encode()))
    }

    @Test
    fun `encoding is compact, versioned and fits the custom data limits`() {
        val text = full.encode().toString(Charsets.UTF_8)
        assertTrue(text.startsWith("{\"v\":1"), text)
        assertTrue("null" !in text, text)
        assertTrue(DisplayCustomDataLimits.accepts(emptyMap(), DisplayPolicy.KEY, full.encode()))
        assertTrue(DisplayCustomDataLimits.isValidKey(DisplayPolicy.KEY))
        assertEquals("{\"v\":1,\"sync\":{\"deadBandMs\":30}}",
            DisplayPolicy(sync = DisplayPolicy.Sync(deadBandMs = 30)).encode().toString(Charsets.UTF_8))
    }

    @Test
    fun `audio gain round trips, merges and clamps`() {
        val p = DisplayPolicy(audio = DisplayPolicy.Audio(radiusBlocks = 20.0)).withGain(0.8)
        assertEquals("{\"v\":1,\"audio\":{\"radiusBlocks\":20.0,\"gain\":0.8}}", p.encode().toString(Charsets.UTF_8))
        assertEquals(p, DisplayPolicy.decode(p.encode()))
        assertEquals(0.8, DisplayPolicy.EMPTY.mergedOver(p).audio?.gain)
        assertEquals(0.5, DisplayPolicy.EMPTY.withGain(0.5).mergedOver(p).audio?.gain)
        assertEquals(DisplayPolicy.EMPTY, DisplayPolicy.EMPTY.withGain(0.8).withGain(null))
        assertEquals(1.0, DisplayPolicy.Audio().resolvedGain)
        assertEquals(2.0, DisplayPolicy.Audio(gain = 9.0).resolvedGain)
        assertEquals(0.0, DisplayPolicy.Audio(gain = -1.0).resolvedGain)
        // Survives forDisplay(), so it reaches clients in DisplayInfo
        assertEquals(0.8, p.forDisplay().audio?.gain)
    }

    @Test
    fun `garbage and absent data decode to null`() {
        assertNull(DisplayPolicy.decode(null))
        assertNull(DisplayPolicy.decode(ByteArray(0)))
        assertNull(decode("not json"))
        assertNull(decode("[1,2]"))
        assertNull(DisplayPolicy.decode(byteArrayOf(0, -1, 3)))
    }

    @Test
    fun `a newer major version is ignored`() {
        assertNull(decode("{\"v\":2,\"sync\":{\"deadBandMs\":10}}"))
        assertEquals(10, decode("{\"v\":1,\"sync\":{\"deadBandMs\":10}}")?.sync?.deadBandMs)
        // Missing version reads as the current one
        assertEquals(10, decode("{\"sync\":{\"deadBandMs\":10}}")?.sync?.deadBandMs)
    }

    @Test
    fun `unknown fields and sections are ignored`() {
        val p = assertNotNull(decode("{\"v\":1,\"future\":{\"x\":1},\"sync\":{\"deadBandMs\":25,\"newThing\":true}}"))
        assertEquals(DisplayPolicy.Sync(deadBandMs = 25), p.sync)
    }

    @Test
    fun `a broken section is dropped without losing the others`() {
        val p = assertNotNull(decode("{\"v\":1,\"sync\":{\"deadBandMs\":\"abc\"},\"quality\":{\"maxHeight\":480}}"))
        assertNull(p.sync)
        assertEquals(480, p.quality?.maxHeight)
        assertNull(decode("{\"v\":1,\"sync\":5}")?.sync)
    }

    @Test
    fun `an unknown enum value falls back to the client default`() {
        val p = assertNotNull(decode("{\"audio\":{\"falloff\":\"cubic\",\"radiusBlocks\":20}}"))
        assertNull(p.audio?.falloff)
        assertEquals(20.0, p.audio?.radiusBlocks)
    }

    @Test
    fun `merge prefers the override field by field`() {
        val defaults = DisplayPolicy(
            sync = DisplayPolicy.Sync(enabled = true, deadBandMs = 40, maxRatePct = 1.0),
            quality = DisplayPolicy.Quality(1080),
        )
        val override = DisplayPolicy(
            sync = DisplayPolicy.Sync(deadBandMs = 20),
            visibility = DisplayPolicy.Visibility(64.0),
        )
        val merged = override.mergedOver(defaults)
        assertEquals(DisplayPolicy.Sync(enabled = true, deadBandMs = 20, maxRatePct = 1.0), merged.sync)
        assertEquals(1080, merged.quality?.maxHeight)
        assertEquals(64.0, merged.visibility?.maxDistanceBlocks)
        assertNull(merged.audio)
        assertEquals(defaults, DisplayPolicy.EMPTY.mergedOver(defaults))
    }

    @Test
    fun `isEmpty ignores sections that set nothing`() {
        assertTrue(DisplayPolicy.EMPTY.isEmpty)
        assertTrue(DisplayPolicy(sync = DisplayPolicy.Sync()).isEmpty)
        assertTrue(!full.isEmpty)
    }

    @Test
    fun `withField sets, replaces and removes a field`() {
        val a = assertNotNull(DisplayPolicy.withField(null, "sync.deadBandMs", 25))
        assertEquals(25, a.sync?.deadBandMs)
        val b = assertNotNull(DisplayPolicy.withField(a, "audio.falloff", "linear"))
        assertEquals(DisplayPolicy.Falloff.LINEAR, b.audio?.falloff)
        assertEquals(25, b.sync?.deadBandMs)
        val c = assertNotNull(DisplayPolicy.withField(b, "sync.deadBandMs", null))
        assertNull(c.sync?.deadBandMs)
    }

    @Test
    fun `withField rejects unknown paths and wrong types`() {
        assertNull(DisplayPolicy.withField(null, "sync.nope", 1))
        assertNull(DisplayPolicy.withField(null, "nope.deadBandMs", 1))
        assertNull(DisplayPolicy.withField(null, "deadBandMs", 1))
        assertNull(DisplayPolicy.withField(null, "sync.deadBandMs", "abc"))
        assertNull(DisplayPolicy.withField(null, "audio.falloff", "cubic"))
    }

    @Test
    fun `sync settings fill defaults and clamp`() {
        assertEquals(SyncSettings.DEFAULT, SyncSettings.from(null))
        assertEquals(SyncSettings.DEFAULT, SyncSettings.from(DisplayPolicy.Sync()))
        val wild = SyncSettings.from(DisplayPolicy.Sync(false, -5, 50.0, 10, 0))
        assertEquals(false, wild.enabled)
        assertEquals(0, wild.deadBandMs)
        assertEquals(5.0, wild.maxRatePct)
        assertEquals(200, wild.seekThresholdMs)
        assertEquals(1_000, wild.seekCooldownMs)
        // The dead band can never swallow the seek threshold
        assertEquals(500, SyncSettings.from(DisplayPolicy.Sync(deadBandMs = 900, seekThresholdMs = 1_000)).deadBandMs)
        assertEquals(SyncSettings.DEFAULT, SyncSettings.from(DisplayPolicy.CLIENT_DEFAULTS.sync))
        assertEquals(1.0, SyncSettings.from(DisplayPolicy.Sync(maxRatePct = Double.NaN)).maxRatePct)
    }

    @Test
    fun `network read-ahead decodes, merges and counts as content`() {
        assertEquals(DisplayPolicy.Network(45), decode("""{"v":1,"network":{"readAheadSeconds":45}}""")?.network)
        assertNull(decode("""{"v":1,"network":"nope","quality":{"maxHeight":720}}""")?.network)
        val merged = DisplayPolicy(network = DisplayPolicy.Network(0)).mergedOver(full)
        assertEquals(0, merged.network?.readAheadSeconds, "A display can turn read-ahead off over a server default.")
        assertEquals(30, DisplayPolicy().mergedOver(full).network?.readAheadSeconds)
        assertTrue(DisplayPolicy(network = DisplayPolicy.Network()).isEmpty)
        assertTrue(!DisplayPolicy(network = DisplayPolicy.Network(10)).isEmpty)
        assertEquals(20, PolicyPaths.apply(null, PolicyPaths.find("network.read_ahead_seconds")!!, "20")?.network?.readAheadSeconds)
        assertNull(PolicyPaths.apply(null, PolicyPaths.find("network.read_ahead_seconds")!!, "500"), "Out of range.")
    }
}
