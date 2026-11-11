package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.shared.playback.PlaybackContext
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.shared.playback.PlaybackPermissions
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import java.util.*

/**
 * Builds the [PlaybackContext] the shared [PlaybackPermissions] rules
 * consume. Used by every server entry point that enforces permissions.
 */
object PlaybackContexts {
    /**
     * The permission context for [senderId] acting on [display]; [isAdmin] comes from the platform.
     */
    fun of(
        display: DisplayData, senderId: UUID, isAdmin: Boolean,
        territoryMember: () -> Boolean = { false },
    ): PlaybackContext {
        val mode = display.mode
        val forcedLock = mode == PlaybackMode.BROADCAST
        val locked = when {
            forcedLock -> true
            display.access == DisplayAccess.EVERYONE -> false
            display.access == DisplayAccess.REGION -> !territoryMember()
            else -> true
        }
        return PlaybackContext(
            mode = mode,
            isOwner = display.ownerId == senderId,
            isAdmin = isAdmin,
            isLocked = locked,
        )
    }
}
