package com.dreamdisplays.media.source

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.source.MediaResolverService
import com.dreamdisplays.api.media.source.MediaResolverRegistry
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.source.ResolvedMedia
import com.dreamdisplays.media.runtime.security.MediaHostGuard
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

/** Default [MediaResolverRegistry]: tries registered resolvers in priority order, returning the first success. */
class DefaultMediaResolverRegistry : MediaResolverRegistry {
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
                    for (resolver in resolvers) {
                        if (!resolver.canResolve(source)) continue
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
        val candidates = resolvers.filter { it.canResolve(source) }
        if (candidates.isEmpty()) {
            throw DreamMediaException.Unknown("No resolver registered for source: $source", isFatal = true)
        }
        if (candidates.size == 1) return candidates[0].resolve(source)
        return runBlocking { raceResolvers(source, candidates) }
    }

    private suspend fun raceResolvers(source: MediaSource, candidates: List<MediaResolverService>): ResolvedMedia {
        val winner = CompletableDeferred<ResolvedMedia>()
        val errors = arrayOfNulls<Throwable>(candidates.size)
        val remaining = AtomicInteger(candidates.size)

        // Once a winner lands, candidates still waiting out their head start are canceled so they never
        // spawn work. One already resolving is left to finish (not interrupted): an interrupted extract can
        // surface as "unresolvable" and poison a resolver's negative cache, while a finished one warms it.
        val jobs = candidates.mapIndexed { index, resolver ->
            DreamCoroutines.clientIo.launch {
                if (index > 0) delay(RACE_HEAD_START * index)
                if (!winner.isCompleted) {
                    runCatching { resolver.resolve(source) }
                        .onSuccess { winner.complete(it) }
                        .onFailure { e ->
                            if (e is CancellationException) throw e
                            errors[index] = e
                        }
                }
                if (remaining.decrementAndGet() == 0 && !winner.isCompleted) {
                    winner.completeExceptionally(
                        // A typed failure says why; otherwise the last resolver is the final fallback and its error is
                        // the conclusive one (earlier ones are often just "deferring to the next resolver")
                        errors.filterNotNull().let { all -> all.firstOrNull { it is DreamMediaException } ?: all.lastOrNull() }
                            ?: DreamMediaException.Unknown("All resolvers failed for source: $source")
                    )
                }
            }
        }
        try {
            return winner.await()
        } finally {
            jobs.forEach(Job::cancel)
        }
    }

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

    private companion object {
        /**
         * Hints warmed at once. Enough to cover a room of screens, few enough not to flood the
         * network (or the `yt-dlp` subprocess budget) with speculative work.
         */
        const val PREFETCH_CONCURRENCY = 3

        val RACE_HEAD_START = 400.milliseconds
    }
}
