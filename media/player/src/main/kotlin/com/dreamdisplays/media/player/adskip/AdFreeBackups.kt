package com.dreamdisplays.media.player.adskip

import org.slf4j.LoggerFactory

/** A Twitch player variant (`playerType` on `platform`) whose playback token may come without stitched ads. */
data class BackupVariant(val playerType: String, val platform: String) {
    override fun toString(): String = "$playerType/$platform"
}

/** The live rendition a display plays: its channel and the picture it was picked for. */
data class LiveRendition(val login: String, val height: Int?, val fps: Double?, val group: String?)

/**
 * Signs the same channel for another player variant and returns that variant's media playlist URL for the rendition
 * closest to the one playing (same picture size), or null when it has none. Implemented by the Twitch resolver; may
 * throw on any network or API failure.
 */
fun interface BackupPlaylistSource {
    fun mediaPlaylistUrl(variant: BackupVariant, rendition: LiveRendition): String?
}

/**
 * What every display on this client learned about the backup variants: the one that last gave an ad-free playlist is
 * tried first, a variant that failed is left alone for a while, and a round where none worked backs every display
 * off, so a patched-out trick costs Twitch a handful of requests, not a stream of them.
 */
internal class BackupMemory(
    private val candidates: List<BackupVariant>,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var preferred: BackupVariant? = null
    private val failedUntil = HashMap<BackupVariant, Long>()
    private val failures = HashMap<BackupVariant, Int>()
    private var roundsFailed = 0
    private var pausedUntil: Long? = null

    /** False while every display is backing off after a round where no variant worked. */
    @Synchronized
    fun mayTry(): Boolean = pausedUntil.let { it == null || nanoTime() - it >= 0 }

    /** The variants worth trying now, the last one that worked first. */
    @Synchronized
    fun order(): List<BackupVariant> {
        val now = nanoTime()
        val ok = candidates.filter { v -> failedUntil[v]?.let { now - it >= 0 } ?: true }
        val first = preferred?.takeIf { it in ok }
        return if (first == null) ok else listOf(first) + (ok - first)
    }

    @Synchronized
    fun worked(variant: BackupVariant) {
        preferred = variant
        failures.remove(variant)
        failedUntil.remove(variant)
        roundsFailed = 0
        pausedUntil = null
    }

    @Synchronized
    fun failed(variant: BackupVariant) {
        if (preferred == variant) preferred = null
        val n = (failures[variant] ?: 0) + 1
        failures[variant] = n
        failedUntil[variant] = nanoTime() + backoffNanos(VARIANT_BACKOFF_MS, n, VARIANT_BACKOFF_MAX_MS)
    }

    /** A whole round found nothing: every display waits before the next one. */
    @Synchronized
    fun roundFailed() {
        roundsFailed++
        pausedUntil = nanoTime() + backoffNanos(ROUND_BACKOFF_MS, roundsFailed, ROUND_BACKOFF_MAX_MS)
    }

    companion object {
        const val VARIANT_BACKOFF_MS = 5 * 60_000L
        const val VARIANT_BACKOFF_MAX_MS = 60 * 60_000L
        const val ROUND_BACKOFF_MS = 60_000L
        const val ROUND_BACKOFF_MAX_MS = 15 * 60_000L

        /** [baseMs] doubled for every failure after the first, at most [maxMs], in nanos. */
        internal fun backoffNanos(baseMs: Long, failures: Int, maxMs: Long): Long {
            val shift = (failures - 1).coerceIn(0, 20)
            return (baseMs shl shift).coerceAtMost(maxMs) * 1_000_000L
        }
    }
}

/**
 * Finds an ad-free copy of one display's stream while its own playlist shows a stitched ad: signs the channel for the
 * other player variants ([BackupMemory.order]) and keeps the first whose playlist [acceptable] takes. Best effort and
 * quiet: anything that goes wrong is a debug line and a null, which leaves the ad to be filtered out instead.
 */
internal class BackupChooser(
    private val rendition: LiveRendition,
    private val source: BackupPlaylistSource,
    private val fetch: (String) -> String,
    private val memory: BackupMemory,
    private val label: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private var current: Pair<BackupVariant, String>? = null
    private var triesThisBreak = 0

    /** The variant in use, for logs. */
    val currentVariant: BackupVariant? get() = current?.first

    /**
     * An ad-free playlist for the stream, from the backup in use or a newly signed one; null when there is none. At most
     * [MAX_TRIES_PER_BREAK] variants are signed per ad break.
     */
    fun adFree(acceptable: (StitchedAdPlaylist) -> Boolean): Pair<BackupVariant, StitchedAdPlaylist>? {
        current?.let { (variant, url) ->
            val playlist = read(url)
            if (playlist != null && acceptable(playlist)) return variant to playlist
            logger.debug("{} [ad-skip] backup {} {}; dropping it.", label, variant, if (playlist == null) "failed" else "shows an ad too")
            memory.failed(variant)
            current = null
        }
        if (triesThisBreak >= MAX_TRIES_PER_BREAK || !memory.mayTry()) return null
        val order = memory.order()
        var tried = 0
        for (variant in order) {
            if (triesThisBreak >= MAX_TRIES_PER_BREAK) break
            triesThisBreak++
            tried++
            val url = runCatching { source.mediaPlaylistUrl(variant, rendition) }
                .onFailure { e -> logger.debug("{} [ad-skip] backup {} could not be signed: {}", label, variant, e.toString()) }
                .getOrNull()
            if (url == null) {
                memory.failed(variant)
                continue
            }
            val playlist = read(url)
            if (playlist == null || !acceptable(playlist)) {
                logger.debug("{} [ad-skip] backup {} {}.", label, variant, if (playlist == null) "playlist failed" else "shows an ad too")
                memory.failed(variant)
                continue
            }
            memory.worked(variant)
            current = variant to url
            logger.debug("{} [ad-skip] backup {} is ad-free; playing it while the ad runs.", label, variant)
            return variant to playlist
        }
        if (tried > 0) memory.roundFailed()
        return null
    }

    /** The stream's own playlist is ad-free again: the next ad break may sign variants anew. */
    fun breakOver() {
        triesThisBreak = 0
    }

    private fun read(url: String): StitchedAdPlaylist? =
        runCatching { StitchedAdPlaylist.parse(fetch(url), url) }
            .onFailure { e -> logger.debug("{} [ad-skip] backup playlist read failed: {}", label, e.toString()) }
            .getOrNull()
            ?.takeIf { it.segments.isNotEmpty() && !it.endList }

    companion object {
        /** Variants signed per ad break, at most (each costs a token request, a master and a media playlist). */
        const val MAX_TRIES_PER_BREAK = 3
    }
}
