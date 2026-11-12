package com.dreamdisplays.shared.capability

import com.dreamdisplays.api.util.WireEnum
import com.dreamdisplays.api.util.wireEnumValueOfOrNull

/** Server capabilities advertised during negotiation; string tokens stay centralized in this enum. */
enum class ServerFeature(override val wire: String) : WireEnum {
    /** Server supports selecting playback modes. */
    MODES("modes"),

    /** Server supports broadcast playback. */
    BROADCAST("broadcast"),

    /**
     * Server can resolve region membership (i.e. `WorldGuard` is installed), so the region access
     * level is a real choice.
     */
    REGION_ACCESS("region_access"),

    /** Server keeps a playback speed on shared timelines and accepts the `SPEED` playback action. */
    PLAYBACK_SPEED("playback_speed"),

    /** Server gates start holds on clients' `DisplayReadiness` reports and schedules starts (`DisplaySync.startInMs`). */
    DISPLAY_READINESS("display_readiness"),

    /** Server runs per-display "Up next" queues (QueueState / QueueAction / QueuePreload). */
    QUEUE("queue"),

    /** Server takes `ClientErrorReport` / `ClientEnvironment` packets (op-only diagnostics); clients send none otherwise. */
    CLIENT_ERROR_REPORTS("client_error_reports"),

    /**
     * Server runs karaoke (Simple Voice Chat installed): takes karaoke latency / offset / mic marker reports and sends
     * clock pings; clients send none otherwise.
     */
    KARAOKE("karaoke"),

    /**
     * This player may share their screen to a display (`ScreenShareRequest` / `ScreenShareState`): the server has a
     * relay configured and the player holds the share permission. Sent per player.
     */
    SCREEN_SHARE("screen_share"),

    /**
     * The server takes screen-share heartbeats (`ScreenShareRequest.heartbeat`) and ends a share whose sharer stops
     * sending them. Sent together with [SCREEN_SHARE]; clients send no heartbeats otherwise.
     */
    SCREEN_SHARE_HEARTBEAT("screen_share_heartbeat"),

    /**
     * The server clears a display's video on `SetVideo(clear = true)` from anyone allowed to set one: it sends an
     * empty URL (with a new media generation) and stops the display's timeline. Clients offer "Clear" only then.
     */
    CLEAR_VIDEO("clear_video"),

    /**
     * This player may see displays' Activity lists (`MenuPanelRequest.activity` / `ActivityState`). Sent per player
     * (`[activity] visible_to`); clients show the Activity tab only then.
     */
    ACTIVITY_LOG("activity_log"),

    /**
     * This player may see who watches a display (`MenuPanelRequest.viewers` / `ViewersState`). Sent per player
     * (`[activity] viewers_visible_to`); clients show the Viewers tab only then.
     */
    VIEWER_LIST("viewer_list");

    companion object {
        /** Playback-related features every server implementation supports unconditionally. */
        val playbackFeatures: List<ServerFeature> = [MODES, BROADCAST, PLAYBACK_SPEED, DISPLAY_READINESS]

        /** Playback-related feature tokens for string-based wire protocols. */
        val playbackFeatureWires: List<String> = playbackFeatures.toWire()

        /** Decodes a feature token, or returns `null` when unknown. */
        fun fromWire(raw: String): ServerFeature? = wireEnumValueOfOrNull(raw, entries)
    }
}

/** Converts feature enums to their wire tokens. */
fun Iterable<ServerFeature>.toWire(): List<String> = map { it.wire }
