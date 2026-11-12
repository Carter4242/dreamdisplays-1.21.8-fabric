package com.dreamdisplays.core.protocol.common

import com.dreamdisplays.core.activity.ActivityKind
import com.dreamdisplays.core.activity.PanelVisibility
import com.dreamdisplays.core.activity.ViewerStatus
import com.dreamdisplays.core.activity.ViewerVia
import com.dreamdisplays.core.protocol.common.packets.ActivityEntryWire
import com.dreamdisplays.core.protocol.common.packets.ActivityState
import com.dreamdisplays.core.protocol.common.packets.MenuPanelRequest
import com.dreamdisplays.core.protocol.common.packets.ViewerEntryWire
import com.dreamdisplays.core.protocol.common.packets.ViewersState
import com.dreamdisplays.shared.capability.ClientFeature
import com.dreamdisplays.shared.capability.ServerFeature
import com.dreamdisplays.shared.protocol.PacketDirection
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MenuPanelWireTest {
    private val id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
    private val alice = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210")

    private val activity = ActivityState(
        id,
        listOf(
            ActivityEntryWire(7, 1_700_000_000_000, ActivityKind.SEEK.wire, alice, "Alice", fromMs = 206_000, toMs = 3_730_000),
            ActivityEntryWire(6, 1_699_999_999_000, ActivityKind.VIDEO_SET.wire, alice, "Alice", "Song ✓", "https://youtu.be/x"),
            ActivityEntryWire(5, 1_699_999_998_000, ActivityKind.LOOPED.wire),
            ActivityEntryWire(4, 1, ActivityKind.SPEED.wire, rate = 1.5, console = true, text = "t", text2 = "u", count = 3, flag = true),
        ),
        replace = true,
        serverTimeMs = 1_700_000_000_500,
    )

    @Test
    fun `panel packets round trip`() {
        assertEquals(activity, PacketRegistry.decode(PacketRegistry.encode(activity)))
        assertEquals(ActivityState(), PacketRegistry.decode(PacketRegistry.encode(ActivityState())))
        val request = MenuPanelRequest(id, activity = true, viewers = true)
        assertEquals(request, PacketRegistry.decode(PacketRegistry.encode(request)))
        assertEquals(MenuPanelRequest(id), PacketRegistry.decode(PacketRegistry.encode(MenuPanelRequest(id))))
        val viewers = ViewersState(
            id,
            listOf(
                ViewerEntryWire(alice, "Alice", ViewerVia.PIP.wire, ViewerStatus.LOADING.wire),
                ViewerEntryWire(id, "Bob", ViewerVia.NEARBY.wire, ViewerStatus.NO_MOD.wire),
            ),
            total = 1,
        )
        assertEquals(viewers, PacketRegistry.decode(PacketRegistry.encode(viewers)))
    }

    @Test
    fun `ids and directions are fixed`() {
        assertEquals(90, PacketType.MENU_PANEL_REQUEST.id)
        assertEquals(91, PacketType.ACTIVITY_STATE.id)
        assertEquals(92, PacketType.VIEWERS_STATE.id)
        assertIs<ActivityState>(PacketRegistry.decode(PacketRegistry.encode(activity), PacketDirection.SERVER_TO_CLIENT))
        assertFailsWith<IllegalArgumentException> {
            PacketRegistry.decode(PacketRegistry.encode(activity), PacketDirection.CLIENT_TO_SERVER)
        }
        assertFailsWith<IllegalArgumentException> {
            PacketRegistry.decode(PacketRegistry.encode(MenuPanelRequest(id)), PacketDirection.SERVER_TO_CLIENT)
        }
        assertEquals("activity_log", ClientFeature.ACTIVITY_LOG.wire)
        assertEquals("viewer_list", ClientFeature.VIEWER_LIST.wire)
        assertEquals("activity_log", ServerFeature.ACTIVITY_LOG.wire)
        assertEquals("viewer_list", ServerFeature.VIEWER_LIST.wire)
    }

    @Test
    fun `a full list of the largest rows stays small`() {
        val big = ActivityEntryWire(
            Long.MAX_VALUE, Long.MAX_VALUE, ActivityKind.VIDEO_SET.wire, alice, "n".repeat(32), "t".repeat(120), "u".repeat(300),
            text = "x".repeat(64), text2 = "y".repeat(64),
        )
        val bytes = PacketRegistry.encode(ActivityState(id, List(100) { big }, replace = true, serverTimeMs = 1)).size
        assertTrue(bytes < 80_000, "$bytes bytes")
    }

    @Test
    fun `visibility rule`() {
        assertEquals(PanelVisibility.EVERYONE, PanelVisibility.parse(null))
        assertEquals(PanelVisibility.EVERYONE, PanelVisibility.parse("bogus"))
        assertEquals(PanelVisibility.ADMINS, PanelVisibility.parse(" Admins "))
        assertEquals(PanelVisibility.NOBODY, PanelVisibility.parse("nobody"))
        assertTrue(PanelVisibility.mayView(PanelVisibility.EVERYONE, isAdmin = false, present = true))
        assertFalse(PanelVisibility.mayView(PanelVisibility.EVERYONE, isAdmin = true, present = false))
        assertFalse(PanelVisibility.mayView(PanelVisibility.ADMINS, isAdmin = false, present = true))
        assertTrue(PanelVisibility.mayView(PanelVisibility.ADMINS, isAdmin = true, present = true))
        assertFalse(PanelVisibility.mayView(PanelVisibility.NOBODY, isAdmin = true, present = true))
    }
}
