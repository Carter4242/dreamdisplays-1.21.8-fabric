@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common

import com.dreamdisplays.core.protocol.common.packets.ClientHello
import com.dreamdisplays.core.protocol.common.packets.ServerHello
import com.dreamdisplays.shared.capability.ClientFeature
import com.dreamdisplays.shared.protocol.ProtocolVersion
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hello's protocol version is on the wire, and a hello without one (every jar before this rule left the field at
 * its own CURRENT, so it was never encoded) reads as UNKNOWN, never as the receiver's own version.
 */
class HelloVersionWireTest {
    /** A release-11 hello as it was declared: the version defaulted to that build's CURRENT. */
    @Serializable
    private data class ClientHelloV11(
        @ProtoNumber(1) val protocolVersion: Int = 11,
        @ProtoNumber(2) val modVersion: String = "",
    )

    @Serializable
    private data class ServerHelloV11(
        @ProtoNumber(1) val protocolVersion: Int = 11,
        @ProtoNumber(7) val allowedFeatures: List<String> = [],
    )

    private val legacyCodec = ProtoBuf { encodeDefaults = false }

    private fun frame(type: PacketType, payload: ByteArray): ByteArray =
        legacyCodec.encodeToByteArray(Envelope.serializer(), Envelope(type.id, payload))

    @Test
    fun `an old client hello reads as unknown, not as this build's version`() {
        val payload = legacyCodec.encodeToByteArray(ClientHelloV11.serializer(), ClientHelloV11(modVersion = "1.10.9"))
        val decoded = PacketRegistry.decode(frame(PacketType.CLIENT_HELLO, payload)) as ClientHello
        assertEquals(ProtocolVersion.UNKNOWN, decoded.protocolVersion)
        assertEquals("1.10.9", decoded.modVersion)
        assertEquals(emptyList(), decoded.features)
        assertFalse(decoded.hasFeature(ClientFeature.MEDIA_GEN))
    }

    @Test
    fun `an old server hello reads as unknown and as no greeting`() {
        val payload = legacyCodec.encodeToByteArray(ServerHelloV11.serializer(), ServerHelloV11(allowedFeatures = ["queue"]))
        val decoded = PacketRegistry.decode(frame(PacketType.SERVER_HELLO, payload)) as ServerHello
        assertEquals(ProtocolVersion.UNKNOWN, decoded.protocolVersion)
        assertFalse(decoded.greeting)
    }

    @Test
    fun `a version set by the sender is encoded and read back`() {
        val hello = ClientHello(protocolVersion = ProtocolVersion.CURRENT, modVersion = "1.10.9")
        val bytes = legacyCodec.encodeToByteArray(ClientHello.serializer(), hello)
        // Field 1, varint: tag byte 0x08 then the version
        assertEquals(0x08, bytes[0].toInt())
        assertEquals(ProtocolVersion.CURRENT, bytes[1].toInt())
        val decoded = PacketRegistry.decode(PacketRegistry.encode(hello)) as ClientHello
        assertEquals(ProtocolVersion.CURRENT, decoded.protocolVersion)
        val server = PacketRegistry.decode(PacketRegistry.encode(ServerHello(protocolVersion = ProtocolVersion.CURRENT)))
        assertEquals(ProtocolVersion.CURRENT, (server as ServerHello).protocolVersion)
    }

    @Test
    fun `an old reader skips the new fields`() {
        val hello = ClientHello(protocolVersion = ProtocolVersion.CURRENT, modVersion = "1.10.9", features = ["media_gen"])
        val old = legacyCodec.decodeFromByteArray(ClientHelloV11.serializer(), legacyCodec.encodeToByteArray(ClientHello.serializer(), hello))
        assertEquals(ClientHelloV11(ProtocolVersion.CURRENT, "1.10.9"), old)
        val greeting = ServerHello(protocolVersion = ProtocolVersion.CURRENT, allowedFeatures = ["queue"], greeting = true)
        val oldServer = legacyCodec.decodeFromByteArray(ServerHelloV11.serializer(), legacyCodec.encodeToByteArray(ServerHello.serializer(), greeting))
        assertEquals(listOf("queue"), oldServer.allowedFeatures)
    }

    @Test
    fun `features and greeting round trip, unknown tokens are ignored`() {
        val hello = ClientHello(
            protocolVersion = ProtocolVersion.CURRENT,
            features = [ClientFeature.MEDIA_GEN.wire, "some_future_token", ClientFeature.READINESS_UNHOLDABLE.wire],
        )
        val decoded = PacketRegistry.decode(PacketRegistry.encode(hello)) as ClientHello
        assertEquals(hello, decoded)
        assertTrue(decoded.hasFeature(ClientFeature.MEDIA_GEN))
        assertTrue(decoded.hasFeature(ClientFeature.READINESS_UNHOLDABLE))
        assertFalse(decoded.hasFeature(ClientFeature.SHARE_HEARTBEAT))
        val greeting = ServerHello(protocolVersion = ProtocolVersion.CURRENT, greeting = true)
        assertTrue((PacketRegistry.decode(PacketRegistry.encode(greeting)) as ServerHello).greeting)
    }

    @Test
    fun `client feature tokens are unique and decode`() {
        assertEquals(ClientFeature.entries.size, ClientFeature.entries.map { it.wire }.toSet().size)
        for (feature in ClientFeature.entries) assertEquals(feature, ClientFeature.fromWire(feature.wire))
    }
}
