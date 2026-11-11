package com.dreamdisplays.shared.playback

import com.dreamdisplays.api.playback.model.PlaybackMode

/** Inputs the permission rules need. */
data class PlaybackContext(
    val mode: PlaybackMode,
    val isOwner: Boolean,
    val isAdmin: Boolean,
    val isLocked: Boolean,
)
