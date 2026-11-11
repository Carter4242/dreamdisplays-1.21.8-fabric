@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common

import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import com.dreamdisplays.core.protocol.common.packets.DisplayReadiness
import com.dreamdisplays.core.protocol.common.packets.DisplaySync
import com.dreamdisplays.core.protocol.common.packets.MediaGen
import com.dreamdisplays.core.protocol.common.packets.PlaybackCommand
import com.dreamdisplays.core.protocol.common.packets.ReportDuration
import com.dreamdisplays.core.protocol.common.packets.ReportKind
import com.dreamdisplays.core.protocol.common.packets.ReportRequest
import com.dreamdisplays.core.protocol.common.packets.RequestSync
import com.dreamdisplays.core.protocol.common.packets.SetMode
import com.dreamdisplays.shared.protocol.PacketDirection
import com.dreamdisplays.shared.protocol.ProtocolVersion
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContractV12WireTest {
    private val proto = ProtoBuf { encodeDefaults = false }
    private val id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")

    /** What protocol-11 peers (7522afe5 clients and servers) know of these packets. */
    @Serializable
    private data class SyncV11(
        @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
        @ProtoNumber(3) val isPaused: Boolean = false,
        @ProtoNumber(4) val currentTimeMs: Long = 0,
        @ProtoNumber(11) val karaokeLeadMs: Int = 0,
    )

    @Serializable
    private data class DurationV11(
        @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
        @ProtoNumber(2) val durationMs: Long = 0,
    )

    @Test
    fun `protocol is 12`() {
        assertEquals(12, ProtocolVersion.CURRENT)
    }

    @Test
    fun `new fields round trip`() {
        val packets = listOf(
            DisplayInfo(id, url = "https://youtu.be/a", mediaGen = 77L, world = "minecraft:the_nether"),
            DisplaySync(id, currentTimeMs = 5_000, mediaGen = 77L),
            PlaybackCommand(id, action = 2, positionMs = 9_000, mediaGen = 77L),
            SetMode(id, mode = 1, positionMs = 3_000, mediaGen = 77L),
            DisplayReadiness(id, state = 1, positionMs = 0, latencyMs = 20, mediaGen = 77L),
            ReportDuration(id, 123_456L, "https://youtu.be/a", mediaGen = 77L, observedEnd = true),
            RequestSync(id, knownMediaGen = 76L),
            ReportRequest(id, ReportKind.READINESS or ReportKind.DURATION),
        )
        for (packet in packets) assertEquals(packet, PacketRegistry.decode(PacketRegistry.encode(packet)))
    }

    @Test
    fun `report request only travels to the client and keeps its id`() {
        assertEquals(23, PacketType.REPORT_REQUEST.id)
        val bytes = PacketRegistry.encode(ReportRequest(ZERO_UUID, ReportKind.PRELOAD))
        assertIs<ReportRequest>(PacketRegistry.decode(bytes, PacketDirection.SERVER_TO_CLIENT))
        assertFailsWith<IllegalArgumentException> { PacketRegistry.decode(bytes, PacketDirection.CLIENT_TO_SERVER) }
    }

    @Test
    fun `a protocol 11 peer reads protocol 12 packets and ignores the new fields`() {
        val sync = DisplaySync(id, isPaused = true, currentTimeMs = 4_000, karaokeLeadMs = 300, mediaGen = Long.MAX_VALUE)
        assertEquals(
            SyncV11(id, isPaused = true, currentTimeMs = 4_000, karaokeLeadMs = 300),
            proto.decodeFromByteArray(SyncV11.serializer(), proto.encodeToByteArray(DisplaySync.serializer(), sync)),
        )
        val report = ReportDuration(id, 60_000, "u", mediaGen = 5, observedEnd = true)
        assertEquals(
            DurationV11(id, 60_000),
            proto.decodeFromByteArray(DurationV11.serializer(), proto.encodeToByteArray(ReportDuration.serializer(), report)),
        )
    }

    @Test
    fun `a protocol 11 client's packets decode with an unknown generation`() {
        val old = proto.encodeToByteArray(DurationV11.serializer(), DurationV11(id, 60_000))
        val decoded = proto.decodeFromByteArray(ReportDuration.serializer(), old)
        assertEquals(0L, decoded.mediaGen)
        assertEquals("", decoded.url)
        assertFalse(decoded.observedEnd)
        assertEquals(0L, proto.decodeFromByteArray(RequestSync.serializer(), proto.encodeToByteArray(RequestSync.serializer(), RequestSync(id))).knownMediaGen)
    }

    @Test
    fun `timelines carry the display's generation, scheduled or not`() {
        val playing = Timeline(positionMs = 1_000, serverTimeMs = 900, paused = false)
        assertEquals(9L, playing.toSync(id, PlaybackMode.SYNCED, nowMs = 1_000, mediaGen = 9L).mediaGen)
        val scheduled = Timeline(positionMs = 0, serverTimeMs = 1_300, paused = false)
        val sync = scheduled.toSync(id, PlaybackMode.SYNCED, nowMs = 1_000, mediaGen = 9L)
        assertEquals(300, sync.startInMs)
        assertEquals(9L, sync.mediaGen)
        assertEquals(0L, playing.toSync(id, PlaybackMode.SYNCED, nowMs = 1_000).mediaGen)
    }

    @Test
    fun `an unknown generation never makes a report stale`() {
        assertTrue(MediaGen.matches(0L, 5L))
        assertTrue(MediaGen.matches(5L, 0L))
        assertTrue(MediaGen.matches(5L, 5L))
        assertFalse(MediaGen.matches(4L, 5L))
    }

    @Test
    fun `report kinds are bits and unknown ones are ignored`() {
        val kinds = ReportKind.READINESS or ReportKind.PRELOAD or 64
        assertTrue(ReportKind.has(kinds, ReportKind.READINESS))
        assertTrue(ReportKind.has(kinds, ReportKind.PRELOAD))
        assertFalse(ReportKind.has(kinds, ReportKind.DURATION))
        assertNull(ReportRequest().displayId.takeIf { it != ZERO_UUID })
    }
}
