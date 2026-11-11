package com.dreamdisplays.media.player

/**
 * What a session held warm by a pause (parked with its processes and buffered sound) can take in place. A resume
 * simply un-parks it, so anything the parked processes cannot follow has to drop it instead.
 */
internal object WarmPausePolicy {
    /**
     * An audio-track change while [pausedWarm]: the parked audio process plays the old track, and neither the seamless
     * track switch nor the audio restart runs on a parked session, so the session is dropped and the resume reopens.
     */
    fun audioTrackChangeReopens(pausedWarm: Boolean): Boolean = pausedWarm
}
