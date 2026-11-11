package com.dreamdisplays.core.protocol.common

import com.dreamdisplays.core.protocol.common.packets.ClientHello
import com.dreamdisplays.core.protocol.common.packets.DisplaySync
import com.dreamdisplays.core.protocol.common.packets.KaraokeClockPing
import com.dreamdisplays.core.protocol.common.packets.KaraokeClockPong
import com.dreamdisplays.core.protocol.common.packets.KaraokeLatencyReport
import com.dreamdisplays.core.protocol.common.packets.KaraokeMicMarkers
import com.dreamdisplays.core.protocol.common.packets.KaraokeOffsetReport
import com.dreamdisplays.shared.capability.ServerFeature
import com.dreamdisplays.shared.protocol.PacketDirection
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalSerializationApi::class)
class KaraokeWireTest {
    private val id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")

    @Test
    fun `karaoke packets round trip`() {
        val packets = listOf(
            KaraokeLatencyReport(
                trackOutputMs = 42, trackOutputMeasured = true, trackQueuedMs = 180, voiceChat = true, captureMs = 50,
                voiceBufferMs = 100, voiceOutputMs = 38, voiceOutputMeasured = true,
            ),
            KaraokeLatencyReport(),
            KaraokeOffsetReport(id, offsetMs = -37, confidence = 0.62, windowMs = 8_000),
            KaraokeClockPong(seq = 9, serverNanos = 123_456_789_012L, clientReceiveNanos = -5L, clientSendNanos = 77L),
            KaraokeMicMarkers(listOf(10L, 20L, 30L), listOf(1_000_000_000L, 1_200_000_000L, 1_400_000_000L)),
        )
        for (packet in packets) {
            assertEquals(packet, PacketRegistry.decode(PacketRegistry.encode(packet), PacketDirection.CLIENT_TO_SERVER))
        }
        val ping = KaraokeClockPing(seq = 3, serverNanos = 98_765_432_100L)
        assertEquals(ping, PacketRegistry.decode(PacketRegistry.encode(ping), PacketDirection.SERVER_TO_CLIENT))
        assertFailsWith<IllegalArgumentException> {
            PacketRegistry.decode(PacketRegistry.encode(ping), PacketDirection.CLIENT_TO_SERVER)
        }
        // Unknown figures are -1, also when absent on the wire
        assertEquals(-1, KaraokeLatencyReport().trackOutputMs)
    }

    @Test
    fun `type ids stay put`() {
        assertEquals(70, PacketType.KARAOKE_LATENCY_REPORT.id)
        assertEquals(71, PacketType.KARAOKE_OFFSET_REPORT.id)
        assertEquals(72, PacketType.KARAOKE_CLOCK_PING.id)
        assertEquals(73, PacketType.KARAOKE_CLOCK_PONG.id)
        assertEquals(74, PacketType.KARAOKE_MIC_MARKERS.id)
        assertEquals("karaoke", ServerFeature.KARAOKE.wire)
    }

    @Test
    fun `the singer's lead rides on the timeline and older peers ignore it`() {
        val sync = DisplaySync(id = id, currentTimeMs = 12_000, serverTimeMs = 50_000, karaokeLeadMs = 410)
        assertEquals(sync, PacketRegistry.decode(PacketRegistry.encode(sync)))
        // A timeline from an older server has no lead: not singing
        val old = ProtoBuf.encodeToByteArray(DisplaySync.serializer(), DisplaySync(id = id, currentTimeMs = 12_000))
        assertEquals(0, ProtoBuf.decodeFromByteArray(DisplaySync.serializer(), old).karaokeLeadMs)
        val hello = ClientHello(modVersion = "1.0", supportsKaraoke = true)
        assertEquals(hello, PacketRegistry.decode(PacketRegistry.encode(hello)))
        assertEquals(false, ClientHello(modVersion = "1.0").supportsKaraoke)
    }
}
