package com.dreamdisplays.media.player.adskip

import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil

/**
 * One display's Twitch live playlist with the stitched ads taken out, served to its decoders in place of Twitch's own.
 *
 * Every poll reads the stream's media playlist. While it shows an ad, an ad-free copy signed for another player variant
 * is tried first ([BackupChooser]); without one, the ad's segments are dropped (what streamlink's
 * `--twitch-disable-ads` does) and the playlist served is held: it stops growing at the last segment before the ad, so
 * the decoders play out what they have and then run dry, and the player shows its ad-break card ([holding]). The
 * segments after the ad are released only once [JOIN_SEGMENTS] of them exist, and only those (a cut): the session opened
 * then starts at the first of them, [JOIN_SEGMENTS] segments behind the edge as any live join does, so every viewer comes
 * back at the same distance from the live edge whatever their own ad's length.
 *
 * The playlist served numbers its segments itself (a sequence that only grows), so dropped ads, a backup with other
 * sequence numbers and the switches between them never look like a jump to the decoders. Segments are matched across
 * playlists by Twitch's live sequence number (a playlist showing an ad numbers and dates its segments on the ad's own
 * clock), else by their program date.
 */
internal class LiveAdFilter(
    private val mainUrl: String,
    private val label: String,
    private val fetch: (String) -> String,
    private val backups: BackupChooser? = null,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private class Entry(val vseq: Long, val segment: StitchedAdPlaylist.Segment, var discontinuity: Boolean)

    /** An ad break in progress: the playlist served is held at the cut until enough of the stream follows it. */
    private class Hold(val sinceNanos: Long) {
        var adSeconds = 0.0
        var postAdCount = 0
        var resumeAtNanos: Long? = null
    }

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var nextVseq = -1L
    private var cutVseq = Long.MIN_VALUE
    private var hold: Hold? = null
    private var lastContentDateMs: Long? = null
    private var lastLiveSeq: Long? = null
    private var lastAdDateMs: Long? = null
    private var lastSource: String? = null
    private var lastSeq = Long.MIN_VALUE
    private var windowSize = MIN_WINDOW
    private var targetSec = 2.0
    private var segmentSec = 2.0
    private var failures = 0
    private var source = MAIN

    /**
     * Just after an ad break: only the [JOIN_SEGMENTS] segments after the cut are served, so the session opened now
     * starts exactly at the cut (its sound and picture both), however late it reads. Lifted [PIN_READ_GRACE_NANOS] after
     * the first read, or [PIN_MAX_NANOS] after the release if nothing reads.
     */
    private var pinSinceNanos: Long? = null
    private var pinFirstReadNanos: Long? = null

    @Volatile
    private var rendered: String? = null

    /** Ad breaks this filter held (for logs and tests). */
    @Volatile
    var breaks = 0
        private set

    /** The stream's playlist is gone (offline, refused): the playlist is no longer served. */
    @Volatile
    var gone = false
        private set

    /** Polled successfully at least once. */
    val ready: Boolean get() = rendered != null

    /** True while an ad break holds the playlist: the session plays out what it has, then the ad-break card shows. */
    val holding: Boolean get() = synchronized(lock) { hold != null }

    /** When the current hold began ([nanoTime]), or null when nothing is held. */
    val holdSinceNanos: Long? get() = synchronized(lock) { hold?.sinceNanos }

    /**
     * When the stream is expected back ([nanoTime]): the ad's remaining length (from its date ranges) plus the time
     * [JOIN_SEGMENTS] segments take to follow it. Null while unknown or when nothing is held.
     */
    val resumeAtNanos: Long? get() = synchronized(lock) { hold?.resumeAtNanos }

    /**
     * Between polls: half a segment, so a new one is seen within a second or so; faster while the stream is one segment
     * from its release, so every viewer rejoins as soon as the segments are there.
     */
    val pollIntervalMs: Long
        get() = synchronized(lock) {
            if ((hold?.postAdCount ?: -1) >= JOIN_SEGMENTS - 1) RELEASE_POLL_MS
            else (segmentSec * 500).toLong().coerceIn(MIN_POLL_MS, MAX_POLL_MS)
        }

    /** Which playlist feeds the segments now: `main`, or the backup variant's name. */
    val sourceName: String get() = synchronized(lock) { source }

    /** The playlist to serve, or null when the stream is gone (the decoders then get an error, as from Twitch itself). */
    fun playlist(): String? {
        if (gone) return null
        synchronized(lock) {
            val since = pinSinceNanos ?: return rendered
            val now = nanoTime()
            val firstRead = pinFirstReadNanos ?: now.also { pinFirstReadNanos = it }
            if (now - firstRead > PIN_READ_GRACE_NANOS || now - since > PIN_MAX_NANOS) {
                pinSinceNanos = null
                pinFirstReadNanos = null
                rendered = render()
            }
            return rendered
        }
    }

    /** Reads the stream's playlist (and a backup's, during an ad) once and updates what is served. Never throws. */
    fun refresh() {
        if (gone) return
        val main = try {
            StitchedAdPlaylist.parse(fetch(mainUrl), mainUrl)
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            synchronized(lock) {
                if (++failures >= MAX_FAILURES) {
                    logger.debug("{} [ad-skip] playlist failed {} times ({}); giving it up.", label, failures, e.toString())
                    gone = true
                }
            }
            return
        }
        if (main.endList) {
            gone = true
            return
        }
        var use = main
        var name = MAIN
        val adNow = synchronized(lock) { hasNewAd(main, MAIN) }
        if (adNow && backups != null) {
            backups.adFree { p -> synchronized(lock) { !hasNewAd(p, BACKUP) } }?.let { (variant, playlist) ->
                use = playlist
                name = variant.toString()
                // The ad on the stream's own playlist is covered: it must not hold the stream once that playlist is back
                synchronized(lock) { coverAds(main) }
            }
        } else if (!adNow) {
            backups?.breakOver()
        }
        synchronized(lock) {
            failures = 0
            if (name != source) {
                logger.debug("{} [ad-skip] segments now come from {} (was {}).", label, name, source)
                source = name
            }
            ingest(use, if (name == MAIN) MAIN else BACKUP)
            rendered = render()
        }
    }

    /** True when [p] shows an ad this filter has not passed yet. Caller holds [lock]. */
    private fun hasNewAd(p: StitchedAdPlaylist, sourceKey: String): Boolean {
        val fresh = freshness(p, sourceKey)
        return p.segments.indices.any { p.segments[it].ad && fresh[it] }
    }

    /** Marks [p]'s ads as passed (a backup played the stream through them). Caller holds [lock]. */
    private fun coverAds(p: StitchedAdPlaylist) {
        p.segments.filter { it.ad }.mapNotNull { it.programDateMs }.maxOrNull()?.let { d ->
            lastAdDateMs = maxOf(lastAdDateMs ?: d, d)
        }
    }

    /**
     * Which of [p]'s segments this filter has not taken in yet. A stream segment is matched by Twitch's live sequence
     * number, else its program date, else (same playlist only) its media sequence. An ad is new only after the last
     * stream segment already taken in, and past the last ad seen. Caller holds [lock].
     */
    private fun freshness(p: StitchedAdPlaylist, sourceKey: String): BooleanArray {
        val segments = p.segments
        val fresh = BooleanArray(segments.size)
        val sameSource = lastSource == sourceKey
        val keyless = segments.none { it.liveSeq != null || it.programDateMs != null }
        val newestContent = segments.indexOfLast { !it.ad }
        var lastOld = -1
        for ((i, s) in segments.withIndex()) {
            if (s.ad) continue
            val liveSeq = s.liveSeq
            val date = s.programDateMs
            val lastLive = lastLiveSeq
            val lastDate = lastContentDateMs
            fresh[i] = when {
                liveSeq != null && lastLive != null -> liveSeq > lastLive
                date != null && lastDate != null -> date > lastDate + DATE_SLACK_MS
                lastSource == null -> true
                sameSource || !keyless -> s.seq > lastSeq
                // Another playlist with nothing to match by: only its newest segment
                else -> i == newestContent
            }
            if (!fresh[i]) lastOld = i
        }
        for ((i, s) in segments.withIndex()) {
            if (!s.ad || i <= lastOld) continue
            val date = s.programDateMs
            val lastAd = lastAdDateMs
            fresh[i] = when {
                date != null -> lastAd == null || date > lastAd
                else -> !sameSource || s.seq > lastSeq
            }
        }
        return fresh
    }

    private fun ingest(p: StitchedAdPlaylist, sourceKey: String) {
        targetSec = p.targetDurationSec
        p.segments.lastOrNull { !it.ad }?.durationSec?.takeIf { it > 0 }?.let { segmentSec = it }
        if (nextVseq < 0) nextVseq = p.mediaSequence.coerceAtLeast(0L)
        val fresh = freshness(p, sourceKey)
        var previousWasAd = false
        for ((i, s) in p.segments.withIndex()) {
            if (fresh[i]) {
                if (s.ad) onAdSegment(s) else onContentSegment(s, previousWasAd)
            }
            previousWasAd = s.ad
        }
        lastSource = sourceKey
        p.segments.lastOrNull()?.let { lastSeq = it.seq }
        // A held playlist keeps its size: the session playing it out must not lose a segment it has yet to read
        if (hold == null) windowSize = p.segments.count { !it.ad }.coerceIn(MIN_WINDOW, MAX_WINDOW)
        updateHold(p)
    }

    private fun onAdSegment(s: StitchedAdPlaylist.Segment) {
        s.programDateMs?.let { lastAdDateMs = it }
        val h = hold ?: Hold(nanoTime()).also {
            hold = it
            breaks++
            logger.debug("{} [ad-skip] stitched ad at seq {}; holding the playlist at the last segment before it.", label, s.seq)
        }
        h.adSeconds += s.durationSec
        // An ad after some of the stream came back (the next ad of a pod): the stream rejoins after this one instead
        h.postAdCount = 0
        cutVseq = nextVseq
    }

    private fun onContentSegment(s: StitchedAdPlaylist.Segment, afterAd: Boolean) {
        s.programDateMs?.let { lastContentDateMs = it }
        s.liveSeq?.let { lastLiveSeq = it }
        val vseq = nextVseq++
        // The first segment after a cut starts a new timeline; elsewhere a discontinuity of the stream itself is kept
        val discontinuity = vseq == cutVseq || (s.discontinuity && !afterAd)
        entries.addLast(Entry(vseq, s, discontinuity))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        hold?.let { if (vseq >= cutVseq) it.postAdCount++ }
    }

    private fun updateHold(p: StitchedAdPlaylist) {
        val h = hold ?: return
        val now = nanoTime()
        if (h.postAdCount >= JOIN_SEGMENTS && !p.adAtEdge) {
            logger.debug(
                "{} [ad-skip] ad break over after {} s of ad; the stream rejoins {} segments behind its edge.",
                label, "%.1f".format(Locale.ROOT, h.adSeconds), JOIN_SEGMENTS,
            )
            hold = null
            pinSinceNanos = now
            pinFirstReadNanos = null
            return
        }
        if (now - h.sinceNanos > MAX_HOLD_NANOS) {
            logger.debug("{} [ad-skip] ad break held for over {} min; letting the playlist go.", label, MAX_HOLD_NANOS / 60_000_000_000L)
            hold = null
            return
        }
        h.resumeAtNanos = resumeAt(now, if (p.adAtEdge) p.adSecondsAfterEdge() else 0.0, h.postAdCount, segmentSec)
    }

    private fun render(): String {
        val held = hold != null
        val visible = when {
            held -> entries.filter { it.vseq < cutVseq }.takeLast(windowSize)
            pinSinceNanos != null -> entries.filter { it.vseq >= cutVseq }.take(JOIN_SEGMENTS)
            else -> entries.filter { it.vseq >= cutVseq }.takeLast(windowSize)
        }
        val sb = StringBuilder(256 + visible.size * 160)
        sb.append("#EXTM3U\n#EXT-X-VERSION:6\n")
        sb.append("#EXT-X-TARGETDURATION:").append(ceil(targetSec).toLong().coerceAtLeast(1L)).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(visible.firstOrNull()?.vseq ?: nextVseq.coerceAtLeast(0L)).append('\n')
        var map: String? = null
        for ((i, e) in visible.withIndex()) {
            val s = e.segment
            // A leading discontinuity means nothing to a join
            if (e.discontinuity && i > 0) sb.append("#EXT-X-DISCONTINUITY\n")
            if (s.mapUri != null && s.mapUri != map) {
                sb.append("#EXT-X-MAP:URI=\"").append(s.mapUri).append("\"\n")
                map = s.mapUri
            }
            s.programDateMs?.let { sb.append("#EXT-X-PROGRAM-DATE-TIME:").append(Instant.ofEpochMilli(it)).append('\n') }
            sb.append("#EXTINF:").append(String.format(Locale.ROOT, "%.3f", s.durationSec)).append(",live\n")
            sb.append(s.uri).append('\n')
        }
        return sb.toString()
    }

    companion object {
        /** Segments behind the live edge a session joins at: FFmpeg's HLS default, and the sound feeder's. */
        const val JOIN_SEGMENTS = 3

        const val MAIN = "main"
        private const val BACKUP = "backup"
        private const val MIN_WINDOW = JOIN_SEGMENTS
        private const val MAX_WINDOW = 15
        private const val MAX_ENTRIES = 40
        private const val MAX_FAILURES = 5
        private const val MIN_POLL_MS = 1_000L
        private const val MAX_POLL_MS = 3_000L
        private const val RELEASE_POLL_MS = 500L
        private const val PIN_READ_GRACE_NANOS = 4_000_000_000L
        private const val PIN_MAX_NANOS = 30_000_000_000L

        /** Program dates within this of the last one are the same segment seen again (another playlist's rounding). */
        private const val DATE_SLACK_MS = 250L

        /** Longest an ad break holds the stream; past it the playlist is let go and the usual stall handling applies. */
        val MAX_HOLD_NANOS = 8 * 60_000_000_000L

        /**
         * When the stream is expected back after an ad with [adLeftSec] seconds still to run past the edge (null:
         * unknown), [postAdCount] segments of the stream already in and segments [segmentSec] long: the rest of the ad,
         * then the segments still missing for a join. Null when the ad's length is unknown.
         */
        internal fun resumeAt(nowNanos: Long, adLeftSec: Double?, postAdCount: Int, segmentSec: Double): Long? {
            val left = adLeftSec ?: return null
            val missing = (JOIN_SEGMENTS - postAdCount).coerceAtLeast(0)
            return nowNanos + ((left.coerceAtLeast(0.0) + missing * segmentSec) * 1e9).toLong()
        }
    }
}
