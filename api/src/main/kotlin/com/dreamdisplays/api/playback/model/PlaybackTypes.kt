package com.dreamdisplays.api.playback.model

import kotlinx.serialization.Serializable

/**
 * How a display drives its playback timeline.
 *
 * @since 1.8.x
 */
@Serializable
enum class PlaybackMode(
    /** The append-only wire value for this mode (also the stored value); wire value 2 is retired, never reuse it. */
    val wire: Int,
) {
    /** No synchronization: every client plays independently of its own saved position. */
    LOCAL(0),

    /** Server-authoritative shared timeline; nearby players join the live position. */
    SYNCED(1),

    /** Persistent server-driven public screen: loops forever, capped quality, always locked. */
    BROADCAST(3),

    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        /** The mode for [wire], or [LOCAL] for unknown values (forward-compat with newer peers). */
        fun fromWire(wire: Int): PlaybackMode = byWire[wire] ?: LOCAL

        /** Every mode a display can hold. */
        val baseModes: List<PlaybackMode> = entries
    }
}

/**
 * Who may change a display's playback and settings.
 *
 * @since 1.10.x
 */
@Serializable
enum class DisplayAccess {
    /** Any player may change the display, whether or not they belong to the region it stands in. */
    EVERYONE,

    /** Only members (and owners) of the `WorldGuard` region the display stands in. */
    REGION,

    /** Only the display's owner and server admins. */
    LOCKED,

    ;

    /** The append-only wire value for this level. */
    val wire: Int get() = ordinal

    companion object {
        private val byWire = entries.associateBy { it.ordinal }

        val DEFAULT: DisplayAccess = LOCKED

        fun fromWire(wire: Int): DisplayAccess = byWire[wire] ?: DEFAULT

        fun fromLegacyLocked(locked: Boolean): DisplayAccess = if (locked) LOCKED else EVERYONE
    }
}

/** A playback intent a client sends to a server-authoritative timeline (`PlaybackCommand`). */
enum class PlaybackAction {
    PLAY, PAUSE, SEEK, RESTART,

    /** Change the playback speed to `PlaybackCommand.rate` (servers older than 1.10.x ignore it). */
    SPEED;

    /** The append-only wire value for this action. */
    val wire: Int get() = ordinal

    companion object {
        private val byWire = entries.associateBy { it.ordinal }

        /** The action for [wire], or null for unknown values (ignore unknown intents). */
        fun fromWire(wire: Int): PlaybackAction? = byWire[wire]
    }
}
