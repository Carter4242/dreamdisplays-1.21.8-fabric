package com.dreamdisplays.core.display.policy

import com.dreamdisplays.api.display.data.DisplayCustomDataLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `client` and `debug` policy sections and how clients resolve them against their own settings. */
class ClientPolicyTest {
    private val client = DisplayPolicy.Client(
        enabled = false, maxActiveDisplays = 3, hardwareDecoding = false,
        audioAcoustics = DisplayPolicy.Acoustics.BASIC, unshadedDisplays = false, displayDistanceChunks = 6,
    )
    private val debug = DisplayPolicy.Debug(syncLogIntervalSeconds = 2, verbose = true, reportIntervalSeconds = 7)
    private val full = DisplayPolicy(
        sync = DisplayPolicy.Sync(deadBandMs = 30), quality = DisplayPolicy.Quality(720), client = client, debug = debug,
    )

    private fun decode(text: String) = DisplayPolicy.decode(text.toByteArray())

    @Test
    fun `client and debug round trip and stay compact`() {
        assertEquals(full, DisplayPolicy.decode(full.encode()))
        assertEquals(
            "{\"v\":1,\"client\":{\"enabled\":false,\"audioAcoustics\":\"off\"}}",
            DisplayPolicy(client = DisplayPolicy.Client(enabled = false, audioAcoustics = DisplayPolicy.Acoustics.OFF))
                .encode().toString(Charsets.UTF_8),
        )
        assertTrue(DisplayCustomDataLimits.accepts(emptyMap(), DisplayPolicy.KEY, full.encode()))
        assertFalse(DisplayPolicy(client = DisplayPolicy.Client(enabled = true)).isEmpty)
        assertFalse(DisplayPolicy(debug = DisplayPolicy.Debug(verbose = false)).isEmpty)
        assertTrue(DisplayPolicy(client = DisplayPolicy.Client(), debug = DisplayPolicy.Debug()).isEmpty)
    }

    @Test
    fun `older clients ignore the new sections and a broken one is dropped alone`() {
        // A client without these sections just never reads them; here: unknown values / types inside them
        val p = assertNotNull(decode("""{"v":1,"client":{"audioAcoustics":"hyper","enabled":false},"quality":{"maxHeight":480}}"""))
        assertNull(p.client?.audioAcoustics)
        assertEquals(false, p.client?.enabled)
        val broken = assertNotNull(decode("""{"v":1,"client":{"enabled":"nope"},"debug":{"verbose":true},"future":{"x":1}}"""))
        assertNull(broken.client)
        assertEquals(true, broken.debug?.verbose)
    }

    @Test
    fun `merging takes unset fields from the defaults`() {
        val defaults = DisplayPolicy(client = DisplayPolicy.Client(enabled = true, maxActiveDisplays = 4), debug = debug)
        val override = DisplayPolicy(client = DisplayPolicy.Client(displayDistanceChunks = 8))
        val merged = override.mergedOver(defaults)
        assertEquals(DisplayPolicy.Client(enabled = true, maxActiveDisplays = 4, displayDistanceChunks = 8), merged.client)
        assertEquals(debug, merged.debug)
        assertEquals(defaults.client, DisplayPolicy.EMPTY.mergedOver(defaults).client)
    }

    @Test
    fun `per-display payload drops server-wide-only fields`() {
        val perDisplay = full.forDisplay()
        assertEquals(
            DisplayPolicy.Client(enabled = false, displayDistanceChunks = 6, audioAcoustics = DisplayPolicy.Acoustics.BASIC),
            perDisplay.client,
        )
        assertNull(perDisplay.debug)
        assertEquals(full.sync, perDisplay.sync)
        assertNull(DisplayPolicy(client = DisplayPolicy.Client(maxActiveDisplays = 2)).forDisplay().client)

        val wide = full.serverWide()
        assertEquals(client, wide.client)
        assertEquals(debug, wide.debug)
        assertNull(wide.sync)
        assertNull(wide.quality)
    }

    @Test
    fun `server-wide-only set matches real fields`() {
        for (field in DisplayPolicy.GLOBAL_ONLY) {
            val value: Any = when {
                field.endsWith("videoDecoder") -> "jvm"
                field.endsWith("textureUpload") -> "basic"
                field.endsWith("audioBackend") -> "openal"
                field.endsWith("Source") -> "system"
                field.endsWith("JsRuntime") -> "off"
                field.endsWith("twitchAdSkip") -> "off"
                field.endsWith("disabledResolvers") -> listOf("newpipe")
                field.endsWith("gpuYuv") || field.endsWith("queuePreload") || field.endsWith("autoWarm") -> false
                field.endsWith("Acoustics") -> "off"
                field.endsWith("OutputProfile") -> "speakers"
                field.endsWith("logLevel") -> "ultra"
                field.endsWith("Decoding") || field.endsWith("Displays") && !field.contains("max") ||
                        field.endsWith("verbose") || field.endsWith("Normalization") -> true
                else -> 1
            }
            assertNotNull(DisplayPolicy.withField(null, field, value), field)
        }
        val serverWide = PolicyPaths.all.filter { it.serverWideOnly }.map { it.name }.toSet()
        assertEquals(
            setOf(
                "client.max_active_displays", "client.hardware_decoding", "client.audio_output_profile",
                "client.audio_normalization", "client.unshaded_displays", "debug.sync_log_interval_seconds", "debug.verbose", "debug.log_level",
                "debug.report_interval_seconds", "client.video_decoder", "client.gpu_yuv", "client.texture_upload",
                "client.audio_backend", "client.disabled_resolvers", "client.ffmpeg_source", "client.yt_dlp_source",
                "client.yt_dlp_js_runtime", "client.queue_preload", "client.auto_warm", "client.twitch_ad_skip",
            ),
            serverWide,
        )
        assertFalse(PolicyPaths.find("client.enabled")!!.serverWideOnly)
        assertFalse(PolicyPaths.find("client.display_distance_chunks")!!.serverWideOnly)
        assertFalse(PolicyPaths.find("client.audio_acoustics")!!.serverWideOnly)
    }

    @Test
    fun `acoustics precedence is display, then server-wide, then the player`() {
        val serverBasic = ServerClientSettings.fromWire(
            DisplayPolicy(client = DisplayPolicy.Client(audioAcoustics = DisplayPolicy.Acoustics.BASIC)).encode(),
        )
        val displayUltra = DisplayPolicy(client = DisplayPolicy.Client(audioAcoustics = DisplayPolicy.Acoustics.ULTRA))
        val displayNone = DisplayPolicy(client = DisplayPolicy.Client(enabled = true))
        assertEquals("ULTRA", serverBasic.audioAcoustics("OFF", displayUltra))
        assertEquals("BASIC", serverBasic.audioAcoustics("OFF", displayNone))
        assertEquals("BASIC", serverBasic.audioAcoustics("OFF", null))
        assertEquals("OFF", ServerClientSettings.NONE.audioAcoustics("OFF", displayNone))
        assertEquals("ULTRA", ServerClientSettings.NONE.audioAcoustics("OFF", displayUltra))
        assertEquals("ULTRA", serverBasic.displayAcoustics(displayUltra))
        assertNull(serverBasic.displayAcoustics(displayNone))
        // A per-display tier travels with the display; the server-wide payload never carries display fields
        assertEquals(DisplayPolicy.Acoustics.ULTRA, displayUltra.forDisplay().client?.audioAcoustics)
        assertNull(DisplayPolicy(sync = DisplayPolicy.Sync(deadBandMs = 1)).serverWide().client)
    }

    @Test
    fun `older clients still read a display policy that carries an acoustics tier`() {
        // The field already existed in the released schema; per display it just arrives in another payload
        val wire = DisplayPolicy(
            client = DisplayPolicy.Client(enabled = true, audioAcoustics = DisplayPolicy.Acoustics.OFF, displayDistanceChunks = 4),
        ).forDisplay().encode().toString(Charsets.UTF_8)
        assertEquals("{\"v\":1,\"client\":{\"enabled\":true,\"audioAcoustics\":\"off\",\"displayDistanceChunks\":4}}", wire)
        val read = assertNotNull(decode(wire))
        assertEquals(true, read.client?.enabled)
        assertEquals(4, read.client?.displayDistanceChunks)
    }

    @Test
    fun `output profile and normalization overrides`() {
        val none = ServerClientSettings.NONE
        assertEquals(true, none.audioBinauralOutput(true))
        assertEquals(false, none.audioBinauralOutput(false))
        assertEquals(false, none.audioNormalization(false))
        val forced = ServerClientSettings.fromWire(
            DisplayPolicy(
                client = DisplayPolicy.Client(
                    audioOutputProfile = DisplayPolicy.OutputProfile.SPEAKERS, audioNormalization = false,
                ),
            ).encode(),
        )
        assertEquals(false, forced.audioBinauralOutput(true))
        assertEquals(false, forced.audioNormalization(true))
        val headphones = ServerClientSettings.fromWire(
            DisplayPolicy(client = DisplayPolicy.Client(audioOutputProfile = DisplayPolicy.OutputProfile.HEADPHONES)).encode(),
        )
        assertEquals(true, headphones.audioBinauralOutput(false))
        assertEquals(true, headphones.audioNormalization(true))
        // Never per display
        val perDisplay = DisplayPolicy(
            client = DisplayPolicy.Client(audioOutputProfile = DisplayPolicy.OutputProfile.SPEAKERS, audioNormalization = false),
        ).forDisplay()
        assertNull(perDisplay.client)
        val profile = PolicyPaths.find("client.audio_output_profile")!!
        assertEquals(DisplayPolicy.OutputProfile.SPEAKERS, PolicyPaths.apply(null, profile, "Speakers")?.client?.audioOutputProfile)
        assertNull(PolicyPaths.apply(null, profile, "earbuds"))
        assertEquals(false, PolicyPaths.apply(null, PolicyPaths.find("client.audio_normalization")!!, "off")?.client?.audioNormalization)
    }

    @Test
    fun `policy paths parse the new keys`() {
        val acoustics = PolicyPaths.find("client.audio_acoustics")!!
        val set = assertNotNull(PolicyPaths.apply(null, acoustics, "Ultra"))
        assertEquals(DisplayPolicy.Acoustics.ULTRA, set.client?.audioAcoustics)
        assertNull(PolicyPaths.apply(null, acoustics, "loud"))
        assertNull(PolicyPaths.apply(null, PolicyPaths.find("client.max_active_displays")!!, "17"))
        assertNull(PolicyPaths.apply(null, PolicyPaths.find("debug.report_interval_seconds")!!, "-1"))
        val off = assertNotNull(PolicyPaths.apply(null, PolicyPaths.find("client.enabled")!!, "off"))
        assertEquals(false, off.client?.enabled)
    }

    @Test
    fun `no server policy means the player's own settings and no reports`() {
        val s = ServerClientSettings.NONE
        assertFalse(s.received)
        assertTrue(s.displaysEnabled)
        assertTrue(s.displayEnabled(null))
        assertEquals(5, s.maxActiveDisplays(5))
        assertEquals(true, s.hardwareDecoding(true))
        assertEquals("ADVANCED", s.audioAcoustics("ADVANCED"))
        assertFalse(s.acousticsForced)
        assertEquals(true, s.unshadedDisplays(true))
        assertEquals(12, s.displayDistanceChunks(12))
        assertEquals(0L, s.reportIntervalMs)
        assertEquals(ServerClientSettings.DEFAULT_SYNC_LOG_MS, s.syncLogIntervalMs)
        assertFalse(s.verbose)
    }

    @Test
    fun `server overrides win over the player's settings`() {
        val s = ServerClientSettings.fromWire(full.encode())
        assertTrue(s.received)
        assertFalse(s.displaysEnabled)
        assertEquals(3, s.maxActiveDisplays(0))
        assertEquals(false, s.hardwareDecoding(true))
        assertEquals("BASIC", s.audioAcoustics("ULTRA"))
        assertTrue(s.acousticsForced)
        assertEquals(false, s.unshadedDisplays(true))
        assertEquals(6, s.displayDistanceChunks(12))
        assertEquals(2_000L, s.syncLogIntervalMs)
        assertTrue(s.verbose)
        assertEquals(7_000L, s.reportIntervalMs)
        // Per-display sections never leak into the server-wide settings
        assertNull(s.serverWide?.quality)
    }

    @Test
    fun `kill switch - server-wide off beats a display on`() {
        val off = ServerClientSettings(DisplayPolicy(client = DisplayPolicy.Client(enabled = false)))
        assertFalse(off.displayEnabled(DisplayPolicy(client = DisplayPolicy.Client(enabled = true))))
        val on = ServerClientSettings(DisplayPolicy(client = DisplayPolicy.Client(enabled = true)))
        assertTrue(on.displayEnabled(null))
        assertFalse(on.displayEnabled(DisplayPolicy(client = DisplayPolicy.Client(enabled = false))))
        assertTrue(ServerClientSettings.NONE.displayEnabled(DisplayPolicy(client = DisplayPolicy.Client())))
    }

    @Test
    fun `display distance prefers the display, then the server, and is clamped`() {
        val s = ServerClientSettings(DisplayPolicy(client = DisplayPolicy.Client(displayDistanceChunks = 99)))
        assertEquals(32, s.displayDistanceChunks(4))
        assertEquals(3, s.displayDistanceChunks(4, DisplayPolicy(client = DisplayPolicy.Client(displayDistanceChunks = 3))))
        assertEquals(16, ServerClientSettings(DisplayPolicy(client = DisplayPolicy.Client(maxActiveDisplays = 40))).maxActiveDisplays(1))
    }

    @Test
    fun `report and log intervals`() {
        fun with(debug: DisplayPolicy.Debug) = ServerClientSettings(DisplayPolicy(debug = debug))
        assertEquals(ServerClientSettings.DEFAULT_REPORT_MS, ServerClientSettings(DisplayPolicy.EMPTY).reportIntervalMs)
        assertEquals(ServerClientSettings.DEFAULT_REPORT_MS, ServerClientSettings.fromWire(ByteArray(0)).reportIntervalMs)
        assertEquals(0L, with(DisplayPolicy.Debug(reportIntervalSeconds = 0)).reportIntervalMs)
        assertEquals(2_000L, with(DisplayPolicy.Debug(reportIntervalSeconds = 1)).reportIntervalMs)
        assertEquals(600_000L, with(DisplayPolicy.Debug(reportIntervalSeconds = 9_999)).reportIntervalMs)
        assertEquals(0L, with(DisplayPolicy.Debug(syncLogIntervalSeconds = 0)).syncLogIntervalMs)
        assertEquals(3_600_000L, with(DisplayPolicy.Debug(syncLogIntervalSeconds = 99_999)).syncLogIntervalMs)
    }
}
