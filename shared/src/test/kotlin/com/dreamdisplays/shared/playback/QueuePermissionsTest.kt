package com.dreamdisplays.shared.playback

import com.dreamdisplays.api.playback.model.PlaybackMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueuePermissionsTest {
    private fun ctx(
        mode: PlaybackMode = PlaybackMode.SYNCED,
        owner: Boolean = false,
        admin: Boolean = false,
        locked: Boolean = false,
    ) = PlaybackContext(mode, isOwner = owner, isAdmin = admin, isLocked = locked)

    @Test
    fun `adding and skipping follow who may change the video`() {
        assertTrue(PlaybackPermissions.canAddToQueue(ctx()))
        assertTrue(PlaybackPermissions.canSkipQueue(ctx()))
        assertFalse(PlaybackPermissions.canAddToQueue(ctx(locked = true)))
        assertFalse(PlaybackPermissions.canSkipQueue(ctx(locked = true)))
        assertTrue(PlaybackPermissions.canAddToQueue(ctx(locked = true, owner = true)))
        assertTrue(PlaybackPermissions.canSkipQueue(ctx(locked = true, admin = true)))
        // Broadcast: owner / admin only, like the video itself
        assertFalse(PlaybackPermissions.canAddToQueue(ctx(PlaybackMode.BROADCAST, locked = true)))
        assertTrue(PlaybackPermissions.canAddToQueue(ctx(PlaybackMode.BROADCAST, owner = true, locked = true)))
    }

    @Test
    fun `the queue does not run on local displays or during a watch party`() {
        assertFalse(PlaybackPermissions.canAddToQueue(ctx(PlaybackMode.LOCAL, owner = true)))
        assertFalse(PlaybackPermissions.canSkipQueue(ctx(PlaybackMode.LOCAL, admin = true)))
        assertFalse(PlaybackPermissions.canAddToQueue(ctx(PlaybackMode.WATCH_PARTY, owner = true, locked = true)))
        assertTrue(PlaybackPermissions.queueRuns(PlaybackMode.SYNCED))
        assertTrue(PlaybackPermissions.queueRuns(PlaybackMode.BROADCAST))
    }

    @Test
    fun `removal is for the adder, owner or admin and management for owner or admin`() {
        assertTrue(PlaybackPermissions.canRemoveFromQueue(ctx(locked = true), isAdder = true))
        assertFalse(PlaybackPermissions.canRemoveFromQueue(ctx(), isAdder = false))
        assertTrue(PlaybackPermissions.canRemoveFromQueue(ctx(owner = true), isAdder = false))
        assertTrue(PlaybackPermissions.canRemoveFromQueue(ctx(admin = true), isAdder = false))
        assertFalse(PlaybackPermissions.canManageQueue(ctx()))
        assertTrue(PlaybackPermissions.canManageQueue(ctx(owner = true)))
        assertTrue(PlaybackPermissions.canManageQueue(ctx(admin = true)))
    }
}
