package com.dreamdisplays.api.media.search

import com.dreamdisplays.api.media.source.MediaPlatform
import com.dreamdisplays.api.media.source.YouTubeUrls

/**
 * Describes a single video returned by a search or related-video query.
 *
 * @since 1.6.x
 */
data class MediaSearchResult(
    /** Result ID. */
    val id: String,

    /** Video title. */
    val title: String,

    /** Uploader. */
    val uploader: String?,

    /** Video duration. */
    val durationSec: Long?,

    /** View count of video. */
    val viewCount: Long?,

    /** Like count of video. */
    val likeCount: Long? = null,

    /** Published (date format) */
    val publishedText: String? = null,

    /** Published (days format). */
    val publishedDaysAgo: Int? = null,

    /** Overrides [getWatchUrl] for non-YouTube results (e.g. Twitch), which have no derivable watch URL. */
    val watchUrlOverride: String? = null,

    /** Overrides [getThumbnailUrl] for non-YouTube results, which have no derivable thumbnail URL. */
    val thumbnailUrlOverride: String? = null,

    /** True when this result comes from Twitch. */
    val isTwitch: Boolean = false,

    /** True when this result is a custom link pasted by the player (no platform metadata). */
    val isCustom: Boolean = false,

    /** Service this result comes from; defaults to YOUTUBE. */
    val platform: MediaPlatform = MediaPlatform.YOUTUBE,

    /** The uploader / channel's avatar image URL, or null when unavailable. */
    val channelAvatarUrl: String? = null,

    /** True when the uploader/channel carries a platform "verified" badge. */
    val isVerified: Boolean = false,

    /** True when this result is a currently-live broadcast. */
    val isLive: Boolean = false,
) {
    /** True for a real YouTube result: [id] is a YouTube video id, so watch and thumbnail URLs can be derived from it. */
    val isYouTubeResult: Boolean
        get() = !isCustom && !isTwitch && platform == MediaPlatform.YOUTUBE && YouTubeVideoId.isValid(id)

    /** Returns true if the video was published within the last [daysWindow] days. */
    fun isRecent(daysWindow: Int): Boolean =
        publishedDaysAgo != null && publishedDaysAgo >= 0 && publishedDaysAgo <= daysWindow

    /**
     * Returns [watchUrlOverride] if set, else the YouTube watch URL for a YouTube result, else [id] itself (a pasted
     * link's id is its URL). Never throws.
     */
    fun getWatchUrl(): String =
        watchUrlOverride ?: (if (isYouTubeResult) YouTubeUrls.watchUrl(id) else null) ?: id

    /**
     * Returns [thumbnailUrlOverride] if set, else the YouTube thumbnail URL for a YouTube result, else null (a pasted
     * link or a platform card without metadata has none). Never throws.
     */
    fun getThumbnailUrl(): String? =
        thumbnailUrlOverride ?: if (isYouTubeResult) YouTubeUrls.thumbnailUrl(id) else null

    /** Returns a formatted HH:MM:SS duration string, or empty if unavailable. */
    fun formatDuration(): String {
        val s = durationSec ?: return ""
        if (s <= 0) return ""
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%d:%02d", m, sec)
    }

    /** Returns formatted view count (e.g. "1.2M views"), or empty if unavailable. */
    fun formatViews(): String {
        val v = viewCount ?: return ""
        if (v <= 0) return ""
        return when {
            v >= 1_000_000_000L -> String.format("%.1fB views", v / 1_000_000_000.0)
            v >= 1_000_000L -> String.format("%.1fM views", v / 1_000_000.0)
            v >= 1_000L -> String.format("%.1fK views", v / 1_000.0)
            else -> "$v views"
        }
    }

    /** Returns a formatted like count string (e.g. "42K"), or empty if unavailable. */
    fun formatLikes(): String {
        val l = likeCount ?: return ""
        if (l <= 0) return ""
        return when {
            l >= 1_000_000_000L -> String.format("%.1fB", l / 1_000_000_000.0)
            l >= 1_000_000L -> String.format("%.1fM", l / 1_000_000.0)
            l >= 1_000L -> String.format("%.1fK", l / 1_000.0)
            else -> l.toString()
        }
    }
}
