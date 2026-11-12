@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.sync

import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.core.protocol.common.PacketRegistry
import com.dreamdisplays.core.protocol.common.ZERO_UUID
import com.dreamdisplays.core.protocol.common.packets.DisplaySync
import com.dreamdisplays.core.protocol.common.toSync
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeedChangesTest {
    private val playing = Timeline(10_000, 1_000, paused = false, durationMs = 60_000, loop = true, rate = 1.0)

    @Test
    fun `scheduled only when every viewer can follow it, nobody sings, the media is not live and a lead is set`() {
        assertTrue(SpeedChanges.schedules(400, viewersCapable = true, karaokeLeads = false, live = false))
        assertFalse(SpeedChanges.schedules(0, viewersCapable = true, karaokeLeads = false, live = false))
        assertFalse(SpeedChanges.schedules(400, viewersCapable = false, karaokeLeads = false, live = false))
        assertFalse(SpeedChanges.schedules(400, viewersCapable = true, karaokeLeads = true, live = false))
        assertFalse(SpeedChanges.schedules(400, viewersCapable = true, karaokeLeads = false, live = true))
    }

    @Test
    fun `lead adds the slowest viewer's latency, capped`() {
        assertEquals(400, SpeedChanges.leadMs(400, 0))
        assertEquals(550, SpeedChanges.leadMs(400, 150))
        assertEquals(400 + SpeedChanges.MAX_LATENCY_LEAD_MS, SpeedChanges.leadMs(400, 5_000))
        assertEquals(0, SpeedChanges.leadMs(-5, -5))
    }

    @Test
    fun `the timeline plays on at the old speed to the switch instant and on from there at the new one`() {
        val now = 3_000L
        val switched = assertNotNull(SpeedChanges.scheduled(playing, 2.0, now, leadMs = 400))
        // Anchored at the instant, where 1x has brought it: no jump
        assertEquals(now + 400, switched.serverTimeMs)
        assertEquals(playing.positionAt(now + 400), switched.positionMs)
        assertEquals(12_400, switched.positionMs)
        assertEquals(2.0, switched.rate)
        assertFalse(switched.paused)
        // After it, the new speed
        assertEquals(12_400 + 2 * 1_000, switched.positionAt(now + 1_400))
    }

    @Test
    fun `slowing down is continuous too`() {
        val fast = playing.copy(rate = 2.0)
        val switched = assertNotNull(SpeedChanges.scheduled(fast, 0.5, 2_000, leadMs = 500))
        assertEquals(fast.positionAt(2_500), switched.positionMs)
        assertEquals(switched.positionMs + 500, switched.positionAt(3_500))
    }

    @Test
    fun `no scheduled switch over the end of a looping timeline, nor for a paused one`() {
        val nearEnd = playing.copy(positionMs = 59_800)
        assertNull(SpeedChanges.scheduled(nearEnd, 2.0, 1_000, leadMs = 400))
        assertNull(SpeedChanges.scheduled(playing.copy(paused = true), 2.0, 3_000, 400))
        // A timeline that does not loop, or has no length yet, can switch anywhere
        assertNotNull(SpeedChanges.scheduled(nearEnd.copy(loop = false), 2.0, 1_000, 400))
        assertNotNull(SpeedChanges.scheduled(nearEnd.copy(durationMs = 0), 2.0, 1_000, 400))
    }

    @Test
    fun `its packet is a start scheduled ahead, marked with the old speed until the instant`() {
        val now = 3_000L
        val switched = assertNotNull(SpeedChanges.scheduled(playing, 1.2, now, leadMs = 400))
        val sync = SpeedChanges.mark(switched.toSync(ZERO_UUID, PlaybackMode.SYNCED, now), playing.rate)
        assertEquals(400, sync.startInMs)
        assertEquals(12_400, sync.currentTimeMs)
        assertEquals(1.2, sync.rate)
        assertEquals(1.0, sync.speedChangeFromRate)
        assertFalse(sync.isPaused)
        // Resent after the instant it is a plain playing timeline
        val later = SpeedChanges.mark(switched.toSync(ZERO_UUID, PlaybackMode.SYNCED, now + 600), playing.rate)
        assertEquals(0, later.startInMs)
        assertEquals(0.0, later.speedChangeFromRate)
        // A paused packet or a plain one is never marked
        assertEquals(0.0, SpeedChanges.mark(playing.toSync(ZERO_UUID, PlaybackMode.SYNCED, now), 1.0).speedChangeFromRate)
    }

    @Test
    fun `a client follows it without a stop only while it plays`() {
        assertTrue(SpeedChanges.followsWithoutStop(1.0, paused = false, startInMs = 400, playing = true))
        assertFalse(SpeedChanges.followsWithoutStop(1.0, paused = false, startInMs = 400, playing = false))
        assertFalse(SpeedChanges.followsWithoutStop(0.0, paused = false, startInMs = 400, playing = true))
        assertFalse(SpeedChanges.followsWithoutStop(1.0, paused = true, startInMs = 400, playing = true))
        assertFalse(SpeedChanges.followsWithoutStop(1.0, paused = false, startInMs = 0, playing = true))
    }

    /** [DisplaySync] as protocol-12 release clients (038a5ab3) declare it: no field 13. */
    @Serializable
    private data class ReleaseDisplaySync(
        @ProtoNumber(3) val isPaused: Boolean = false,
        @ProtoNumber(4) val currentTimeMs: Long = 0,
        @ProtoNumber(9) val rate: Double = 1.0,
        @ProtoNumber(10) val startInMs: Int = 0,
        @ProtoNumber(12) val mediaGen: Long = 0,
    )

    @Test
    fun `the mark round-trips, and a release client reads the packet as the start it also is`() {
        val proto = ProtoBuf { encodeDefaults = false }
        val sync = DisplaySync(currentTimeMs = 12_400, rate = 2.0, startInMs = 400, mediaGen = 7, speedChangeFromRate = 1.0)
        assertEquals(sync, PacketRegistry.decode(PacketRegistry.encode(sync)))
        val old = proto.decodeFromByteArray(ReleaseDisplaySync.serializer(), proto.encodeToByteArray(DisplaySync.serializer(), sync))
        assertEquals(ReleaseDisplaySync(false, 12_400, 2.0, 400, 7), old)
        // Unmarked packets put nothing new on the wire
        val plain = sync.copy(speedChangeFromRate = 0.0)
        assertEquals(
            proto.encodeToByteArray(DisplaySync.serializer(), plain).size + 9,
            proto.encodeToByteArray(DisplaySync.serializer(), sync).size,
        )
    }
}
