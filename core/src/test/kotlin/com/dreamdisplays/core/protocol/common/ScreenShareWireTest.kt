package com.dreamdisplays.core.protocol.common

import com.dreamdisplays.core.protocol.common.packets.ScreenShareRequest
import com.dreamdisplays.core.protocol.common.packets.ScreenShareState
import com.dreamdisplays.shared.capability.ServerFeature
import com.dreamdisplays.shared.protocol.PacketDirection
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScreenShareWireTest {
    private val id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")

    @Test
    fun `screen share packets round trip one way only`() {
        val request = ScreenShareRequest(id, start = true)
        assertEquals(request, PacketRegistry.decode(PacketRegistry.encode(request), PacketDirection.CLIENT_TO_SERVER))
        val heartbeat = ScreenShareRequest(id, start = false, heartbeat = true)
        assertEquals(heartbeat, PacketRegistry.decode(PacketRegistry.encode(heartbeat), PacketDirection.CLIENT_TO_SERVER))
        val state = ScreenShareState(id, active = true, publishUrl = "rtmp://u:p@relay.example:1935/abc")
        assertEquals(state, PacketRegistry.decode(PacketRegistry.encode(state), PacketDirection.SERVER_TO_CLIENT))
        assertFailsWith<IllegalArgumentException> {
            PacketRegistry.decode(PacketRegistry.encode(state), PacketDirection.CLIENT_TO_SERVER)
        }
    }

    @Test
    fun `share limits travel and an old state without them decodes to the client defaults`() {
        val state = ScreenShareState(id, active = true, publishUrl = "srt://r:8890?streamid=publish:dd1",
            encoder = ScreenShareState.ENCODER_SOFTWARE, maxFps = 20, maxHeight = 720)
        assertEquals(state, PacketRegistry.decode(PacketRegistry.encode(state), PacketDirection.SERVER_TO_CLIENT))
        val old = ScreenShareState(id, active = true, publishUrl = "srt://r:8890?streamid=publish:dd1")
        val decoded = PacketRegistry.decode(PacketRegistry.encode(old), PacketDirection.SERVER_TO_CLIENT) as ScreenShareState
        assertEquals("", decoded.encoder)
        assertEquals(0, decoded.maxFps)
        assertEquals(0, decoded.maxHeight)
    }

    @Test
    fun `type ids stay put`() {
        assertEquals(80, PacketType.SCREEN_SHARE_REQUEST.id)
        assertEquals(81, PacketType.SCREEN_SHARE_STATE.id)
        assertEquals("screen_share", ServerFeature.SCREEN_SHARE.wire)
        assertEquals("screen_share_heartbeat", ServerFeature.SCREEN_SHARE_HEARTBEAT.wire)
        assertEquals(PacketType.entries.size, PacketType.entries.map { it.id }.toSet().size)
    }

    @Test
    fun `a heartbeat is never a start request`() {
        // A server without the heartbeat feature reads field 3 as unknown: it must see a stop, never a (re)start
        val decoded = PacketRegistry.decode(
            PacketRegistry.encode(ScreenShareRequest(id, heartbeat = true)), PacketDirection.CLIENT_TO_SERVER,
        ) as ScreenShareRequest
        assertEquals(false, decoded.start)
        assertEquals(true, decoded.heartbeat)
    }
}
