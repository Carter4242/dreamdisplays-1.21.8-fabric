package com.dreamdisplays.core.karaoke

import com.dreamdisplays.core.protocol.common.packets.DisplaySync
import java.util.*

/**
 * What goes out when a player starts or stops singing on a display, or their lead changes: exactly one timeline, the
 * display's current one moved by their new lead (0 = the normal timeline), to that player alone, and only if they watch
 * the display ([audience]: near it, or from afar in fullscreen or PiP). It is the display's own state (playing stays playing): never a paused-first resync, which
 * restarts the player and leaves the singer without sound for seconds. Everyone else's timeline did not change, so
 * they get nothing. A new-lead client jumps there in place ([DisplaySync.karaokeLeadMs]); an older one corrects it as
 * drift.
 */
object LeadResync {
    fun packetsFor(
        playerId: UUID, audience: Collection<UUID>, current: DisplaySync?, newLeadMs: Long,
    ): List<Pair<UUID, DisplaySync>> {
        if (current == null || playerId !in audience) return emptyList()
        return listOf(playerId to KaraokeTiming.withLead(current, newLeadMs))
    }
}
