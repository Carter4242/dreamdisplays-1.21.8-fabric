package com.dreamdisplays.core.display.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rescue levers: how they travel, what a client makes of them, and what unset / unknown values mean. */
class PolicyLeversTest {
    private fun settings(client: DisplayPolicy.Client) = ServerClientSettings(DisplayPolicy(client = client))

    @Test
    fun `levers travel server-wide and keep their wire names`() {
        val client = DisplayPolicy.Client(
            videoDecoder = DisplayPolicy.VideoDecoder.JVM, gpuYuv = false,
            textureUpload = DisplayPolicy.TextureUpload.BASIC, audioBackend = DisplayPolicy.AudioBackend.OPENAL,
            disabledResolvers = ["newpipe"], ffmpegSource = DisplayPolicy.ToolSource.SYSTEM,
            ytDlpSource = DisplayPolicy.ToolSource.BUNDLED, queuePreload = false, autoWarm = false,
        )
        val policy = DisplayPolicy(client = client)
        assertEquals(
            "{\"v\":1,\"client\":{\"videoDecoder\":\"jvm\",\"gpuYuv\":false,\"textureUpload\":\"basic\"," +
                "\"audioBackend\":\"openal\",\"disabledResolvers\":[\"newpipe\"],\"ffmpegSource\":\"system\"," +
                "\"ytDlpSource\":\"bundled\",\"queuePreload\":false,\"autoWarm\":false}}",
            policy.encode().toString(Charsets.UTF_8),
        )
        assertEquals(policy, DisplayPolicy.decode(policy.encode()))
        // Server-wide only: they never ride along with one display
        assertNull(policy.forDisplay().client)
        assertEquals(client, policy.serverWide().client)
    }

    @Test
    fun `unset levers leave the client's own behaviour`() {
        val s = ServerClientSettings.NONE
        assertEquals(DisplayPolicy.VideoDecoder.AUTO, s.videoDecoder)
        assertTrue(s.gpuYuv)
        assertTrue(s.persistentUploads)
        assertEquals("javasound", s.audioBackend("javasound"))
        assertEquals(emptySet(), s.disabledResolvers)
        assertEquals(DisplayPolicy.ToolSource.AUTO, s.ffmpegSource)
        assertEquals(DisplayPolicy.ToolSource.AUTO, s.ytDlpSource)
        assertTrue(s.queuePreload)
        assertTrue(s.autoWarm)
    }

    @Test
    fun `set levers win over the client's own settings`() {
        val s = settings(
            DisplayPolicy.Client(
                videoDecoder = DisplayPolicy.VideoDecoder.PROCESS, gpuYuv = false,
                textureUpload = DisplayPolicy.TextureUpload.BASIC, audioBackend = DisplayPolicy.AudioBackend.OPENAL,
                disabledResolvers = [" NewPipe ", "", "yt-dlp"], queuePreload = false, autoWarm = false,
            ),
        )
        assertEquals(DisplayPolicy.VideoDecoder.PROCESS, s.videoDecoder)
        assertFalse(s.gpuYuv)
        assertFalse(s.persistentUploads)
        assertEquals("openal", s.audioBackend("javasound"))
        assertEquals(setOf("newpipe", "yt-dlp"), s.disabledResolvers)
        assertFalse(s.queuePreload)
        assertFalse(s.autoWarm)
    }

    @Test
    fun `an unknown choice from a newer server reads as unset`() {
        val p = assertNotNull(
            DisplayPolicy.decode("""{"v":1,"client":{"videoDecoder":"vulkan","audioBackend":"wasapi","gpuYuv":false}}""".toByteArray()),
        )
        assertNull(p.client?.videoDecoder)
        assertNull(p.client?.audioBackend)
        assertEquals(false, p.client?.gpuYuv)
        assertEquals(DisplayPolicy.VideoDecoder.AUTO, ServerClientSettings(p).videoDecoder)
    }

    @Test
    fun `seek levers resolve into sync settings and merge per display`() {
        assertTrue(SyncSettings.DEFAULT.correctiveSeeks)
        assertTrue(SyncSettings.DEFAULT.cheapSeeks)
        val off = SyncSettings.from(DisplayPolicy.Sync(correctiveSeeks = false, cheapSeeks = false))
        assertFalse(off.correctiveSeeks)
        assertFalse(off.cheapSeeks)
        assertEquals(SyncSettings.DEFAULT.seekThresholdMs, off.seekThresholdMs)
        val merged = DisplayPolicy(sync = DisplayPolicy.Sync(cheapSeeks = false))
            .mergedOver(DisplayPolicy(sync = DisplayPolicy.Sync(correctiveSeeks = false)))
        assertEquals(DisplayPolicy.Sync(correctiveSeeks = false, cheapSeeks = false), merged.sync)
        assertEquals(off, SyncSettings.from(off.toSection()))
    }

    @Test
    fun `admin paths parse the levers`() {
        val resolvers = PolicyPaths.find("client.disabled_resolvers")!!
        assertEquals(listOf("newpipe", "yt-dlp"), PolicyPaths.apply(null, resolvers, "NewPipe, yt-dlp")?.client?.disabledResolvers)
        assertEquals(emptyList(), PolicyPaths.apply(null, resolvers, "none")?.client?.disabledResolvers)
        assertNull(PolicyPaths.apply(null, resolvers, "new pipe"))
        assertTrue(resolvers.serverWideOnly)
        val decoder = PolicyPaths.find("client.video_decoder")!!
        assertEquals(DisplayPolicy.VideoDecoder.JVM, PolicyPaths.apply(null, decoder, "JVM")?.client?.videoDecoder)
        assertNull(PolicyPaths.apply(null, decoder, "gpu"))
        val seeks = PolicyPaths.find("sync.corrective_seeks")!!
        assertFalse(seeks.serverWideOnly)
        assertEquals(false, PolicyPaths.apply(null, seeks, "off")?.sync?.correctiveSeeks)
    }
}
