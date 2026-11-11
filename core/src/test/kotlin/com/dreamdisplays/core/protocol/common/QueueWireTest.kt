@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common

import com.dreamdisplays.core.protocol.common.packets.ClientHello
import com.dreamdisplays.core.protocol.common.packets.QueueAction
import com.dreamdisplays.core.protocol.common.packets.QueueActionType
import com.dreamdisplays.core.protocol.common.packets.QueueItemWire
import com.dreamdisplays.core.protocol.common.packets.QueuePreload
import com.dreamdisplays.shared.playback.FullscreenPrepareState
import com.dreamdisplays.core.protocol.common.packets.QueueState
import com.dreamdisplays.shared.protocol.PacketDirection
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class QueueWireTest {
    private val proto = ProtoBuf { encodeDefaults = false }
    private val id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
    private val adder = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210")

    private val state = QueueState(
        id = id,
        items = listOf(
            QueueItemWire("i1", "https://youtu.be/abc", "en", "First ✓", 213_000, "https://i.ytimg.com/vi/abc/mqdefault.jpg", adder, "Alice"),
            QueueItemWire("i2", "https://example.com/v.mp4", "", "", 0, "", adder, "Alice"),
        ),
        loop = true, maxItems = 50, maxPerPlayer = 10, revision = 7, currentItemId = "i0",
    )

    @Test
    fun `queue packets round trip`() {
        assertEquals(state, PacketRegistry.decode(PacketRegistry.encode(state)))
        assertEquals(QueueState(), PacketRegistry.decode(PacketRegistry.encode(QueueState())))
        val add = QueueAction(id, QueueActionType.ADD.wire, url = "https://youtu.be/x", title = "x", durationMs = 5)
        assertEquals(add, PacketRegistry.decode(PacketRegistry.encode(add)))
        val move = QueueAction(id, QueueActionType.MOVE.wire, itemId = "i2", toIndex = 0)
        assertEquals(move, PacketRegistry.decode(PacketRegistry.encode(move)))
        val batch = QueueAction(id, QueueActionType.ADD_BATCH.wire, items = state.items)
        assertEquals(batch, PacketRegistry.decode(PacketRegistry.encode(batch)))
        // A batch of 100 playlist entries stays well under the 32 KiB serverbound payload limit
        val big = QueueAction(
            id, QueueActionType.ADD_BATCH.wire,
            items = List(100) { QueueItemWire(url = "https://www.youtube.com/watch?v=dQw4w9WgXc$it".take(43), title = "x".repeat(100), durationMs = 600_000) },
        )
        kotlin.test.assertTrue(PacketRegistry.encode(big).size < 20_000)
        val preload = QueuePreload(id, "i1", FullscreenPrepareState.READY.wire)
        assertEquals(preload, PacketRegistry.decode(PacketRegistry.encode(preload)))
    }

    @Test
    fun `directions are enforced`() {
        assertIs<QueueState>(PacketRegistry.decode(PacketRegistry.encode(state), PacketDirection.SERVER_TO_CLIENT))
        assertFailsWith<IllegalArgumentException> {
            PacketRegistry.decode(PacketRegistry.encode(state), PacketDirection.CLIENT_TO_SERVER)
        }
        assertFailsWith<IllegalArgumentException> {
            PacketRegistry.decode(PacketRegistry.encode(QueueAction(id)), PacketDirection.SERVER_TO_CLIENT)
        }
        assertIs<QueuePreload>(PacketRegistry.decode(PacketRegistry.encode(QueuePreload(id)), PacketDirection.CLIENT_TO_SERVER))
    }

    @Test
    fun `ids stay clear of the other ranges`() {
        assertEquals(50, PacketType.QUEUE_STATE.id)
        assertEquals(51, PacketType.QUEUE_ACTION.id)
        assertEquals(52, PacketType.QUEUE_PRELOAD.id)
    }

    @Test
    fun `an older peer drops a queue packet instead of failing`() {
        // An older build's registry has no entry for ids 50-52: decode() returns null for an unknown type id
        val bytes = PacketRegistry.encode(state)
        val envelope = proto.decodeFromByteArray(Envelope.serializer(), bytes)
        assertEquals(50, envelope.type)
        val unknown = proto.encodeToByteArray(Envelope.serializer(), Envelope(type = 98, payload = envelope.payload))
        assertNull(PacketRegistry.decode(unknown))
    }

    /** The prefix of [ClientHello] an older server knows. */
    @Serializable
    private data class OldHello(
        @ProtoNumber(1) val protocolVersion: Int = 0,
        @ProtoNumber(2) val modVersion: String = "",
        @ProtoNumber(27) val timeZoneOffsetMinutes: Int = 0,
    )

    @Test
    fun `the queue capability bit is invisible to older servers and absent from older clients`() {
        val newHello = ClientHello(modVersion = "1.10.9", timeZoneOffsetMinutes = -300, supportsQueue = true)
        val old = proto.decodeFromByteArray(OldHello.serializer(), proto.encodeToByteArray(ClientHello.serializer(), newHello))
        assertEquals("1.10.9", old.modVersion)
        assertEquals(-300, old.timeZoneOffsetMinutes)
        // An older client never sets field 30, so a new server reads it as false and sends it no queue packets
        val fromOldClient = proto.decodeFromByteArray(ClientHello.serializer(), proto.encodeToByteArray(OldHello.serializer(), old))
        assertEquals(false, fromOldClient.supportsQueue)
    }
}
