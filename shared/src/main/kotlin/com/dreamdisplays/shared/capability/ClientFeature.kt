package com.dreamdisplays.shared.capability

import com.dreamdisplays.api.util.WireEnum
import com.dreamdisplays.api.util.wireEnumValueOfOrNull

/**
 * What a client does, advertised in `ClientHello.features` as these string tokens. A server decides how to treat a
 * client from these (and `ClientHello.supports*`), never from its protocol or mod version. Tokens are append-only and
 * never change meaning; a client that lacks one behaves as described under "without it".
 */
enum class ClientFeature(override val wire: String) : WireEnum {
    /**
     * Echoes `DisplayInfo.mediaGen` in everything it says about a display's video (playback commands, mode changes,
     * readiness, durations, `RequestSync.knownMediaGen`), stamped with the generation of the video it actually shows.
     * Without it: every generation field arrives as 0 (the legacy wildcard).
     */
    MEDIA_GEN("media_gen"),

    /**
     * An empty `DisplayInfo.url` stops and unloads the display's video. Without it the old video keeps playing: the
     * server has to send `DisplayDelete` (and the description again) instead.
     */
    CLEAR_VIDEO("clear_video"),

    /** Answers `ReportRequest`. Without it the request is dropped unanswered. */
    REPORT_REQUEST("report_request"),

    /**
     * Sends `ScreenShareRequest.heartbeat` while sharing, when the server advertises `screen_share_heartbeat`.
     * Without it: no heartbeats ever, so a server must not end its shares for lack of them.
     */
    SHARE_HEARTBEAT("share_heartbeat"),

    /** Stamps share starts/stops with `ScreenShareRequest.nonce` and matches `ScreenShareState.nonce`. */
    SHARE_NONCE("share_nonce"),

    /**
     * Applies `ScreenShareState` encoder / maxFps / maxHeight / bitrateKbps / noSound at the start of a share. Without
     * it those limits are ignored (the share runs on the client's own settings, with sound).
     */
    SHARE_LIMITS("share_limits"),

    /**
     * Reports `ReadinessState.UNHOLDABLE` for a display it will not hold ready (live, unseekable, failed, or beyond
     * the displays it holds). Without it such a display reports NOT_READY and a start hold waits its whole deadline.
     */
    READINESS_UNHOLDABLE("readiness_unholdable"),

    /**
     * Answers a `ServerHello` with `greeting = true` with its hello at once, negotiated or not. Without it a greeting
     * is answered only when it does not look like the reply to the client's own hello (timing).
     */
    SERVER_GREETING("server_greeting"),

    /**
     * Applies the policy levers that came with this token: `client.videoDecoder`, `client.gpuYuv`,
     * `client.textureUpload`, `client.audioBackend`, `client.disabledResolvers`, `client.ffmpegSource`,
     * `client.ytDlpSource`, `client.ytDlpJsRuntime`, `client.queuePreload`, `client.autoWarm`, `sync.correctiveSeeks`,
     * `sync.cheapSeeks`; and reads
     * a policy section with one unreadable field as the rest of that section. Without it those fields are ignored.
     */
    POLICY_LEVERS("policy_levers"),

    /**
     * Follows `DisplaySync.speedChangeFromRate`: a speed change scheduled ahead plays on at the old speed and switches
     * at the start instant, without stopping. Without it a speed change holds every viewer paused while they restart.
     */
    SCHEDULED_SPEED("scheduled_speed");

    companion object {
        /** Decodes a feature token, or returns `null` when unknown. */
        fun fromWire(raw: String): ClientFeature? = wireEnumValueOfOrNull(raw, entries)
    }
}
