package com.dreamdisplays.media.player.adskip

import java.net.URI
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * One live HLS media playlist as Twitch serves it, read for its stitched ads: which segments are ads and which are the
 * stream itself.
 *
 * Twitch splices its ads into the viewer's own media playlist (server-side ad insertion). An ad is announced by an
 * `#EXT-X-DATERANGE` whose `CLASS` is `twitch-stitched-ad` (its `ID` starts with `stitched-ad-`, and it carries
 * `X-TV-TWITCH-AD-*` attributes; quartile beacons are `twitch-ad-quartile`), and each of its segments has an `#EXTINF`
 * title other than the stream's own `live`. A segment is an ad when either says so: its program date falls inside an
 * ad's date range, or its title is not `live` (the same two tests streamlink's `--twitch-disable-ads` uses).
 */
internal class StitchedAdPlaylist(
    val mediaSequence: Long,
    val targetDurationSec: Double,
    val segments: List<Segment>,
    val adRanges: List<AdRange>,
    val endList: Boolean,
) {
    /** One media segment, its URI made absolute. */
    data class Segment(
        val uri: String,
        val seq: Long,
        val durationSec: Double,
        val title: String?,
        /** `#EXT-X-PROGRAM-DATE-TIME` in epoch ms, or null when the playlist has none for it. */
        val programDateMs: Long?,
        val discontinuity: Boolean,
        /** Absolute `#EXT-X-MAP` URI in force for this segment (fragmented MP4), or null for MPEG-TS. */
        val mapUri: String?,
        val ad: Boolean,
        /**
         * Twitch's own number for this segment of the stream (`#EXT-X-TWITCH-LIVE-SEQUENCE`), the same in every
         * player variant's playlist; null for ads and in playlists without it. A playlist showing an ad numbers its
         * segments from 0 and dates them on the ad's own clock, so only this matches a segment across playlists.
         */
        val liveSeq: Long? = null,
    ) {
        /** Program date where this segment ends, or null without one. */
        val endDateMs: Long? get() = programDateMs?.let { it + (durationSec * 1_000).toLong() }
    }

    /** One stitched-ad date range: its start (epoch ms) and length, when the playlist gives them. */
    data class AdRange(val id: String, val startMs: Long?, val durationSec: Double?) {
        val endMs: Long? get() = if (startMs != null && durationSec != null) startMs + (durationSec * 1_000).toLong() else null

        fun covers(dateMs: Long): Boolean {
            val start = startMs ?: return false
            val end = endMs ?: return false
            return dateMs in start until end
        }
    }

    /** True when any segment in this window is an ad. */
    val hasAdSegments: Boolean get() = segments.any { it.ad }

    /** True when the newest segment is an ad: an ad break is running at the live edge right now. */
    val adAtEdge: Boolean get() = segments.lastOrNull()?.ad == true

    /** True when anything in the playlist marks a stitched ad (an ad segment or an ad date range). */
    val hasAdMarkers: Boolean get() = hasAdSegments || adRanges.isNotEmpty()

    /** Program date where the newest segment ends (the live edge), or null without dates. */
    val edgeDateMs: Long? get() = segments.lastOrNull()?.endDateMs

    /**
     * Seconds of ad still to come after the live edge, from the ad date ranges that reach past it; null when the
     * playlist does not say (no dates, or no ad range with a length).
     */
    fun adSecondsAfterEdge(): Double? {
        val edge = edgeDateMs ?: return null
        val end = adRanges.mapNotNull { it.endMs }.filter { it > edge }.maxOrNull() ?: return if (adAtEdge) null else 0.0
        return (end - edge) / 1_000.0
    }

    companion object {
        /** `EXTINF` title of the stream's own segments; anything else is an ad (or a slate standing in for one). */
        const val LIVE_TITLE = "live"

        /** Parses [body], a live media playlist fetched from [baseUrl]. Never throws on odd input: unknown tags are skipped. */
        fun parse(body: String, baseUrl: String): StitchedAdPlaylist {
            val base = runCatching { URI(baseUrl) }.getOrNull()
            fun abs(ref: String): String = base?.let { runCatching { it.resolve(ref).toString() }.getOrNull() } ?: ref

            var mediaSequence = 0L
            var target = 2.0
            var endList = false
            var mapUri: String? = null
            val ranges = ArrayList<AdRange>()

            data class Pending(var duration: Double = 0.0, var title: String? = null, var date: Long? = null, var disc: Boolean = false)
            var pending = Pending()
            val raw = ArrayList<Pending>()
            val liveSeqAt = ArrayList<Pair<Int, Long>>()
            val uris = ArrayList<String>()
            val maps = ArrayList<String?>()

            for (line0 in body.lineSequence()) {
                val line = line0.trim()
                when {
                    line.isEmpty() -> {}
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                        mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: mediaSequence
                    line.startsWith("#EXT-X-TARGETDURATION:") ->
                        target = line.substringAfter(':').trim().toDoubleOrNull()?.takeIf { it > 0 } ?: target
                    line == "#EXT-X-ENDLIST" -> endList = true
                    line == "#EXT-X-DISCONTINUITY" -> pending.disc = true
                    line.startsWith("#EXT-X-MAP:") ->
                        mapUri = attributes(line.substringAfter(':'))["URI"]?.takeIf { it.isNotEmpty() }?.let(::abs)
                    line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> pending.date = parseDate(line.substringAfter(':').trim())
                    line.startsWith("#EXTINF:") -> {
                        val rest = line.substringAfter(':')
                        pending.duration = rest.substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                        pending.title = if (rest.contains(',')) rest.substringAfter(',').trim() else null
                    }
                    line.startsWith("#EXT-X-DATERANGE:") -> adRange(attributes(line.substringAfter(':')))?.let(ranges::add)
                    line.startsWith("#EXT-X-TWITCH-LIVE-SEQUENCE:") -> {
                        val n = line.substringAfter(':').trim().toLongOrNull()
                        // Numbers the next segment (in the header: the first one)
                        if (n != null) liveSeqAt += raw.size to n
                    }
                    line.startsWith("#") -> {} // Prefetch hints, Twitch's own tags, comments
                    else -> {
                        raw += pending
                        uris += abs(line)
                        maps += mapUri
                        pending = Pending()
                    }
                }
            }
            val tagAt = liveSeqAt.toMap()
            var running: Long? = null
            val segments = raw.mapIndexed { i, p ->
                val inRange = p.date?.let { d -> ranges.any { it.covers(d) } } == true
                val titledAd = !p.title.isNullOrEmpty() && !p.title.equals(LIVE_TITLE, ignoreCase = true)
                val ad = inRange || titledAd
                tagAt[i]?.let { running = it }
                val liveSeq = if (ad) null else running
                running = if (ad) null else running?.plus(1)
                Segment(
                    uri = uris[i], seq = mediaSequence + i, durationSec = p.duration, title = p.title,
                    programDateMs = p.date, discontinuity = p.disc, mapUri = maps[i], ad = ad, liveSeq = liveSeq,
                )
            }
            return StitchedAdPlaylist(mediaSequence, target, segments, ranges, endList)
        }

        /** The ad a `#EXT-X-DATERANGE` describes, or null when it is some other date range (stream source, markers). */
        private fun adRange(attrs: Map<String, String>): AdRange? {
            val id = attrs["ID"].orEmpty()
            val cls = attrs["CLASS"].orEmpty()
            val ad = cls == "twitch-stitched-ad" || cls == "twitch-ad-quartile" || id.startsWith("stitched-ad-") ||
                    attrs.keys.any { it.startsWith("X-TV-TWITCH-AD-") }
            if (!ad) return null
            // A quartile beacon marks a point inside an ad, not the ad's extent
            val duration = if (cls == "twitch-ad-quartile") null else attrs["DURATION"]?.toDoubleOrNull()?.takeIf { it > 0 }
            return AdRange(id, attrs["START-DATE"]?.let(::parseDate), duration)
        }

        internal fun parseDate(text: String): Long? = try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }

        /** Parses an HLS attribute list (`KEY=value,KEY="quoted, value"`). */
        internal fun attributes(list: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            var i = 0
            while (i < list.length) {
                val eq = list.indexOf('=', i)
                if (eq < 0) break
                val key = list.substring(i, eq).trim()
                var j = eq + 1
                val value: String
                if (j < list.length && list[j] == '"') {
                    val close = list.indexOf('"', j + 1)
                    if (close < 0) break
                    value = list.substring(j + 1, close)
                    j = close + 1
                } else {
                    val comma = list.indexOf(',', j)
                    value = (if (comma < 0) list.substring(j) else list.substring(j, comma)).trim()
                    j = if (comma < 0) list.length else comma
                }
                if (key.isNotEmpty()) out[key] = value
                i = if (j < list.length && list[j] == ',') j + 1 else j
            }
            return out
        }
    }
}
