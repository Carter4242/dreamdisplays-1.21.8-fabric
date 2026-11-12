package com.dreamdisplays.media.player.adskip

import java.time.Instant

/**
 * A live Twitch channel as one viewer's media playlist shows it, a segment at a time: the stream's own segments
 * (`live`, numbered in the header by `#EXT-X-TWITCH-LIVE-SEQUENCE`), and stitched ads spliced in the way Twitch does
 * (a `twitch-stitched-ad` date range, `Amazon|...` titles, a discontinuity on each side and the live sequence named
 * again after the ad).
 */
internal class TwitchSim(
    private val host: String = "https://0123456789ab.j.cloudfront.hls.ttvnw.net",
    private val window: Int = 6,
    private val startMs: Long = 1_800_000_000_000L,
    private var liveSeq: Long = 9_000L,
    private var mediaSeq: Long = 500L,
) {
    data class Seg(val uri: String, val ad: Boolean, val dateMs: Long, val dur: Double, val liveSeq: Long?, val disc: Boolean)

    private val segments = ArrayList<Seg>()
    private val ranges = ArrayList<String>()
    private var clockMs = startMs
    private var adRun = 0
    private var inAd = false

    /** Wall clock of the stream's edge (ms). */
    val edgeMs: Long get() = clockMs

    /** Appends one segment of the stream. */
    fun live(count: Int = 1): TwitchSim {
        repeat(count) {
            val disc = inAd
            inAd = false
            segments += Seg("$host/v1/segment/live-$liveSeq.ts", false, clockMs, 2.0, liveSeq, disc)
            liveSeq++
            clockMs += 2_000
        }
        return this
    }

    /** Skips [count] segments of the stream (what the ad replaced: the stream goes on meanwhile). */
    fun lose(count: Int): TwitchSim {
        liveSeq += count
        return this
    }

    /** Starts an ad of [durationSec] (its date range), then appends its first [count] segments. */
    fun startAd(durationSec: Double, count: Int = 1, rollType: String = "MIDROLL"): TwitchSim {
        adRun++
        val id = "stitched-ad-${clockMs / 1000}-${(durationSec * 1e9).toLong()}"
        ranges += "#EXT-X-DATERANGE:ID=\"$id\",CLASS=\"twitch-stitched-ad\",START-DATE=\"${Instant.ofEpochMilli(clockMs)}\"," +
                "DURATION=${"%.3f".format(java.util.Locale.ROOT, durationSec)},X-TV-TWITCH-AD-ROLL-TYPE=\"$rollType\"," +
                "X-TV-TWITCH-AD-POD-LENGTH=\"1\",X-TV-TWITCH-AD-POD-POSITION=\"0\""
        return ad(count)
    }

    /** Appends [count] more segments of the running ad. */
    fun ad(count: Int = 1): TwitchSim {
        repeat(count) {
            val disc = !inAd
            inAd = true
            segments += Seg("$host/v1/segment/ad-$adRun-${segments.size}.ts", true, clockMs, 2.0, null, disc)
            clockMs += 2_000
        }
        return this
    }

    /** The media playlist as the viewer gets it now (the last [window] segments). */
    fun playlist(): String {
        val shown = segments.takeLast(window)
        val first = segments.size - shown.size
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSeq + first).append('\n')
        shown.firstOrNull()?.liveSeq?.let { sb.append("#EXT-X-TWITCH-LIVE-SEQUENCE:").append(it).append('\n') }
        ranges.forEach { sb.append(it).append('\n') }
        var previousAd = shown.firstOrNull()?.ad ?: false
        for ((i, s) in shown.withIndex()) {
            if (s.disc && i > 0) sb.append("#EXT-X-DISCONTINUITY\n")
            if (i > 0 && previousAd && !s.ad) sb.append("#EXT-X-TWITCH-LIVE-SEQUENCE:").append(s.liveSeq).append('\n')
            sb.append("#EXT-X-PROGRAM-DATE-TIME:").append(Instant.ofEpochMilli(s.dateMs)).append('\n')
            sb.append("#EXTINF:2.000,").append(if (s.ad) "Amazon|2000000000002.0" else "live").append('\n')
            sb.append(s.uri).append('\n')
            previousAd = s.ad
        }
        return sb.toString()
    }

    companion object {
        /** The segment URIs a served playlist lists, in order. */
        fun uris(playlist: String?): List<String> =
            playlist.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()

        fun mediaSequence(playlist: String?): Long? =
            playlist.orEmpty().lineSequence().firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }
                ?.substringAfter(':')?.trim()?.toLongOrNull()
    }
}
