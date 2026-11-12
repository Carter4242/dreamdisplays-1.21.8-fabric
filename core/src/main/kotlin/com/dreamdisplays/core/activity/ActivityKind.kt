package com.dreamdisplays.core.activity

/**
 * What an Activity row says happened ([com.dreamdisplays.core.protocol.common.packets.ActivityEntryWire.kind]).
 * Wire values are append-only and never change meaning; a client that meets one it does not know skips the row.
 * The fields each kind uses are listed next to it; [automatic] kinds happen by themselves and have no player;
 * [aboutActor] kinds name the player they are about (the sharer, the singer), whoever caused them.
 */
enum class ActivityKind(val wire: Int, val automatic: Boolean = false, val aboutActor: Boolean = false) {
    PLAY(1),
    PAUSE(2),

    /** fromMs -> toMs (video positions). */
    SEEK(3),
    RESTART(4),

    /** rate. */
    SPEED(5),

    /** title / url of the new video. */
    VIDEO_SET(6),

    /** title / url of the video that was taken off. */
    VIDEO_CLEAR(7),

    /** title / url. */
    QUEUE_ADD(10),

    /** count (videos added). */
    QUEUE_ADD_PLAYLIST(11),

    /** title / url. */
    QUEUE_REMOVE(12),

    /** title / url; fromMs / toMs are the entry's old and new 1-based positions. */
    QUEUE_MOVE(13),

    /** title / url of the video that was skipped. */
    QUEUE_SKIP(14),

    /** count (entries removed). */
    QUEUE_CLEAR(15),

    /** flag (on / off). */
    QUEUE_LOOP(16),

    /** text: mode (`local`, `synced`, `broadcast`). */
    MODE(20),

    /** text: access (`everyone`, `region`, `locked`). */
    ACCESS(21),

    /** text: the new name (empty: name removed). */
    RENAME(22),
    SHARE_START(30, aboutActor = true),
    SHARE_STOP(31, aboutActor = true),
    KARAOKE_JOIN(40, aboutActor = true),
    KARAOKE_LEAVE(41, aboutActor = true),

    /** flag: the player started (true) or stopped (false) singing. */
    KARAOKE_STAGE(42, aboutActor = true),

    /** count: players the fullscreen push went to (0 = unknown). */
    FULLSCREEN_START(50),
    FULLSCREEN_END(51),

    /** count: players asked to prepare (0 = unknown). */
    PREPARE(52),

    /** text: `play` / `pause`; toMs: when (server wall time). */
    SCHEDULE_SET(60),
    SCHEDULE_CANCEL(61),

    /** text: `play` / `pause`. */
    SCHEDULE_FIRED(62, automatic = true),

    /** text: policy key (empty: every key reset); text2: new value (empty = reset to the server default). */
    POLICY(70),
    CREATED(80),

    /** count / toMs: new width / height in blocks. */
    RESIZED(81),
    MOVED(82),

    /** title / url of the entry that started. */
    QUEUE_NEXT(100, automatic = true),
    LOOPED(101, automatic = true),
    AUTO_PAUSED(102, automatic = true),

    /** The display carried on where it was after a server restart or plugin reload. */
    RESTORED(103, automatic = true),

    /** A fullscreen push ended by itself (its video ended). */
    FULLSCREEN_ENDED(104, automatic = true);

    companion object {
        private val byWire = entries.associateBy { it.wire }

        /** Decodes [wire], or null for a value this build does not know. */
        fun fromWire(wire: Int): ActivityKind? = byWire[wire]

        /** The row for a playback command. */
        fun ofPlayback(action: com.dreamdisplays.api.playback.model.PlaybackAction): ActivityKind = when (action) {
            com.dreamdisplays.api.playback.model.PlaybackAction.PLAY -> PLAY
            com.dreamdisplays.api.playback.model.PlaybackAction.PAUSE -> PAUSE
            com.dreamdisplays.api.playback.model.PlaybackAction.SEEK -> SEEK
            com.dreamdisplays.api.playback.model.PlaybackAction.RESTART -> RESTART
            com.dreamdisplays.api.playback.model.PlaybackAction.SPEED -> SPEED
        }
    }
}

/** How a player gets a display ([com.dreamdisplays.core.protocol.common.packets.ViewerEntryWire.via]); append-only. */
enum class ViewerVia(val wire: Int) {
    NEARBY(1),
    PIP(2),
    FULLSCREEN(3);

    companion object {
        fun fromWire(wire: Int): ViewerVia = entries.firstOrNull { it.wire == wire } ?: NEARBY
    }
}

/** What a viewer's client is doing with the display ([com.dreamdisplays.core.protocol.common.packets.ViewerEntryWire.status]). */
enum class ViewerStatus(val wire: Int) {
    /** Gets it; no report says more. */
    WATCHING(0),
    LOADING(1),
    BUFFERING(2),
    ERROR(3),

    /** In range without the mod: sees nothing. Listed, not counted. */
    NO_MOD(4);

    companion object {
        fun fromWire(wire: Int): ViewerStatus = entries.firstOrNull { it.wire == wire } ?: WATCHING
    }
}
