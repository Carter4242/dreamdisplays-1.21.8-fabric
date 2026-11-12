package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.core.protocol.common.hasFeature
import com.dreamdisplays.core.protocol.common.packets.ActivityEntryWire
import com.dreamdisplays.core.protocol.common.packets.ActivityState
import com.dreamdisplays.core.protocol.common.packets.MenuPanelRequest
import com.dreamdisplays.core.protocol.common.packets.ViewersState
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.managers.ClientPacketManager
import com.dreamdisplays.shared.capability.ServerFeature
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Client view of the display menu's Activity and Viewers tabs: what the server last sent ([ActivityState],
 * [ViewersState]) and which panels this client asked it for. The menu calls [sync] every frame with what it shows;
 * a request goes out only when that changes, and [close] when the menu closes, so the server sends nothing to a
 * client that is not looking.
 */
object DisplayPanels {
    /** Rows kept per display at most (the server sends no more than its own cap). */
    private const val MAX_ROWS = 1_000

    /** The server keeps an Activity list and lets this player see it. */
    val activitySupported: Boolean get() = ClientPacketManager.serverSnapshot.hasFeature(ServerFeature.ACTIVITY_LOG)

    /** The server lists who watches a display and lets this player see it. */
    val viewersSupported: Boolean get() = ClientPacketManager.serverSnapshot.hasFeature(ServerFeature.VIEWER_LIST)

    /** A display's Activity rows, newest first, and the server clock minus this client's when they arrived. */
    class Activity(val rows: List<ActivityEntryWire>, val clockOffsetMs: Long)

    private val activity = ConcurrentHashMap<UUID, Activity>()
    private val viewers = ConcurrentHashMap<UUID, ViewersState>()

    /** The panels last asked for; null when none (menu closed). */
    @Volatile
    private var asked: MenuPanelRequest? = null

    fun apply(packet: ActivityState) {
        val offset = if (packet.serverTimeMs > 0L) packet.serverTimeMs - System.currentTimeMillis() else 0L
        activity.compute(packet.id) { _, old ->
            val rows = if (packet.replace || old == null) packet.entries
            else (packet.entries + old.rows.filter { row -> packet.entries.none { it.seq == row.seq } })
            Activity(rows.sortedByDescending { it.seq }.take(MAX_ROWS), offset)
        }
    }

    fun apply(packet: ViewersState) {
        viewers[packet.id] = packet
    }

    /** [displayId]'s Activity rows, or null before the server sent any. */
    fun activityOf(displayId: UUID): Activity? = activity[displayId]

    /** Who watches [displayId], or null before the server said. */
    fun viewersOf(displayId: UUID): ViewersState? = viewers[displayId]

    /** True once the server answered the current request for [displayId]'s Activity. */
    fun activityLoaded(displayId: UUID): Boolean = activity.containsKey(displayId)

    /**
     * The menu of [displayId] shows the Activity tab ([activityOpen]) and wants the Viewers list ([viewersOpen]); asks
     * the server when that differs from what was asked last.
     */
    fun sync(displayId: UUID, activityOpen: Boolean, viewersOpen: Boolean) {
        val want = MenuPanelRequest(displayId, activityOpen && activitySupported, viewersOpen && viewersSupported)
        if (want == asked) return
        if (!want.activity && !want.viewers) return close()
        // A list from an earlier opening may be stale: the reply replaces it
        if (want.activity && asked?.activity != true) activity.remove(displayId)
        if (want.viewers && asked?.viewers != true) viewers.remove(displayId)
        asked = want
        Initializer.sendPacket(want)
    }

    /** The menu closed: the server stops sending. */
    fun close() {
        val last = asked ?: return
        asked = null
        if (activitySupported || viewersSupported) Initializer.sendPacket(MenuPanelRequest(last.id))
    }

    /** A (re)loaded server forgot what this client asked for: ask again on the next frame. */
    fun onServerHello() {
        asked = null
    }

    /** Forgets everything (disconnect). */
    fun reset() {
        asked = null
        activity.clear()
        viewers.clear()
    }
}
