package com.dreamdisplays.media.player.adskip

import com.dreamdisplays.media.player.util.daemon
import com.dreamdisplays.media.runtime.security.MediaHostGuard
import com.dreamdisplays.util.net.DreamHttpClient
import org.slf4j.LoggerFactory

/**
 * Twitch ad skipping for live channels, client-side only: each display playing a Twitch channel reads it through an
 * [AdSkipSession], whose playlist has the stitched ads taken out (an ad-free backup when one exists, else the ads
 * dropped and an ad-break card shown in their place; see [LiveAdFilter]).
 *
 * The Twitch resolver tells this which media playlist URLs are live Twitch renditions ([rememberLiveRendition]) and how
 * to sign backups ([backupSource]); the client wires [enabled] to the server's `client.twitch_ad_skip` lever.
 */
object TwitchAdSkip {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** False turns ad skipping off for streams opened from then on (the server's `client.twitch_ad_skip = off`). */
    @Volatile
    var enabled: () -> Boolean = { true }

    /** Signs backups for another player variant; null leaves only the filter. Set by the Twitch resolver. */
    @Volatile
    var backupSource: BackupPlaylistSource? = null

    /**
     * Test-only: with private URLs allowed (`-Ddreamdisplays.allowPrivateUrls=true`, which only test and development
     * clients set), a live HLS playlist served from this machine under a `/twitch-sim/` path counts as a Twitch rendition
     * (filter only, no backups), so the end-to-end harness can play a synthetic stream carrying Twitch's ad markers.
     */
    private val testStreams: Boolean = System.getProperty("dreamdisplays.allowPrivateUrls", "false").toBoolean()

    internal fun isTestStream(url: String): Boolean =
        testStreams && (url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")) && "/twitch-sim/" in url

    /**
     * Backup variants in the order they are tried. Twitch decides on ads per player variant a token is signed for; all
     * of these serve the stream's full quality ladder, with the same segments and live sequence numbers as the web
     * popout player the stream is opened with (checked against live Twitch, 2026-11). The `autoplay`,
     * `picture-by-picture` and `thunderdome` variants are left out: they stop at 360p / 480p.
     */
    val BACKUP_VARIANTS: List<BackupVariant> = listOf(
        BackupVariant("embed", "web"),
        BackupVariant("site", "android"),
        BackupVariant("popout", "android"),
        BackupVariant("picture-by-picture-CACHED", "web"),
        BackupVariant("frontpage", "web"),
    )

    internal val memory = BackupMemory(BACKUP_VARIANTS)

    private const val MAX_RENDITIONS = 256
    private val renditions = object : LinkedHashMap<String, LiveRendition>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LiveRendition>) = size > MAX_RENDITIONS
    }

    private val serverHolder = lazy {
        AdFreePlaylistServer().also { s -> MediaHostGuard.trustLocalUrls(s::owns) }
    }
    private val server: AdFreePlaylistServer by serverHolder

    /** Records that [url] is the media playlist of a live Twitch rendition of [rendition]'s channel. */
    fun rememberLiveRendition(url: String, rendition: LiveRendition) {
        synchronized(renditions) { renditions[url] = rendition }
    }

    /** True when [url] is one of the ad-free playlists served here (a Twitch stream, for the sound feeder's splice rule). */
    fun servesPlaylist(url: String): Boolean = serverHolder.isInitialized() && server.owns(url)

    /**
     * Opens an ad-skip session for [url] when it is a live Twitch rendition and skipping is on: reads its playlist once
     * (and signs a backup if an ad runs right now), then serves the result. Null otherwise, or when anything fails: the
     * stream then plays straight from Twitch, as before.
     */
    fun open(url: String, label: String): AdSkipSession? {
        if (!runCatching { enabled() }.getOrDefault(true)) return null
        val rendition = synchronized(renditions) { renditions[url] }
        val test = rendition == null && isTestStream(url)
        if (rendition == null && !test) return null
        val fetch: (String) -> String = { u -> DreamHttpClient.readText(u, MediaHostGuard.guarded(u, PLAYLIST_OPTIONS)) }
        val backups = if (rendition != null) backupSource?.let { BackupChooser(rendition, it, fetch, memory, label) } else null
        val filter = LiveAdFilter(url, label, fetch, backups)
        filter.refresh()
        if (!filter.ready) {
            logger.debug("{} [ad-skip] first playlist read failed; playing the stream straight from Twitch.", label)
            return null
        }
        var session: AdSkipSession? = null
        val (token, playUrl) = server.register { session?.onRead(); filter.playlist() } ?: return null
        return AdSkipSession(filter, playUrl, label) { server.unregister(token) }.also {
            session = it
            it.start()
        }
    }

    internal val PLAYLIST_OPTIONS = DreamHttpClient.RequestOptions(
        connectTimeoutMs = 4_000L, readTimeoutMs = 4_000L, callTimeoutMs = 6_000L,
    )
}

/**
 * One display's ad-skipping stream: [playUrl] is what its decoders open. A poller keeps the playlist current while
 * anything reads it (or the player waits out an ad, [keepPolling]); [close] when the player lets the stream go.
 */
class AdSkipSession internal constructor(
    internal val filter: LiveAdFilter,
    val playUrl: String,
    private val label: String,
    private val onClose: () -> Unit,
) : AutoCloseable {
    @Volatile
    private var closed = false

    @Volatile
    private var lastReadNanos = System.nanoTime()

    @Volatile
    private var poller: Thread? = null

    /** Set while the player shows its ad-break card: nothing reads the playlist then, but it must stay current. */
    @Volatile
    var keepPolling = false
        set(value) {
            field = value
            if (value) poller?.let(java.util.concurrent.locks.LockSupport::unpark)
        }

    /** True while an ad break holds the stream (see [LiveAdFilter.holding]). */
    val holding: Boolean get() = filter.holding

    /** When the current hold began ([System.nanoTime]), or null. */
    val holdSinceNanos: Long? get() = filter.holdSinceNanos

    /** When the stream is expected back ([System.nanoTime]), or null while unknown. */
    val resumeAtNanos: Long? get() = filter.resumeAtNanos

    /** Twitch stopped serving the stream's playlist. */
    val gone: Boolean get() = filter.gone

    /** Ad breaks held so far. */
    val breaks: Int get() = filter.breaks

    internal fun start() {
        poller = daemon(::pollLoop, "MediaPlayer-ad-skip-poll").also { it.start() }
    }

    /** A decoder read the playlist: polling goes on (and resumes at once after a pause). */
    internal fun onRead() {
        val idle = idle()
        lastReadNanos = System.nanoTime()
        if (idle) poller?.let(java.util.concurrent.locks.LockSupport::unpark)
    }

    private fun idle(): Boolean = !keepPolling && System.nanoTime() - lastReadNanos > IDLE_NANOS

    /** Polls while the playlist is read (or the player waits out an ad); a paused or parked stream costs Twitch nothing. */
    private fun pollLoop() {
        while (!closed && !filter.gone) {
            if (idle()) java.util.concurrent.locks.LockSupport.parkNanos(IDLE_NANOS)
            else java.util.concurrent.locks.LockSupport.parkNanos(filter.pollIntervalMs * 1_000_000L)
            if (Thread.interrupted()) return
            if (!closed && !idle()) filter.refresh()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        onClose()
        poller?.let(java.util.concurrent.locks.LockSupport::unpark)
    }

    private companion object {
        /** No read for this long: the stream is paused or parked, and polling stops until the next read. */
        const val IDLE_NANOS = 20_000_000_000L
    }

    override fun toString(): String = "AdSkipSession($label, ${filter.sourceName})"
}
