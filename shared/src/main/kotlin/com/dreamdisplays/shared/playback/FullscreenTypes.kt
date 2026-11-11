package com.dreamdisplays.shared.playback

import kotlinx.serialization.Serializable

/**
 * Visual sub-mode of the fullscreen overlay.
 *
 * Travels on the wire as its [ordinal] int ([wire] / [fromWire]); ordinals are append-only.
 */
@Serializable
enum class FullscreenMode {
    /** Video letterboxed inside small margins; the game stays visible around the edges. */
    STANDARD,

    /** Video covers the entire screen on an opaque backdrop; nothing of the game shows through. */
    IMMERSIVE;

    /** The append-only wire value for this mode. */
    val wire: Int get() = ordinal

    companion object {
        private val byWire = entries.associateBy { it.ordinal }

        /** The mode for [wire], or [STANDARD] for unknown values (forward-compat with newer peers). */
        fun fromWire(wire: Int): FullscreenMode = byWire[wire] ?: STANDARD
    }
}

/**
 * Client acknowledgement of a fullscreen-broadcast transition.
 *
 * Travels on the wire as its [ordinal] int; ordinals are append-only.
 */
enum class FullscreenAckAction {
    /** The overlay was shown to the player. */
    SHOWN,

    /** The player closed an unforced broadcast overlay. */
    DISMISSED,

    /** The player minimized a forced broadcast overlay to PiP. */
    MINIMIZED;

    /** The append-only wire value for this action. */
    val wire: Int get() = ordinal

    companion object {
        private val byWire = entries.associateBy { it.ordinal }

        /** The action for [wire], or [SHOWN] for unknown values. */
        fun fromWire(wire: Int): FullscreenAckAction = byWire[wire] ?: SHOWN
    }
}

/**
 * How far a client got with a fullscreen preparation (`/display fullscreen prepare`).
 *
 * Travels on the wire as its [ordinal] int; ordinals are append-only.
 */
enum class FullscreenPrepareState {
    /** Loading the video, or waiting for the display to arrive. */
    LOADING,

    /** Loaded and held so that a start plays at once. */
    READY,

    /** The video failed to load. */
    FAILED,

    /** Displays are switched off for this player, by them or by the server. */
    DISABLED;

    /** The append-only wire value for this state. */
    val wire: Int get() = ordinal

    companion object {
        private val byWire = entries.associateBy { it.ordinal }

        /** The state for [wire], or [LOADING] for unknown values. */
        fun fromWire(wire: Int): FullscreenPrepareState = byWire[wire] ?: LOADING
    }
}
