@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common

import com.dreamdisplays.core.protocol.common.packets.ClientHello
import com.dreamdisplays.core.protocol.common.packets.FullscreenPrepare
import com.dreamdisplays.core.protocol.common.packets.FullscreenPrepareStatus
import com.dreamdisplays.core.protocol.common.packets.FullscreenState
import com.dreamdisplays.shared.playback.FullscreenPrepareState
import com.dreamdisplays.shared.protocol.PacketDirection
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FullscreenPrepareWireTest {
    private val proto = ProtoBuf { encodeDefaults = false }
    private val id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")

    @Test
    fun `prepare packets round trip`() {
        val prepare = FullscreenPrepare("a1b2c3d4", id, active = true, quality = "1080", ttlMs = 600_000)
        assertEquals(prepare, PacketRegistry.decode(PacketRegistry.encode(prepare)))
        val cancel = FullscreenPrepare("a1b2c3d4", id, active = false)
        assertEquals(cancel, PacketRegistry.decode(PacketRegistry.encode(cancel)))
        val status = FullscreenPrepareStatus("a1b2c3d4", id, FullscreenPrepareState.READY.wire)
        assertEquals(status, PacketRegistry.decode(PacketRegistry.encode(status)))
        assertEquals(FullscreenPrepareStatus(), PacketRegistry.decode(PacketRegistry.encode(FullscreenPrepareStatus())))
    }

    @Test
    fun `ids are fixed and directions enforced`() {
        assertEquals(42, PacketType.FULLSCREEN_PREPARE.id)
        assertEquals(43, PacketType.FULLSCREEN_PREPARE_STATUS.id)
        val prepare = PacketRegistry.encode(FullscreenPrepare("x", id, active = true))
        assertIs<FullscreenPrepare>(PacketRegistry.decode(prepare, PacketDirection.SERVER_TO_CLIENT))
        assertFailsWith<IllegalArgumentException> { PacketRegistry.decode(prepare, PacketDirection.CLIENT_TO_SERVER) }
        val status = PacketRegistry.encode(FullscreenPrepareStatus("x", id, 1))
        assertIs<FullscreenPrepareStatus>(PacketRegistry.decode(status, PacketDirection.CLIENT_TO_SERVER))
        assertFailsWith<IllegalArgumentException> { PacketRegistry.decode(status, PacketDirection.SERVER_TO_CLIENT) }
    }

    @Test
    fun `a client without the prepare packet drops it instead of failing`() {
        // An older client's registry has no entry for type 42: it decodes the envelope and returns null
        val bytes = PacketRegistry.encode(FullscreenPrepare("x", id, active = true))
        val envelope = proto.decodeFromByteArray(Envelope.serializer(), bytes)
        assertEquals(42, envelope.type)
        val oldRegistryView = proto.encodeToByteArray(Envelope.serializer(), envelope.copy(type = 9_999))
        assertNull(PacketRegistry.decode(oldRegistryView))
    }

    @Test
    fun `the prepare packet cannot be mistaken for a fullscreen start`() {
        // Same field numbers as FullscreenState for id / display / active: if it ever reached FullscreenState's decoder
        // it would read as a start, which is why it travels as its own packet type (unknown to old clients).
        val bytes = proto.encodeToByteArray(FullscreenPrepare.serializer(), FullscreenPrepare("x", id, active = true))
        assertTrue(proto.decodeFromByteArray(FullscreenState.serializer(), bytes).active)
        assertEquals(PacketType.FULLSCREEN_PREPARE, PacketType.entries.single { it.packetClass == FullscreenPrepare::class })
    }

    @Test
    fun `an old client's hello reads as not supporting prepare`() {
        val old = ClientHello(modVersion = "1.10.9", supportsPip = true)
        val decoded = PacketRegistry.decode(PacketRegistry.encode(old)) as ClientHello
        assertFalse(decoded.supportsFullscreenPrepare)
        val current = old.copy(supportsFullscreenPrepare = true)
        assertTrue((PacketRegistry.decode(PacketRegistry.encode(current)) as ClientHello).supportsFullscreenPrepare)
    }

    @Test
    fun `the new hello field only appends bytes an older server skips`() {
        val old = ClientHello(modVersion = "1.10.9", timeZoneOffsetMinutes = -300)
        val oldBytes = proto.encodeToByteArray(ClientHello.serializer(), old)
        val newBytes = proto.encodeToByteArray(ClientHello.serializer(), old.copy(supportsFullscreenPrepare = true))
        // Tag of field 28 (varint) followed by true: everything before it is what an older client sends
        assertTrue(newBytes.contentEquals(oldBytes + byteArrayOf(0xE0.toByte(), 0x01, 1)))
        // And a decoder that does not know a field skips it (field 99 stands in for one it has never heard of)
        val unknown = oldBytes + byteArrayOf(0x98.toByte(), 0x06, 1)
        assertEquals(old, proto.decodeFromByteArray(ClientHello.serializer(), unknown))
    }
}
