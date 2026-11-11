package com.dreamdisplays.media.player

/**
 * What a player does when a VOD stream reaches its end. A display that follows a server timeline never acts on its
 * own end: its stream can end a moment before the shared clock does, and a loop (a seek to 0, which the display
 * reports upstream) would restart the video for every viewer and keep a queue from moving on. It holds the last
 * frame instead, until the timeline moves on (next queue entry, loop wrap, seek, pause).
 */
internal object EndOfStreamPolicy {
    enum class Action {
        /** Restart from the beginning locally (Local displays). */
        LOOP,

        /** Hold the last frame and wait for the server timeline. */
        HOLD,

        /** Stop at the end (non-looping fullscreen on a Local display). */
        END,
    }

    fun onEnd(followsServerTimeline: Boolean, shouldLoopOnEnd: Boolean): Action = when {
        followsServerTimeline -> Action.HOLD
        shouldLoopOnEnd -> Action.LOOP
        else -> Action.END
    }
}
