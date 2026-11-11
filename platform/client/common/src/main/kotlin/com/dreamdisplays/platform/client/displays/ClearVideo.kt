package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.core.protocol.common.hasFeature
import com.dreamdisplays.core.protocol.common.packets.SetVideo
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.managers.ClientPacketManager
import com.dreamdisplays.shared.capability.ServerFeature
import java.util.*

/**
 * Taking a display's video off (`SetVideo(clear = true)`). The server answers with the display's description with an
 * empty URL, which every viewer's player follows like any other video change.
 */
object ClearVideo {
    /** True when this server clears videos ([ServerFeature.CLEAR_VIDEO]); a "Clear" control shows only then. */
    val supported: Boolean get() = ClientPacketManager.serverSnapshot.hasFeature(ServerFeature.CLEAR_VIDEO)

    /** Asks the server to clear [displayId]'s video. Returns false (sends nothing) on a server that cannot. */
    fun request(displayId: UUID): Boolean {
        if (!supported) return false
        Initializer.sendPacket(clearRequest(displayId))
        return true
    }
}

/** The wire request that clears [displayId]'s video. */
internal fun clearRequest(displayId: UUID): SetVideo = SetVideo(displayId, url = "", lang = "", clear = true)
