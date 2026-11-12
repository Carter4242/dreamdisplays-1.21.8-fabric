package com.dreamdisplays.media.player.process

import com.dreamdisplays.api.media.source.LiveStreamUrls

/**
 * Relay streams whose picture needs a longer probe than [MediaProcess.LIVE_PROBE_SIZE] / [MediaProcess.LIVE_ANALYZE_MICROS].
 *
 * The relay sends a joining reader sound at once but pictures only from the next keyframe. The short probe is made for
 * this mod's shares (a keyframe at most a second away); an outside RTMP source with sound and a longer keyframe
 * interval (2 s and up, as OBS sends) makes it run out first whenever its next keyframe is over ~1.45 s away
 * (~0.8 s at 320 kbit/s sound), and the picture fails with "does not contain any stream". Measured against a local
 * MediaMTX: 29% of joins at a 2 s interval, 62% at 4 s, 75% at 8.3 s. After one such failure the stream's later
 * attempts probe up to [MediaProcess.LIVE_WIDE_PROBE_SIZE] / [MediaProcess.LIVE_WIDE_ANALYZE_MICROS], which ends at
 * its first keyframe (or after 4 s, inside the live player's 6 s startup watchdog).
 *
 * RTMP only: SRT's MPEG-TS always probes to the full limit (a wider one would only delay every attempt, and the short
 * one's retries already land a picture quickly), and RTSP knows its streams from the SDP and never fails this way.
 * A picture-only stream never fails it either, so it keeps the short probe (the wide one would make it wait 4 s).
 */
object LiveProbe {
    private const val MAX_REMEMBERED = 64

    private val widened = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > MAX_REMEMBERED
    }

    private val WIDENED_SCHEMES = setOf("rtmp", "rtmps")

    /** True when [url]'s picture is probed with the wide limits. */
    fun isWide(url: String): Boolean = synchronized(widened) { widened[url.trim()] == true }

    /** True when the picture process for [url] failed with [stderr] because its probe ran out before any picture. */
    internal fun ranOutBeforePicture(url: String, stderr: String): Boolean =
        LiveStreamUrls.schemeOf(url) in WIDENED_SCHEMES && stderr.contains("does not contain any stream", ignoreCase = true)

    /** Notes a failed picture attempt at [url]; true when its later attempts now probe wide (for the first time). */
    fun noteFailure(url: String, stderr: String): Boolean {
        if (!ranOutBeforePicture(url, stderr)) return false
        return synchronized(widened) { widened.put(url.trim(), true) != true }
    }

    internal fun forget() = synchronized(widened) { widened.clear() }
}
