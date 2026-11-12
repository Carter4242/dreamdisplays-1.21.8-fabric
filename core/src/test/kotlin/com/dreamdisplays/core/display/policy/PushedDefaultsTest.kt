@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.display.policy

import com.dreamdisplays.api.display.data.DisplayCustomDataLimits
import com.dreamdisplays.core.protocol.common.UuidSerializer
import com.dreamdisplays.core.protocol.common.ZERO_UUID
import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import com.dreamdisplays.core.display.policy.PushedSetting.Value
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.util.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushedDefaultsTest {
    /** One player's client for one display: its own settings and the revisions it applied (as `PushedDefaultsClient`). */
    private class Client(var volume: Float = 0.25f) {
        var brightness = 1f
        var quality = "1080"
        var tier: String? = null
        var muted = false
        val applied = mutableMapOf<String, Long>()

        /** A description of the display arrives with [stored] in its custom data. */
        fun see(stored: ByteArray?) {
            val pushed = PushedDefaults.decode(stored) ?: return
            for (p in pushed.pending(applied)) {
                when (val v = p.value) {
                    is Value.Volume -> volume = v.fraction
                    is Value.Brightness -> brightness = v.level
                    is Value.Quality -> quality = v.label
                    is Value.Audio3d -> tier = v.tier
                    is Value.Mute -> muted = v.muted
                }
                applied[p.setting.wire] = p.rev
            }
        }
    }

    private var server = PushedDefaults.EMPTY
    private var clock = 1_700_000_000_000L

    /** `/display defaults <d> <setting> <raw>`, then the bytes every viewer gets. */
    private fun push(setting: PushedSetting, raw: String): ByteArray {
        server = server.push(setting, setting.parse(raw)!!, clock)
        clock += 1_000
        return server.encode()
    }

    @Test
    fun `a push applies once and the player's own change stands after it`() {
        val a = Client(volume = 0.25f) // 50 %
        val bytes = push(PushedSetting.VOLUME, "100")
        a.see(bytes)
        assertEquals(0.5f, a.volume)
        a.volume = 0.35f // the player sets 70 %
        a.see(bytes) // the display is described again (rejoin, re-entering range, any other change)
        a.see(PushedDefaults.decode(bytes)!!.encode())
        assertEquals(0.35f, a.volume)
    }

    @Test
    fun `a second push of the same value resets players who changed it`() {
        val a = Client()
        a.see(push(PushedSetting.VOLUME, "50%"))
        assertEquals(0.25f, a.volume)
        a.volume = 0.4f
        a.see(push(PushedSetting.VOLUME, "50%"))
        assertEquals(0.25f, a.volume)
    }

    @Test
    fun `a player who first sees the display after a push starts at the pushed value`() {
        push(PushedSetting.VOLUME, "100")
        val bytes = push(PushedSetting.QUALITY, "720p")
        val newcomer = Client(volume = 0.1f) // seeded from audio.startVolume on first sight
        newcomer.see(bytes)
        assertEquals(0.5f, newcomer.volume)
        assertEquals("720", newcomer.quality)
    }

    @Test
    fun `settings are pushed independently`() {
        val a = Client()
        a.see(push(PushedSetting.VOLUME, "100"))
        a.volume = 0.35f
        a.see(push(PushedSetting.BRIGHTNESS, "60"))
        assertEquals(0.35f, a.volume) // pushing brightness leaves the volume the player chose
        assertEquals(0.6f, a.brightness)
    }

    @Test
    fun `every setting reaches the client`() {
        val a = Client()
        push(PushedSetting.VOLUME, "200%")
        push(PushedSetting.BRIGHTNESS, "100")
        push(PushedSetting.QUALITY, "auto")
        push(PushedSetting.AUDIO_3D, "ultra")
        a.see(push(PushedSetting.MUTE, "on"))
        assertEquals(1f, a.volume)
        assertEquals(1f, a.brightness)
        assertEquals("auto", a.quality)
        assertEquals("ULTRA", a.tier)
        assertTrue(a.muted)
        a.see(push(PushedSetting.AUDIO_3D, "default"))
        assertNull(a.tier)
    }

    @Test
    fun `clear stops pushing and the next push is still newer`() {
        val a = Client()
        a.see(push(PushedSetting.VOLUME, "100"))
        server = server.clear(null)
        a.volume = 0.3f
        a.see(server.encode())
        assertEquals(0.3f, a.volume) // players keep what they have
        // The server clock went back (or an older copy of the display was put back): still a newer revision
        clock -= 3_600_000
        a.see(push(PushedSetting.VOLUME, "100"))
        assertEquals(0.5f, a.volume)
    }

    @Test
    fun `clearing one setting keeps the others`() {
        push(PushedSetting.VOLUME, "100")
        push(PushedSetting.MUTE, "off")
        server = server.clear(PushedSetting.VOLUME)
        assertNull(server[PushedSetting.VOLUME])
        assertEquals("false", server[PushedSetting.MUTE]?.value)
    }

    @Test
    fun `revisions keep rising`() {
        val first = server.push(PushedSetting.VOLUME, "0.5", 5_000).rev
        val second = server.push(PushedSetting.VOLUME, "0.5", 5_000).push(PushedSetting.VOLUME, "0.5", 4_000)
        assertEquals(5_000, first)
        assertEquals(5_001, second.rev)
        assertEquals(5_001, second[PushedSetting.VOLUME]?.rev)
    }

    @Test
    fun `the server's policy levers still win over a pushed value`() {
        val a = Client()
        a.see(push(PushedSetting.VOLUME, "200"))
        // audio.maxVolume caps what it plays at; the player's own (pushed) volume stays as it is underneath
        assertEquals(0.3f, DisplayPolicy.Audio(maxVolume = 0.3).capVolume(a.volume))
        assertEquals(1f, a.volume)
        assertEquals(1f, DisplayPolicy.Audio().capVolume(a.volume))
    }

    @Test
    fun `unknown settings and unreadable values from a newer server are skipped`() {
        val json = """{"rev":9,"values":{"volume":{"value":"loud","rev":7},"subtitles":{"value":"on","rev":8},
            "mute":{"value":"true","rev":9}},"future":1}"""
        val pushed = PushedDefaults.decode(json.toByteArray())!!
        assertEquals(listOf(PushedSetting.MUTE), pushed.pending(emptyMap()).map { it.setting })
        assertNull(PushedDefaults.decode("not json".toByteArray()))
        assertNull(PushedDefaults.decode(ByteArray(0)))
    }

    @Test
    fun `persisted bytes read back the same and fit the custom-data limits`() {
        push(PushedSetting.VOLUME, "100")
        push(PushedSetting.AUDIO_3D, "advanced")
        val bytes = server.encode()
        assertEquals(server, PushedDefaults.decode(bytes))
        // Stored next to the display's policy: within every limit
        val stored = mapOf(DisplayPolicy.KEY to DisplayPolicy(audio = DisplayPolicy.Audio(maxVolume = 1.0)).encode())
        assertTrue(DisplayCustomDataLimits.accepts(stored, PushedDefaults.KEY, bytes))
        assertTrue(DisplayCustomDataLimits.isValidKey(PushedDefaults.KEY))
        // A server restart reads the stored state back and the next push is newer than everything in it
        val reloaded = PushedDefaults.decode(bytes)!!.push(PushedSetting.VOLUME, "0.25", 0)
        assertEquals(server.rev + 1, reloaded.rev)
    }

    @Test
    fun `admin values parse to the client's own units`() {
        assertEquals("0.5", PushedSetting.VOLUME.parse("100"))
        assertEquals("0.5", PushedSetting.VOLUME.parse("100%"))
        assertEquals("0.35", PushedSetting.VOLUME.parse(" 70 % "))
        assertEquals("0", PushedSetting.VOLUME.parse("0"))
        assertEquals("1", PushedSetting.VOLUME.parse("200"))
        assertNull(PushedSetting.VOLUME.parse("201"))
        assertNull(PushedSetting.VOLUME.parse("-1"))
        assertNull(PushedSetting.VOLUME.parse("NaN"))
        assertNull(PushedSetting.VOLUME.parse("loud"))
        assertEquals("100%", PushedSetting.VOLUME.show("0.5"))
        assertEquals("0.6", PushedSetting.BRIGHTNESS.parse("60"))
        assertEquals("1", PushedSetting.BRIGHTNESS.parse("100%"))
        assertNull(PushedSetting.BRIGHTNESS.parse("150"))
        assertEquals("60%", PushedSetting.BRIGHTNESS.show("0.6"))
        assertEquals("720", PushedSetting.QUALITY.parse("720p"))
        assertEquals("auto", PushedSetting.QUALITY.parse("AUTO"))
        assertNull(PushedSetting.QUALITY.parse("1000"))
        assertEquals("720p", PushedSetting.QUALITY.show("720"))
        assertEquals("ultra", PushedSetting.AUDIO_3D.parse("Ultra"))
        assertEquals("default", PushedSetting.AUDIO_3D.parse("default"))
        assertNull(PushedSetting.AUDIO_3D.parse("max"))
        assertEquals("true", PushedSetting.MUTE.parse("on"))
        assertEquals("false", PushedSetting.MUTE.parse("off"))
        assertNull(PushedSetting.MUTE.parse("maybe"))
        assertEquals(PushedSetting.AUDIO_3D, PushedSetting.fromWire("AUDIO_3D"))
        assertNull(PushedSetting.fromWire("clear"))
        // Every suggestion is a valid value
        PushedSetting.entries.forEach { s -> s.suggestions.forEach { assertTrue(s.parse(it) != null, "${s.wire} $it") } }
    }

    /** What released (protocol 12, 038a5ab3..) clients know of `DisplayInfo`, as far as custom data goes. */
    @Serializable
    private data class InfoV12(
        @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
        @ProtoNumber(8) val url: String = "",
        @ProtoNumber(25) val customData: Map<String, ByteArray> = emptyMap(),
    )

    @Test
    fun `pushed values travel in the description's custom data and released clients read it as before`() {
        val proto = ProtoBuf { encodeDefaults = false }
        val id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
        val policy = DisplayPolicy(audio = DisplayPolicy.Audio(maxVolume = 0.8))
        val pushed = push(PushedSetting.VOLUME, "100")
        val info = DisplayInfo(
            id, url = "https://youtu.be/a",
            customData = mapOf(DisplayPolicy.KEY to policy.encode(), PushedDefaults.KEY to pushed),
        )
        val bytes = proto.encodeToByteArray(DisplayInfo.serializer(), info)
        val decoded = proto.decodeFromByteArray(DisplayInfo.serializer(), bytes)
        assertContentEquals(pushed, decoded.customData[PushedDefaults.KEY])
        assertEquals(server, PushedDefaults.decode(decoded.customData[PushedDefaults.KEY]))
        // A released client: the same custom data, still within the limits it checks, its policy unchanged
        val old = proto.decodeFromByteArray(InfoV12.serializer(), bytes)
        assertTrue(DisplayCustomDataLimits.isValid(old.customData))
        assertEquals(policy, DisplayPolicy.decode(old.customData[DisplayPolicy.KEY]))
    }
}
