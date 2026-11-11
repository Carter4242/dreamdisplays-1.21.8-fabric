package com.dreamdisplays.core.share

import com.dreamdisplays.api.media.source.LiveStreamUrls

/**
 * The `[live]` relay URL templates a server shares to. `{id}` becomes each share's random stream id; with a relay
 * lease service (see [RelayLeaseSync]) the publish template also carries `{key}`, each share's own publish key.
 */
object ScreenShareRelayTemplates {
    const val ID = "{id}"
    const val KEY = "{key}"

    /**
     * Schemes a sharer's client can publish to. RTSP is a relay scheme viewers can play, but the client's publish
     * pipeline only has the FLV (RTMP) and MPEG-TS (SRT) muxers, so an RTSP publish URL fails every share.
     */
    val PUBLISH_SCHEMES: Set<String> = setOf("srt", "rtmp", "rtmps")

    private const val SAMPLE_ID = "dd0sample"
    private const val SAMPLE_KEY = "k0sample"

    /**
     * Why [publish] / [play] cannot be used for sharing, or null when they can. Both empty is "sharing off" (null
     * from [isSet]). [leased]: a relay lease service is configured, so the publish URL must carry `{key}`.
     */
    fun problem(publish: String, play: String, leased: Boolean): String? {
        if (publish.isBlank() || play.isBlank()) return "relay_publish_url and relay_play_url must both be set"
        if (!publish.contains(ID)) return "relay_publish_url has no {id}"
        if (!play.contains(ID)) return "relay_play_url has no {id}"
        if (play.contains(KEY)) return "relay_play_url must not contain {key} (only the sharer may know the key)"
        if (leased && !publish.contains(KEY)) return "relay_publish_url needs {key} when relay_control_url is set"
        if (!leased && publish.contains(KEY)) return "relay_publish_url has {key} but relay_control_url is not set"
        val publishScheme = LiveStreamUrls.schemeOf(publish)
        if (publishScheme !in PUBLISH_SCHEMES) return "relay_publish_url must be srt://, rtmp:// or rtmps://"
        if (!LiveStreamUrls.isValid(publishUrl(publish, SAMPLE_ID, SAMPLE_KEY))) return "relay_publish_url is not a valid URL"
        // Viewers keep a stream private (no thumbnail, history, held frame or preload) only for a live relay scheme
        if (!LiveStreamUrls.isLive(play)) return "relay_play_url must be a live relay URL (srt, rtmp, rtmps or rtsp)"
        if (!LiveStreamUrls.isValid(playUrl(play, SAMPLE_ID))) return "relay_play_url is not a valid URL"
        return null
    }

    /** True when either template is set (both empty means sharing is simply off, which is not a problem). */
    fun isSet(publish: String, play: String): Boolean = publish.isNotBlank() || play.isNotBlank()

    /** The publish URL for stream [id] with publish [key] (an empty key leaves the template's own credentials). */
    fun publishUrl(template: String, id: String, key: String): String =
        LiveStreamUrls.fromTemplate(template, id).replace(KEY, key)

    /** The play URL for stream [id]. */
    fun playUrl(template: String, id: String): String = LiveStreamUrls.fromTemplate(template, id)
}
