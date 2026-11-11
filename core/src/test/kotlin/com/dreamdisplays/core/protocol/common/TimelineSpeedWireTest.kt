@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common

import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.core.protocol.common.packets.DisplaySync
import com.dreamdisplays.core.protocol.common.packets.PlaybackCommand
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineSpeedWireTest {
    private val proto = ProtoBuf { encodeDefaults = false }

    /** [DisplaySync] as clients before speed control declare it. */
    @Serializable
    private data class OldDisplaySync(
        @ProtoNumber(2) val isSync: Boolean = false,
        @ProtoNumber(3) val isPaused: Boolean = false,
        @ProtoNumber(4) val currentTimeMs: Long = 0,
        @ProtoNumber(5) val durationMs: Long = 0,
        @ProtoNumber(6) val serverTimeMs: Long = 0,
        @ProtoNumber(7) val loop: Boolean = false,
        @ProtoNumber(8) val mode: Int = 0,
    )

    /** [PlaybackCommand] as servers before speed control declare it. */
    @Serializable
    private data class OldPlaybackCommand(
        @ProtoNumber(2) val action: Int = 0,
        @ProtoNumber(3) val positionMs: Long = 0,
    )

    @Test
    fun syncCarriesRateAndRoundTrips() {
        val original = Timeline(2_000, 50_000, paused = false, durationMs = 600_000, loop = true, rate = 1.5)
        val sync = original.toSync(ZERO_UUID, PlaybackMode.SYNCED, nowMs = 52_000)
        assertEquals(1.5, sync.rate)
        assertEquals(5_000, sync.currentTimeMs) // 2 s anchor + 2 s at 1.5x
        val decoded = PacketRegistry.decode(PacketRegistry.encode(sync)) as DisplaySync
        assertEquals(sync, decoded)
        assertEquals(original.anchoredAt(52_000), decoded.toTimeline())
    }

    @Test
    fun normalSpeedPutsNothingNewOnTheWire() {
        val sync = Timeline(2_000, 50_000, paused = false).toSync(ZERO_UUID, PlaybackMode.SYNCED, 50_000)
        val bytes = proto.encodeToByteArray(DisplaySync.serializer(), sync)
        assertEquals(bytes.toList(), proto.encodeToByteArray(DisplaySync.serializer(), sync.copy(rate = 1.0)).toList())
        val old = proto.decodeFromByteArray(OldDisplaySync.serializer(), bytes)
        assertEquals(2_000, old.currentTimeMs)
    }

    @Test
    fun packetFromOlderServerDefaultsToNormalSpeed() {
        val bytes = proto.encodeToByteArray(OldDisplaySync.serializer(), OldDisplaySync(currentTimeMs = 7_000))
        val sync = proto.decodeFromByteArray(DisplaySync.serializer(), bytes)
        assertEquals(1.0, sync.rate)
        assertEquals(1.0, sync.toTimeline().rate)
    }

    @Test
    fun olderClientDecodesSpedUpSyncWithoutError() {
        val sync = Timeline(2_000, 0, paused = false, rate = 2.0).toSync(ZERO_UUID, PlaybackMode.SYNCED, 1_000)
        val old = proto.decodeFromByteArray(OldDisplaySync.serializer(), proto.encodeToByteArray(DisplaySync.serializer(), sync))
        assertEquals(4_000, old.currentTimeMs)
        assertEquals(false, old.isPaused)
    }

    @Test
    fun speedCommandRoundTripsAndOlderServersIgnoreTheAction() {
        val cmd = PlaybackCommand(ZERO_UUID, PlaybackAction.SPEED.wire, positionMs = 1_000, rate = 0.25)
        assertEquals(cmd, PacketRegistry.decode(PacketRegistry.encode(cmd)))
        val old = proto.decodeFromByteArray(OldPlaybackCommand.serializer(), proto.encodeToByteArray(PlaybackCommand.serializer(), cmd))
        // An older server knows actions 0..3 only; PlaybackAction.fromWire there returns null and drops it
        assertEquals(4, old.action)
        assertEquals(PlaybackAction.SPEED, PlaybackAction.fromWire(4))
        assertEquals(1.0, PlaybackCommand(ZERO_UUID, PlaybackAction.SEEK.wire).rate)
    }
}
