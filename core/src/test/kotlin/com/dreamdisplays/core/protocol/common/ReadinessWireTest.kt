@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common

import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.core.protocol.common.packets.ClientHello
import com.dreamdisplays.core.protocol.common.packets.DisplayReadiness
import com.dreamdisplays.core.protocol.common.packets.DisplaySync
import com.dreamdisplays.shared.capability.ServerFeature
import com.dreamdisplays.shared.playback.ReadinessState
import com.dreamdisplays.shared.protocol.PacketDirection
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReadinessWireTest {
    private val proto = ProtoBuf { encodeDefaults = false }
    private val id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")

    @Test
    fun `readiness report round trips and only travels to the server`() {
        val report = DisplayReadiness(id, ReadinessState.READY.wire, 12_345L, 42)
        assertEquals(report, PacketRegistry.decode(PacketRegistry.encode(report)))
        assertEquals(44, PacketType.DISPLAY_READINESS.id)
        val bytes = PacketRegistry.encode(report)
        assertIs<DisplayReadiness>(PacketRegistry.decode(bytes, PacketDirection.CLIENT_TO_SERVER))
        assertFailsWith<IllegalArgumentException> { PacketRegistry.decode(bytes, PacketDirection.SERVER_TO_CLIENT) }
    }

    @Test
    fun `a start scheduled ahead is sent as playing from its start frame with the wait`() {
        val start = Timeline(positionMs = 10_000, serverTimeMs = 1_120, paused = false)
        val sync = start.toSync(id, PlaybackMode.SYNCED, nowMs = 1_000)
        assertFalse(sync.isPaused)
        assertEquals(10_000, sync.currentTimeMs)
        assertEquals(1_000, sync.serverTimeMs)
        assertEquals(120, sync.startInMs)
        // Once due it is an ordinary running timeline
        val later = start.toSync(id, PlaybackMode.SYNCED, nowMs = 1_620)
        assertEquals(0, later.startInMs)
        assertEquals(10_500, later.currentTimeMs)
    }

    @Test
    fun `a timeline anchored ahead has not started yet`() {
        val start = Timeline(positionMs = 10_000, serverTimeMs = 1_120, paused = false)
        assertEquals(10_000, start.positionAt(1_000))
        assertEquals(10_000, start.positionAt(1_120))
        assertEquals(10_080, start.positionAt(1_200))
        // Pausing during the wait keeps the start frame
        assertEquals(Timeline(10_000, 1_050, paused = true), start.withPaused(true, 1_050))
    }

    @Test
    fun `a client that does not know the wait reads a start from the frame now`() {
        val bytes = proto.encodeToByteArray(DisplaySync.serializer(), DisplaySync(id, currentTimeMs = 10_000, serverTimeMs = 1_000, startInMs = 120))
        // Field 9 is all that is new: dropping it leaves an ordinary "playing from 10 s" sync
        val withoutWait = proto.encodeToByteArray(DisplaySync.serializer(), DisplaySync(id, currentTimeMs = 10_000, serverTimeMs = 1_000))
        assertTrue(bytes.size > withoutWait.size)
        assertTrue(bytes.copyOfRange(0, withoutWait.size).contentEquals(withoutWait))
    }

    @Test
    fun `readiness is advertised by both sides`() {
        assertFalse(ClientHello().supportsReadiness)
        val hello = PacketRegistry.decode(PacketRegistry.encode(ClientHello(supportsReadiness = true))) as ClientHello
        assertTrue(hello.supportsReadiness)
        assertTrue(ServerFeature.DISPLAY_READINESS.wire in ServerFeature.playbackFeatureWires)
    }
}
