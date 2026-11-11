package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.core.protocol.common.packets.ClearCache
import com.dreamdisplays.core.protocol.common.packets.DisplayDelete
import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import com.dreamdisplays.core.protocol.common.packets.DreamPacket
import com.dreamdisplays.core.sync.EmptyDisplayRepeats
import java.util.*

/** The server's [EmptyDisplayRepeats]: how often clients older than protocol 12 hear about empty displays. */
object EmptyDisplayDescriptions {
    private val repeats = EmptyDisplayRepeats()

    /** True when [info] goes to [playerId] now. */
    fun sendsTo(playerId: UUID, info: DisplayInfo): Boolean = repeats.shouldSend(
        playerId, info, V2PlayerTracker.helloOf(playerId)?.protocolVersion ?: 0, System.currentTimeMillis(),
    )

    /** [packet] goes to [playerId]: one that drops displays makes their next description go out at once. */
    fun onSent(playerId: UUID, packet: DreamPacket) {
        when (packet) {
            is DisplayDelete -> repeats.forget(playerId, packet.id)
            is ClearCache -> packet.ids.forEach { repeats.forget(playerId, it) }
            else -> Unit
        }
    }

    fun forgetPlayer(playerId: UUID) = repeats.forgetPlayer(playerId)
}
