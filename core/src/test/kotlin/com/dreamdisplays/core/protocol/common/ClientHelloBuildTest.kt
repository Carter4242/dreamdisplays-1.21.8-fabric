@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common

import com.dreamdisplays.core.protocol.common.packets.ClientHello
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.test.Test
import kotlin.test.assertEquals

/** The client build id (fields 30 / 31) survives the wire, and a hello from a jar that predates it reads as empty. */
class ClientHelloBuildTest {
    /** The hello as jars without a build id send it (a subset of the fields is enough for the wire check). */
    @Serializable
    private data class OldClientHello(
        @ProtoNumber(1) val protocolVersion: Int = 2,
        @ProtoNumber(2) val modVersion: String = "",
        @ProtoNumber(27) val timeZoneOffsetMinutes: Int = 0,
    )

    @Test
    fun buildFieldsRoundTrip() {
        val hello = ClientHello(modVersion = "1.10.9", buildCommit = "1a2b3c4d-dev-dirty", buildTimeMs = 1_794_000_000_000)
        assertEquals(hello, PacketRegistry.decode(PacketRegistry.encode(hello)))
    }

    @Test
    fun helloWithoutBuildFieldsDecodesAsOlder() {
        val proto = ProtoBuf { }
        val payload = proto.encodeToByteArray(
            OldClientHello.serializer(),
            OldClientHello(modVersion = "1.10.9", timeZoneOffsetMinutes = -300),
        )
        val frame = proto.encodeToByteArray(Envelope.serializer(), Envelope(PacketType.CLIENT_HELLO.id, payload))
        val decoded = PacketRegistry.decode(frame) as ClientHello
        assertEquals("1.10.9", decoded.modVersion)
        assertEquals(-300, decoded.timeZoneOffsetMinutes)
        assertEquals("", decoded.buildCommit)
        assertEquals(0L, decoded.buildTimeMs)
    }

    @Test
    fun buildCommitOnlyIsEncodedWhenSet() {
        val proto = ProtoBuf { }
        val without = proto.encodeToByteArray(ClientHello.serializer(), ClientHello(modVersion = "1.10.9"))
        val with = proto.encodeToByteArray(ClientHello.serializer(), ClientHello(modVersion = "1.10.9", buildCommit = "1a2b3c4d"))
        // tag 30, wire type 2 -> varint 0xF2 0x01, then length 8 and the id
        assertEquals(without.size + 2 + 1 + 8, with.size)
    }
}
