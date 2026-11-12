package com.dreamdisplays.platform.client

import com.dreamdisplays.core.protocol.common.packets.ClearCache
import com.dreamdisplays.core.protocol.common.packets.DisplayDelete
import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import com.dreamdisplays.core.protocol.common.packets.DisplaySync
import com.dreamdisplays.core.protocol.common.packets.DreamPacket
import com.dreamdisplays.core.protocol.common.packets.ScreenShareState
import com.dreamdisplays.core.protocol.common.packets.ServerHello
import com.dreamdisplays.core.protocol.common.packets.ServerPolicy
import com.dreamdisplays.core.protocol.common.packets.SetDisplaysEnabled
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/** What a server packet does while [MasterSwitch] is off. */
internal enum class OffHandling {
    /** Handled as usual: cheap bookkeeping the mod needs when it is switched back on (or a share answer). */
    APPLY,

    /** A display description: only kept ([HeldDescriptions]), to be handled when the mod is switched back on. */
    HOLD,

    /** A timeline: only read for a karaoke lead (this player was made a singer), else dropped. */
    TIMELINE,

    /** Dropped: the server sends it again, or it means nothing to a client that shows no display. */
    DROP,
}

/** The pure rules of [MasterSwitch], kept apart so they can be tested without a game. */
internal object MasterSwitchRules {
    /**
     * What [packet] does while the mod is off. Fullscreen states and preparations are dropped: the server delivers them
     * again once it hears displays are on here. Queue states and timelines come again with each display's sync request.
     */
    fun offHandling(packet: DreamPacket): OffHandling = when (packet) {
        is ServerHello, is SetDisplaysEnabled, is ServerPolicy, is DisplayDelete, is ClearCache, is ScreenShareState ->
            OffHandling.APPLY

        is DisplayInfo -> OffHandling.HOLD
        is DisplaySync -> OffHandling.TIMELINE
        else -> OffHandling.DROP
    }

    /** What the server is told about this client's displays: on only when the mod and this server's switch both are. */
    fun displaysOnForServer(modOn: Boolean, displaysEnabled: Boolean): Boolean = modOn && displaysEnabled

    /**
     * The answer to the server setting displays [enabled] here (`/display on|off`), or null for none: while the mod is
     * off, an "on" is answered with "off", since nothing will show; the server's switch itself is still recorded.
     */
    fun answerToServerSet(modOn: Boolean, enabled: Boolean): Boolean? = if (!modOn && enabled) false else null

    /**
     * The description of [info]'s display to keep while displays are off here, given the one kept so far ([previous]),
     * or null to keep none. A virtual display only exists for a broadcast, and a forced description only for a
     * broadcast or preparation (it loads at any distance): the server sends both again once displays are on here. A
     * forced description of a display in a known world is kept as a plain one, since it is also the newest description
     * of that display for its normal, distance-bound use.
     */
    fun heldDescription(info: DisplayInfo, previous: DisplayInfo?): DisplayInfo? = when {
        info.virtual -> previous
        info.forced -> if (info.world.isNotEmpty()) info.copy(forced = false) else previous
        else -> info
    }

    /** Whether a timeline makes this client leave a karaoke stage while the mod is off (once per display). */
    fun leavesStage(sync: DisplaySync, alreadyLeft: Set<UUID>): Boolean =
        sync.karaokeLeadMs > 0 && sync.id !in alreadyLeft

    /** [infos] nearest first to the player at ([x], [y], [z]), so a "max playing displays" limit keeps the nearest. */
    fun replayOrder(infos: Collection<DisplayInfo>, x: Double, y: Double, z: Double): List<DisplayInfo> =
        infos.sortedBy {
            val dx = it.x + it.width / 2.0 - x
            val dy = it.y + it.height / 2.0 - y
            val dz = it.z - z
            dx * dx + dy * dy + dz * dz
        }
}

/**
 * The latest description of each display, kept while displays are off on this client (the mod switched off, or
 * `/display off`), so they come back as the server last described them once displays are on again, without a relog.
 * One entry per display, replaced in place: what it costs does not grow with the packets that arrive.
 */
internal class HeldDescriptions {
    private val byId = ConcurrentHashMap<UUID, DisplayInfo>()

    /** Keeps [info] in place of what was kept for its display (see [MasterSwitchRules.heldDescription]). */
    fun keep(info: DisplayInfo) {
        byId.compute(info.id) { _, previous -> MasterSwitchRules.heldDescription(info, previous) }
    }

    /** Keeps [info] unless a description of its display is kept already (that one is newer). */
    fun keepIfAbsent(info: DisplayInfo) {
        if (!byId.containsKey(info.id)) keep(info)
    }

    fun forget(displayId: UUID) {
        byId.remove(displayId)
    }

    fun clear() = byId.clear()

    val size: Int get() = byId.size

    operator fun get(displayId: UUID): DisplayInfo? = byId[displayId]

    /** Everything kept, which is then forgotten. */
    fun drain(): List<DisplayInfo> {
        val out = ArrayList<DisplayInfo>(byId.size)
        for (id in byId.keys.toList()) byId.remove(id)?.let(out::add)
        return out
    }
}
