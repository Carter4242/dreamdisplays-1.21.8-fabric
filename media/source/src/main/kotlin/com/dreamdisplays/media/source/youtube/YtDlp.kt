package com.dreamdisplays.media.source.youtube

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.search.MediaSearchPage
import com.dreamdisplays.api.media.search.MediaSearchResult
import com.dreamdisplays.api.media.search.SortOrder
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.shared.policy.MediaUrlPolicy
import com.dreamdisplays.media.runtime.system.Processes
import com.dreamdisplays.media.source.youtube.binary.YtDlpBinary
import com.dreamdisplays.media.source.youtube.binary.YtDlpJsRuntime
import com.dreamdisplays.media.source.youtube.cache.YtFormatCache
import com.dreamdisplays.media.source.youtube.cookie.YtCookieManager
import com.dreamdisplays.media.source.youtube.model.YtStream
import com.dreamdisplays.media.source.youtube.model.YtStreams
import com.dreamdisplays.media.source.youtube.process.YtDlpClientRace
import com.dreamdisplays.media.source.youtube.process.YtDlpErrors
import com.dreamdisplays.media.source.youtube.search.YtDlpSearchCache
import com.dreamdisplays.util.DreamCoroutines
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private class AbandonFlag {
    private val flag = atomic(false)
    val isAbandoned: Boolean get() = flag.value
    fun abandon() {
        flag.value = true
    }
}

/**
 * `yt-dlp` orchestrator: races the in-process [NewPipeResolver] fast path against the `yt-dlp`
 * subprocess (client racing lives in [YtDlpClientRace]), caches the winning result (via
 * [YtFormatCache]), and exposes YouTube search (via [YtDlpSearchCache]).
 */
object YtDlp {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val cookies = YtCookieManager()
    private val clientRace = YtDlpClientRace(cookies)
    private val formatCache = YtFormatCache(resolve = ::fetchUncached)

    /**
     * Returns stream list for videoUrl (in-memory -> disk -> `yt-dlp`). [owner] is who waits (a resolve passes its
     * source): see [cancelFetch].
     */
    @Throws(IOException::class)
    fun fetch(videoUrl: String, owner: Any = Any()): List<YtStream> {
        if (!MediaUrlPolicy.isAllowed(videoUrl)) {
            return []
        }
        waiters.join(videoUrl, owner)
        try {
            return formatCache.fetch(videoUrl)
        } finally {
            waiters.leave(videoUrl, owner)
        }
    }

    /** Resolves the binary path, cookie browser, and cookie header in the background to reduce first-fetch latency. */
    fun prewarmAsync() {
        DreamCoroutines.clientIo.launch {
            runCatching {
                NewPipeResolver.ensureInitialized()
                NewPipeResolver.prewarmPlayer()
                val command = YtDlpBinary.resolveCommand()
                // Finds an installed runtime (never downloads one) and, with one, reads whether this yt-dlp takes it
                if (YtDlpJsRuntime.current() != null) YtDlpBinary.jsRuntimeArgs(command)
                cookies.prewarm()
            }.onFailure { e ->
                logger.warn("Failed to prewarm yt-dlp.", e)
            }
        }
    }

    /** Fires a background fetch for [videoUrl] if not already cached, so it is ready before [fetch] is called. */
    fun prefetchFormats(videoUrl: String) {
        if (videoUrl.isBlank()) return
        if (!MediaUrlPolicy.isAllowed(videoUrl)) return
        formatCache.prefetch(videoUrl)
    }

    /** Searches YouTube for [query] via InnerTube, returning up to [limit] results; uses a 30-minute in-memory cache. */
    @Throws(IOException::class)
    fun search(query: String, limit: Int): List<MediaSearchResult> = YtDlpSearchCache.search(query, limit)

    /** Fetches up to [limit] related videos for [videoId] via InnerTube; falls back to title search if none found. */
    @Throws(IOException::class)
    fun related(videoId: String, limit: Int): List<MediaSearchResult> = YtDlpSearchCache.related(videoId, limit)

    /** Fetches the first page (up to [limit] results) matching [query] in [sortOrder]; a fresh network call each time (continuation isn't cacheable). */
    @Throws(IOException::class)
    fun searchPage(query: String, limit: Int, sortOrder: SortOrder = SortOrder.RELEVANCE): MediaSearchPage =
        YtDlpSearchCache.searchPage(query, limit, sortOrder)

    /** Fetches the page following [continuationToken] from a prior [searchPage]/[searchMore] call. */
    @Throws(IOException::class)
    fun searchMore(continuationToken: String, limit: Int): MediaSearchPage =
        YtDlpSearchCache.searchMore(continuationToken, limit)

    /** Fetches the first page (up to [limit] results) related to [videoId]. */
    @Throws(IOException::class)
    fun relatedPage(videoId: String, limit: Int): MediaSearchPage = YtDlpSearchCache.relatedPage(videoId, limit)

    /** Fetches the page following [continuationToken] from a prior [relatedPage]/[relatedMore] call. */
    @Throws(IOException::class)
    fun relatedMore(continuationToken: String, limit: Int): MediaSearchPage =
        YtDlpSearchCache.relatedMore(continuationToken, limit)

    /** Extracts the 11-character YouTube video ID from a full URL, short URL, or bare ID. Returns null if not recognized. */
    fun extractVideoId(url: String?): String? = YtDlpSearchCache.extractVideoId(url)

    /**
     * Removes [videoUrl] from the in-memory format cache, in-flight map, and disk cache. Entries are
     * keyed by the canonical URL the resolvers pass in, so a link as the player holds it (`youtu.be/…`,
     * extra query parameters such as `&t=` or `&list=`) is canonicalized first; otherwise a stale or
     * broken format list would survive every retry.
     */
    fun invalidateCache(videoUrl: String) {
        formatCache.invalidate(videoUrl)
        canonicalUrl(videoUrl)?.takeIf { it != videoUrl }?.let(formatCache::invalidate)
    }

    /**
     * Stops the resolve of [videoUrl] in flight and kills its `yt-dlp` processes when [owner] (whose race another
     * resolver won) is the only caller waiting on it: a Twitch or Vimeo metadata lookup, or another display's resolve,
     * waiting on the same fetch keeps it. Nothing is cached for a stopped fetch, so a later one starts afresh.
     */
    fun cancelFetch(videoUrl: String, owner: Any) {
        var abandon: (() -> Unit)? = null
        waiters.stopIfOnly(videoUrl, owner) {
            formatCache.cancel(videoUrl)
            // Taken under the lock: a fetch started right after this one is stopped registers its own
            abandon = abandoners.remove(videoUrl)
        }
        // A canceled coroutine only stops at its next suspension, and NewPipe / the first client block: kill now
        abandon?.invoke()
    }

    /** Callers waiting on each URL's fetch, for [cancelFetch]. */
    private val waiters = FetchWaiters()

    /** Kills the `yt-dlp` work of each fetch in flight, by URL, for [cancelFetch]. */
    private val abandoners = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    /** The URL the resolvers cache [videoUrl] under, or null when it has none. */
    internal fun canonicalUrl(videoUrl: String): String? =
        runCatching { MediaSource.from(videoUrl).toResolvableUrl() }.getOrNull()

    /** Returns a cached YouTube cookie header string for use by HTTP clients other than `yt-dlp`. */
    fun getPublicCookieHeader(): String? = cookies.header()

    @Throws(IOException::class)
    private suspend fun fetchUncached(videoUrl: String): List<YtStream> {
        val plan = YtStreamRefusals.plan(videoUrl)
        if (plan.escalated) return fetchEscalated(videoUrl, plan)
        val ytProcesses = CopyOnWriteArrayList<Process>()
        val abandoned = AbandonFlag()
        val ytdlp = DreamCoroutines.clientIo.async {
            if (abandoned.isAbandoned) return@async []
            clientRace.resolve(videoUrl, plan) { proc ->
                ytProcesses.add(proc)
                if (abandoned.isAbandoned) Processes.destroyTree(proc)
            } ?: []
        }

        // Aborts the parallel yt-dlp branch and reaps any subprocess it managed to spawn (canceled first, so a client
        // killed here counts as abandoned rather than failed)
        fun abandonYtDlp() {
            abandoned.abandon()
            ytdlp.cancel()
            ytProcesses.forEach { runCatching { Processes.destroyTree(it) } }
        }
        val abandon: () -> Unit = ::abandonYtDlp
        abandoners[videoUrl] = abandon
        return try {
            fetchRaced(videoUrl, ytdlp, abandon)
        } catch (e: CancellationException) {
            // The fetch itself was canceled (another resolver won): its yt-dlp branch runs in another scope
            abandonYtDlp()
            throw e
        } finally {
            abandoners.remove(videoUrl, abandon)
        }
    }

    @Throws(IOException::class)
    private suspend fun fetchRaced(
        videoUrl: String,
        ytdlp: Deferred<List<YtStream>>,
        abandonYtDlp: () -> Unit,
    ): List<YtStream> {
        // The server took NewPipe out (`client.disabledResolvers`): yt-dlp alone
        val viaNewPipe = if (!ResolverConfig.newPipeInsideYtDlp) [] else runCatching { NewPipeResolver.fetch(videoUrl) }
            .onFailure { e ->
                if (e is CancellationException) {
                    abandonYtDlp()
                    throw e
                }
                logger.debug("NewPipe resolve failed for {}: {}.", videoUrl, e.message?.take(200))
            }
            .getOrElse { [] }

        if (YtStreams.usable(viaNewPipe) && YtStreams.offersQualityLadder(viaNewPipe) &&
            viaNewPipe.none { it.isLive }
        ) {
            abandonYtDlp()
            return viaNewPipe
        }
        if (viaNewPipe.isNotEmpty()) {
            logger.debug(
                "NewPipe returned no quality ladder for {} (heights={}); awaiting parallel yt-dlp.",
                videoUrl, YtStreams.distinctHeights(viaNewPipe),
            )
        }

        currentCoroutineContext().ensureActive()
        val viaYtDlp = runCatching {
            withTimeoutOrNull((YtDlpClientRace.FETCH_TIMEOUT_SECONDS + 15).seconds) { ytdlp.await() }
                ?: emptyList<YtStream>().also { abandonYtDlp() }
        }.onFailure { e ->
            abandonYtDlp()
            if (e is CancellationException && !currentCoroutineContext().isActive) throw e
            logger.debug("yt-dlp resolve did not settle for {}: {}.", videoUrl, e.message?.take(200))
        }.getOrElse { [] }

        return when {
            YtStreams.usable(viaYtDlp) && YtStreams.offersQualityLadder(viaYtDlp) -> viaYtDlp
            viaNewPipe.isNotEmpty() -> {
                logger.warn(
                    "yt-dlp only produced a non-ladder fallback-client result for $videoUrl " +
                            "(heights=${YtStreams.distinctHeights(viaYtDlp)}); using NewPipeExtractor's muxed " +
                            "stream instead, since fallback yt-dlp clients are PO-token gated."
                )
                viaNewPipe
            }

            viaYtDlp.isNotEmpty() -> viaYtDlp
            else -> throw failure(videoUrl, clientRace.takeLastError(videoUrl))
        }
    }

    /**
     * Why every `yt-dlp` client failed: what its output says, else a passing failure while the JavaScript runtime it
     * needs is downloading (the player retries quietly, and once more after showing it), else a plain one.
     */
    private fun failure(videoUrl: String, lastError: String?): Throwable =
        YtDlpErrors.classify(lastError)
            ?: if (YtDlpJsRuntime.isDownloading) {
                DreamMediaException.Network("YouTube needs a JavaScript runtime for this video; it is being downloaded (once).")
            } else {
                IOException("All yt-dlp clients failed for $videoUrl.")
            }

    /**
     * Resolve after YouTube refused this video's stream URLs more than once: `yt-dlp` with the clients [plan] picks,
     * NewPipe (whose URLs were the refused kind) only if `yt-dlp` has nothing at all, so a broken `yt-dlp` install
     * still leaves the display something to try.
     */
    @Throws(IOException::class)
    private suspend fun fetchEscalated(videoUrl: String, plan: YtResolvePlan): List<YtStream> {
        logger.warn(
            "YouTube refused the stream URLs of {} {} time(s); resolving with yt-dlp ({}) instead of NewPipe.",
            videoUrl, YtStreamRefusals.count(videoUrl), listOfNotNull(plan.primaryClient).plus(plan.fallbackClients),
        )
        val processes = CopyOnWriteArrayList<Process>()
        val abandoned = AbandonFlag()
        val abandon: () -> Unit = {
            abandoned.abandon()
            processes.forEach { runCatching { Processes.destroyTree(it) } }
        }
        abandoners[videoUrl] = abandon
        val viaYtDlp = try {
            withTimeoutOrNull((YtDlpClientRace.FETCH_TIMEOUT_SECONDS * 2 + 15).seconds) {
                clientRace.resolve(videoUrl, plan) {
                    processes.add(it)
                    if (abandoned.isAbandoned) Processes.destroyTree(it)
                }
            }.orEmpty()
        } finally {
            abandoners.remove(videoUrl, abandon)
            processes.forEach { runCatching { Processes.destroyTree(it) } }
        }
        currentCoroutineContext().ensureActive()
        if (YtStreams.usable(viaYtDlp)) return viaYtDlp
        val lastError = clientRace.takeLastError(videoUrl)
        if (!ResolverConfig.newPipeInsideYtDlp) throw failure(videoUrl, lastError)
        val viaNewPipe = runCatching { NewPipeResolver.fetch(videoUrl) }
            .onFailure { e -> if (e is CancellationException) throw e }
            .getOrElse { [] }
        com.dreamdisplays.media.runtime.diagnostics.ResolveTrail.record(
            videoUrl, "newpipe (fallback)", ok = viaNewPipe.isNotEmpty(),
            error = if (viaNewPipe.isEmpty()) "no streams" else null,
        )
        if (viaNewPipe.isNotEmpty()) {
            logger.warn("yt-dlp could not resolve {}; falling back to NewPipe's stream URLs.", videoUrl)
            return viaNewPipe
        }
        throw failure(videoUrl, lastError)
    }
}
