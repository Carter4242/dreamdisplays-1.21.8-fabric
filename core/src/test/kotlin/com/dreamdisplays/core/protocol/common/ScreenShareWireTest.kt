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
        val state = ScreenShareState(id, active = true, publishUrl = "rtmp://u:p@relay.example:1935/abc")
        assertEquals(state, PacketRegistry.decode(PacketRegistry.encode(state), PacketDirection.SERVER_TO_CLIENT))
        assertFailsWith<IllegalArgumentException> {
            PacketRegistry.decode(PacketRegistry.encode(state), PacketDirection.CLIENT_TO_SERVER)
        }
    }

    @Test
    fun `type ids stay put`() {
        assertEquals(80, PacketType.SCREEN_SHARE_REQUEST.id)
        assertEquals(81, PacketType.SCREEN_SHARE_STATE.id)
        assertEquals("screen_share", ServerFeature.SCREEN_SHARE.wire)
        assertEquals(PacketType.entries.size, PacketType.entries.map { it.id }.toSet().size)
    }
}
