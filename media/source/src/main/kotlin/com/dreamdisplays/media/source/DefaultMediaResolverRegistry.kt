package com.dreamdisplays.media.source

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.source.MediaResolverService
import com.dreamdisplays.api.media.source.MediaResolverRegistry
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.source.ResolvedMedia
import com.dreamdisplays.media.runtime.diagnostics.ResolveTrail
import com.dreamdisplays.media.runtime.security.MediaHostGuard
import com.dreamdisplays.media.source.platform.Platform
import com.dreamdisplays.util.DreamCoroutines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Default [MediaResolverRegistry]: tries registered resolvers in priority order, returning the first success.
 * [disabled] names resolvers ([resolverName]) not to use; a source that no other resolver takes still gets them all.
 */
class DefaultMediaResolverRegistry(
    private val disabled: () -> Set<String> = { emptySet() },
) : MediaResolverRegistry {
    private val backing = CopyOnWriteArrayList<MediaResolverService>()
    private val prefetchPermit = Semaphore(PREFETCH_CONCURRENCY)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    override val resolvers: List<MediaResolverService>
        get() = backing.sortedByDescending { it.priority }

    override fun register(resolver: MediaResolverService) {
        if (resolver !in backing) backing.add(resolver)
    }

    override fun unregister(resolver: MediaResolverService) {
        backing.remove(resolver)
    }

    override fun prefetch(source: MediaSource) {
        val key = source.toResolvableUrl() ?: source.toString()
        if (!inFlight.add(key)) return
        DreamCoroutines.clientIo.launch {
            try {
                prefetchPermit.withPermit {
                    if (isBlockedHost(source)) return@withPermit
                    for (resolver in candidatesFor(source)) {
                        if (runCatching { resolver.prefetch(source) }.getOrDefault(false)) break
                    }
                }
            } finally {
                inFlight.remove(key)
            }
        }
    }

    override fun resolve(source: MediaSource): ResolvedMedia {
        when (hostVerdict(source)) {
            MediaHostGuard.HostVerdict.NON_PUBLIC ->
                throw DreamMediaException.Unknown("Refusing to resolve a media URL on a non-public host.", isFatal = true)
            // A name that failed to resolve this once is a network hiccup, not a verdict on the link.
            MediaHostGuard.HostVerdict.UNRESOLVED ->
                throw DreamMediaException.Network("Could not look up this link's server. Check your connection.")
            else -> Unit
        }
        val candidates = candidatesFor(source)
        if (candidates.isEmpty()) {
            throw DreamMediaException.Unknown("No resolver registered for source: $source", isFatal = true)
        }
        if (candidates.size == 1) return traced(source, candidates[0])
        // The first resolver owns this link (a media file for the direct resolver): the rest only if it fails
        val first = candidates[0]
        if (first is RaceAware && first.claims(source)) {
            val claimError = try {
                return traced(source, first)
            } catch (e: Throwable) {
                if (e is CancellationException || e is InterruptedException) throw e
                e
            }
            val rest = candidates.drop(1)
            return runCatching { if (rest.size == 1) traced(source, rest[0]) else runBlocking { raceResolvers(source, rest) } }
                .getOrElse { e ->
                    if (e is CancellationException || e is InterruptedException) throw e
                    throw conclusive([claimError, e]) ?: e
                }
        }
        return runBlocking { raceResolvers(source, candidates) }
    }

    /** A typed failure says why; otherwise the last resolver is the final fallback and its error is the conclusive one. */
    private fun conclusive(errors: List<Throwable?>): Throwable? =
        errors.filterNotNull().let { all -> all.firstOrNull { it is DreamMediaException } ?: all.lastOrNull() }

    /** Runs [resolver] and records how it went in the link's [ResolveTrail] (for client error reports). */
    private fun traced(source: MediaSource, resolver: MediaResolverService): ResolvedMedia {
        val started = System.currentTimeMillis()
        val key = source.toResolvableUrl() ?: source.toString()
        return try {
            resolver.resolve(source).also {
                ResolveTrail.record(key, sourceName(resolver), ok = true, elapsedMs = System.currentTimeMillis() - started)
            }
        } catch (e: Throwable) {
            if (e !is CancellationException && e !is InterruptedException) {
                ResolveTrail.record(
                    key, sourceName(resolver), ok = false, error = e.message ?: e.javaClass.simpleName,
                    elapsedMs = System.currentTimeMillis() - started,
                )
            }
            throw e
        }
    }

    private suspend fun raceResolvers(source: MediaSource, candidates: List<MediaResolverService>): ResolvedMedia {
        val winner = CompletableDeferred<ResolvedMedia>()
        val errors = arrayOfNulls<Throwable>(candidates.size)
        val remaining = AtomicInteger(candidates.size)
        // Started and not yet finished; a resolver that lost while running is told to stop (see below)
        val running = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

        // Once a winner lands, candidates still waiting out their head start are canceled so they never
        // spawn work. One already resolving in-process is left to finish (not interrupted): an interrupted extract
        // can surface as "unresolvable" and poison a resolver's negative cache, while a finished one warms it. One
        // running subprocesses ([RaceAware.cancel], yt-dlp) has them killed: they cost seconds of CPU per client.
        val jobs = candidates.mapIndexed { index, resolver ->
            DreamCoroutines.clientIo.launch {
                if (index > 0) delay(RACE_HEAD_START * index)
                if (!winner.isCompleted) {
                    running += index
                    val result = runCatching { traced(source, resolver) }
                    running -= index
                    result
                        .onSuccess { winner.complete(it) }
                        .onFailure { e ->
                            if (e is CancellationException) throw e
                            errors[index] = e
                        }
                }
                if (remaining.decrementAndGet() == 0 && !winner.isCompleted) {
                    winner.completeExceptionally(
                        // Earlier errors are often just "deferring to the next resolver"
                        conclusive(errors.asList()) ?: DreamMediaException.Unknown("All resolvers failed for source: $source")
                    )
                }
            }
        }
        try {
            return winner.await()
        } finally {
            jobs.forEach(Job::cancel)
            running.forEach { index -> (candidates[index] as? RaceAware)?.let { runCatching { it.cancel(source) } } }
        }
    }

    /** The resolvers that take [source], in priority order, without the [disabled] ones unless that leaves none. */
    private fun candidatesFor(source: MediaSource): List<MediaResolverService> =
        usableResolvers(resolvers.filter { it.canResolve(source) }, runCatching(disabled).getOrDefault(emptySet()))

    private fun isBlockedHost(source: MediaSource): Boolean =
        hostVerdict(source) != MediaHostGuard.HostVerdict.PUBLIC

    private fun hostVerdict(source: MediaSource): MediaHostGuard.HostVerdict {
        val url = when (source) {
            is MediaSource.Remote -> source.url
            is MediaSource.DirectStream -> source.streamUrl
            else -> return MediaHostGuard.HostVerdict.PUBLIC
        }
        return MediaHostGuard.verdict(url)
    }

    companion object {
        /** Short resolver name for reports and `client.disabledResolvers`: `newpipe`, `yt-dlp`, `direct`, or the platform's. */
        fun resolverName(resolver: MediaResolverService): String = sourceName(resolver)

        /** [candidates] without those named in [disabled]; all of them when that would leave none. */
        internal fun usableResolvers(
            candidates: List<MediaResolverService>, disabled: Set<String>,
        ): List<MediaResolverService> {
            if (disabled.isEmpty()) return candidates
            return candidates.filter { sourceName(it) !in disabled }.ifEmpty { candidates }
        }

        private fun sourceName(resolver: MediaResolverService): String = when (resolver) {
            is Platform<*, *> -> resolver.name.lowercase()
            else -> when (val name = resolver.javaClass.simpleName.removeSuffix("Resolver")) {
                "NewPipe" -> "newpipe"
                "YtDlp" -> "yt-dlp"
                "DirectStream" -> "direct"
                else -> name.lowercase()
            }
        }

        /**
         * Hints warmed at once. Enough to cover a room of screens, few enough not to flood the
         * network (or the `yt-dlp` subprocess budget) with speculative work.
         */
        private const val PREFETCH_CONCURRENCY = 3

        private val RACE_HEAD_START = 400.milliseconds
    }
}
