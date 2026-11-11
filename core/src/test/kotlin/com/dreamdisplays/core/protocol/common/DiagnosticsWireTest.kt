@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common

import com.dreamdisplays.core.display.policy.DisplayPolicy
import com.dreamdisplays.core.display.policy.ServerClientSettings
import com.dreamdisplays.core.protocol.common.packets.ServerPolicy
import com.dreamdisplays.core.protocol.common.packets.ViewerReport
import com.dreamdisplays.core.protocol.common.packets.ViewerState
import com.dreamdisplays.shared.protocol.PacketDirection
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class DiagnosticsWireTest {
    private val proto = ProtoBuf { encodeDefaults = false }
    private val id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")

    private val report = ViewerReport(
        id = id, state = ViewerState.BUFFERING.wire, syncing = true, driftMs = -137, rate = 0.9925,
        latencyCompMs = 42, action = 3, seeks = 2, stalls = 1, buffering = true, positionMs = 3_723_000L,
        qualityHeight = 1080, errorKind = "", errorMessage = "",
    )

    @Test
    fun `both packets round trip`() {
        assertEquals(report, PacketRegistry.decode(PacketRegistry.encode(report)))
        val policy = ServerPolicy(DisplayPolicy(client = DisplayPolicy.Client(enabled = false)).encode())
        assertEquals(policy, PacketRegistry.decode(PacketRegistry.encode(policy)))
        assertEquals(ViewerReport(), PacketRegistry.decode(PacketRegistry.encode(ViewerReport())))
        assertEquals(ViewerReport(errorKind = "NETWORK", errorMessage = "reset ✓", state = ViewerState.ERROR.wire),
            PacketRegistry.decode(PacketRegistry.encode(ViewerReport(errorKind = "NETWORK", errorMessage = "reset ✓", state = ViewerState.ERROR.wire))))
    }

    @Test
    fun `directions are enforced`() {
        assertIs<ViewerReport>(PacketRegistry.decode(PacketRegistry.encode(report), PacketDirection.CLIENT_TO_SERVER))
        assertFailsWith<IllegalArgumentException> {
            PacketRegistry.decode(PacketRegistry.encode(report), PacketDirection.SERVER_TO_CLIENT)
        }
        assertIs<ServerPolicy>(PacketRegistry.decode(PacketRegistry.encode(ServerPolicy()), PacketDirection.SERVER_TO_CLIENT))
    }

    @Test
    fun `a peer that does not know the packet type gets null, not an error`() {
        // What an older client / server sees: an envelope whose type id it has no entry for
        val unknown = proto.encodeToByteArray(Envelope.serializer(), Envelope(type = 99, payload = byteArrayOf(8, 1)))
        assertNull(PacketRegistry.decode(unknown))
        assertEquals(40, PacketType.SERVER_POLICY.id)
        assertEquals(41, PacketType.VIEWER_REPORT.id)
    }

    @Test
    fun `a report with fields from a newer client still decodes`() {
        val bytes = proto.encodeToByteArray(ViewerReport.serializer(), report) +
            byteArrayOf(0xF0.toByte(), 0x01, 7) // tag of field 30 (varint), value 7: unknown here
        assertEquals(report, proto.decodeFromByteArray(ViewerReport.serializer(), bytes))
    }

    @Test
    fun `the policy bytes carry the server-wide sections to the client`() {
        val wide = DisplayPolicy(
            quality = DisplayPolicy.Quality(720),
            client = DisplayPolicy.Client(enabled = false, maxActiveDisplays = 2),
            debug = DisplayPolicy.Debug(verbose = true),
        ).serverWide()
        val packet = PacketRegistry.decode(PacketRegistry.encode(ServerPolicy(wide.encode()))) as ServerPolicy
        val settings = ServerClientSettings.fromWire(packet.policy)
        assertEquals(false, settings.displaysEnabled)
        assertEquals(2, settings.maxActiveDisplays(0))
        assertEquals(true, settings.verbose)
        assertNull(settings.serverWide?.quality)
    }
}
