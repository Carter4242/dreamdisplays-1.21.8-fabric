package com.dreamdisplays.media.source.youtube

import com.dreamdisplays.api.media.source.YouTubeUrls
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit

/**
 * How YouTube is resolved for one video: whether `NewPipeExtractor` goes first or only as a last resort, and which
 * `yt-dlp` clients run (one [primaryClient] alone, then [fallbackClients] in parallel).
 */
data class YtResolvePlan(
    val newPipeFirst: Boolean,
    val primaryClient: String?,
    val fallbackClients: List<String>,
) {
    /** True when this plan steers away from the sources that were refused. */
    val escalated: Boolean get() = !newPipeFirst

    companion object {
        /** The usual plan: NewPipe (its URLs come from the `visionos` client), then `yt-dlp` `visionos`, then the rest. */
        val DEFAULT = YtResolvePlan(true, "visionos", ["android_vr", "ios", "tv", "android"])

        /**
         * The plan after [refusals] 403s on this video's stream URLs. The first refusal only asks for a fresh resolve
         * (an expired or one-off URL); a repeated one skips NewPipe and `visionos` for `android_vr`; the next races the
         * remaining clients (their muxed 360p stream is a different kind of URL again). It then starts over, since
         * these refusals are often passing.
         */
        fun forRefusals(refusals: Int): YtResolvePlan {
            if (refusals <= 0) return DEFAULT
            return when ((refusals - 1) % 3) {
                0 -> DEFAULT
                1 -> YtResolvePlan(false, "android_vr", ["ios", "tv", "android"])
                else -> YtResolvePlan(false, null, ["ios", "tv", "android"])
            }
        }
    }
}

/**
 * 403s the player got on a YouTube video's stream URLs, so a retry resolves it from a different source instead of
 * fetching the same kind of URL again. Remembered per video for [WINDOW_MINUTES] after the last refusal.
 */
object YtStreamRefusals {
    private const val WINDOW_MINUTES = 10L

    private val counts: Cache<String, Int> = Caffeine.newBuilder()
        .maximumSize(256)
        .expireAfterWrite(WINDOW_MINUTES, TimeUnit.MINUTES)
        .build()

    /** Records a refusal for [videoUrl]; returns how many it has had in the window. */
    fun record(videoUrl: String): Int {
        val key = keyOf(videoUrl) ?: return 0
        return counts.asMap().merge(key, 1, Int::plus) ?: 1
    }

    /** Refusals recorded for [videoUrl] in the window. */
    fun count(videoUrl: String): Int = keyOf(videoUrl)?.let(counts::getIfPresent) ?: 0

    /** The resolve plan for [videoUrl] given its refusals. */
    fun plan(videoUrl: String): YtResolvePlan = YtResolvePlan.forRefusals(count(videoUrl))

    internal fun clear() = counts.invalidateAll()

    private fun keyOf(url: String): String? = YouTubeUrls.extractVideoId(url)
}
