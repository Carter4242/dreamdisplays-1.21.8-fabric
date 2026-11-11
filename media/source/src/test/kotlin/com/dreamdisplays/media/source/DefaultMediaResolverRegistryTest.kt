package com.dreamdisplays.media.source

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.source.MediaMetadata
import com.dreamdisplays.api.media.source.MediaResolverService
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.source.ResolvedMedia
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultMediaResolverRegistryTest {
    private val source = MediaSource.YouTube("dQw4w9WgXcQ")

    private class FakeResolver(
        override val priority: Int,
        private val warms: Boolean,
        val calls: AtomicInteger = AtomicInteger(),
        private val onPrefetch: () -> Unit = {},
    ) : MediaResolverService {
        override fun canResolve(source: MediaSource): Boolean = true

        override fun resolve(source: MediaSource): ResolvedMedia =
            ResolvedMedia([], MediaMetadata.UNKNOWN, isLive = false, isSeekable = false)

        override fun prefetch(source: MediaSource): Boolean {
            calls.incrementAndGet()
            onPrefetch()
            return warms
        }
    }

    private fun awaitCalls(deadlineMs: Long = 2_000, check: () -> Boolean) {
        val until = System.nanoTime() + deadlineMs * 1_000_000
        while (System.nanoTime() < until) {
            if (check()) return
            Thread.sleep(5)
        }
    }

    @Test
    fun `prefetch stops at the first resolver that warms something`() {
        val fast = FakeResolver(priority = 10, warms = true)
        val fallback = FakeResolver(priority = 0, warms = true)
        val registry = DefaultMediaResolverRegistry().apply {
            register(fallback)
            register(fast)
        }

        registry.prefetch(source)
        awaitCalls { fast.calls.get() == 1 }

        assertEquals(1, fast.calls.get(), "The highest-priority resolver should have been warmed.")
        Thread.sleep(100)
        assertEquals(
            0, fallback.calls.get(),
            "The universal fallback must not be warmed once a dedicated resolver already has been — " +
                    "that is what used to spawn a doomed yt-dlp for every Twitch / Vimeo / direct link.",
        )
    }

    @Test
    fun `prefetch falls through a resolver that warmed nothing`() {
        val declines = FakeResolver(priority = 10, warms = false)
        val fallback = FakeResolver(priority = 0, warms = true)
        val registry = DefaultMediaResolverRegistry().apply {
            register(declines)
            register(fallback)
        }

        registry.prefetch(source)
        awaitCalls { fallback.calls.get() == 1 }

        assertEquals(1, declines.calls.get())
        assertEquals(1, fallback.calls.get(), "A resolver that warmed nothing must not end the chain.")
    }

    @Test
    fun `a hint already in flight is not queued again`() {
        val gate = CountDownLatch(1)
        val slow = FakeResolver(priority = 10, warms = true, onPrefetch = { gate.await(2, TimeUnit.SECONDS) })
        val registry = DefaultMediaResolverRegistry().apply { register(slow) }

        // The client fires this on every URL change and every display load, so a wall of screens
        // showing one video used to queue an identical warm-up per screen.
        repeat(8) { registry.prefetch(source) }
        awaitCalls { slow.calls.get() >= 1 }
        assertEquals(1, slow.calls.get(), "Duplicate hints for the same source must collapse into one.")

        gate.countDown()
        // Once it has settled a later hint is allowed through again (caches decide from there)
        awaitCalls { slow.calls.get() == 1 }
        registry.prefetch(source)
        awaitCalls { slow.calls.get() == 2 }
        assertTrue(slow.calls.get() <= 2, "Expected at most one further warm-up, got ${slow.calls.get()}.")
    }

    @Test
    fun `resolve returns the first successful resolver in priority order`() {
        val failing = object : MediaResolverService {
            override val priority: Int = 20
            override fun canResolve(source: MediaSource): Boolean = true
            override fun resolve(source: MediaSource): ResolvedMedia = error("nope")
        }
        val working = FakeResolver(priority = 5, warms = true)
        val registry = DefaultMediaResolverRegistry().apply {
            register(failing)
            register(working)
        }
        assertEquals(0, registry.resolve(source).streams.size)
    }

    private fun failing(priority: Int, error: Throwable) = object : MediaResolverService {
        override val priority: Int = priority
        override fun canResolve(source: MediaSource): Boolean = true
        override fun resolve(source: MediaSource): ResolvedMedia = throw error
    }

    @Test
    fun `total failure reports the fallback resolver's error, not the first one's`() {
        val registry = DefaultMediaResolverRegistry().apply {
            register(failing(10, IllegalStateException("deferring to the fallback")))
            register(failing(0, IllegalStateException("fallback failed")))
        }
        assertEquals("fallback failed", assertFailsWith<IllegalStateException> { registry.resolve(source) }.message)
    }

    @Test
    fun `total failure prefers a typed error over a plain one`() {
        val typed = DreamMediaException.Unknown("needs sign-in", isFatal = true)
        val registry = DefaultMediaResolverRegistry().apply {
            register(failing(10, typed))
            register(failing(0, IllegalStateException("fallback failed")))
        }
        val e = assertFailsWith<DreamMediaException> { registry.resolve(source) }
        assertEquals("needs sign-in", e.message)
        assertTrue(e.isFatal)
    }

    @Test
    fun `a geo block from the first resolver is conclusive`() {
        val registry = DefaultMediaResolverRegistry().apply {
            register(failing(10, DreamMediaException.GeoBlocked("This video isn't available in your country.")))
            register(failing(0, IllegalStateException("fallback failed")))
        }
        val e = assertFailsWith<DreamMediaException.GeoBlocked> { registry.resolve(source) }
        assertTrue(e.isFatal)
    }

    private val file = MediaSource.from("https://1.1.1.1/movies/film.mp4") // an IP literal: no DNS lookup in the host check
    private fun media() = ResolvedMedia([], MediaMetadata.UNKNOWN, isLive = false, isSeekable = false)

    /** Takes every source; [claim] makes it own them, [work] runs inside resolve, and cancels are counted. */
    private class RacingResolver(
        override val priority: Int,
        private val claim: Boolean = false,
        private val work: () -> ResolvedMedia,
    ) : MediaResolverService, RaceAware {
        val started = AtomicInteger()
        val cancels = AtomicInteger()
        override fun canResolve(source: MediaSource): Boolean = true
        override fun claims(source: MediaSource): Boolean = claim
        override fun cancel(source: MediaSource) {
            cancels.incrementAndGet()
        }

        override fun resolve(source: MediaSource): ResolvedMedia {
            started.incrementAndGet()
            return work()
        }
    }

    @Test
    fun `a media file link is the direct resolver's alone while it succeeds`() {
        assertTrue(file is MediaSource.DirectStream)
        // Slower than the 400 ms head start yt-dlp used to get: it must still never start
        val direct = RacingResolver(20, claim = true) { Thread.sleep(700); media() }
        val ytDlp = RacingResolver(0) { media() }
        val registry = DefaultMediaResolverRegistry().apply {
            register(ytDlp)
            register(direct)
        }
        registry.resolve(file)
        Thread.sleep(100)
        assertEquals(1, direct.started.get())
        assertEquals(0, ytDlp.started.get(), "yt-dlp must not start for a file the direct resolver plays")
    }

    @Test
    fun `only the direct resolver claims, and only links that name media`() {
        assertTrue(com.dreamdisplays.media.source.direct.DirectStreamResolver.claims(file))
        assertTrue(!com.dreamdisplays.media.source.direct.DirectStreamResolver.claims(MediaSource.Remote("https://1.1.1.1/watch?v=1")))
        assertTrue(!com.dreamdisplays.media.source.youtube.YtDlpResolver.claims(file))
    }

    @Test
    fun `the rest run when the claiming resolver fails`() {
        val direct = RacingResolver(20, claim = true) { throw DreamMediaException.Network("unreachable") }
        val ytDlp = RacingResolver(0) { media() }
        val registry = DefaultMediaResolverRegistry().apply {
            register(direct)
            register(ytDlp)
        }
        registry.resolve(file)
        assertEquals(1, ytDlp.started.get())

        val both = DefaultMediaResolverRegistry().apply {
            register(direct)
            register(RacingResolver(0) { error("yt-dlp failed") })
        }
        assertEquals("unreachable", assertFailsWith<DreamMediaException.Network> { both.resolve(file) }.message)
    }

    @Test
    fun `a page link still races the direct probe against the fallbacks`() {
        val gate = CountDownLatch(1)
        val direct = RacingResolver(20, claim = true) { gate.await(3, TimeUnit.SECONDS); error("not media") }
        val ytDlp = RacingResolver(0) { media() }
        val registry = DefaultMediaResolverRegistry().apply {
            register(direct)
            register(ytDlp)
        }
        // A claim is the resolver's own call: one that claims nothing races as before
        val notClaimed = RacingResolver(20, claim = false) { gate.await(3, TimeUnit.SECONDS); error("not media") }
        val racing = DefaultMediaResolverRegistry().apply {
            register(notClaimed)
            register(ytDlp)
        }
        val t0 = System.nanoTime()
        racing.resolve(source)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000, "the fallback wins without waiting for the probe")
        gate.countDown()
        registry.resolve(file) // claimed: completes once the probe fails
        assertEquals(2, ytDlp.started.get())
    }

    @Test
    fun `losers still running are told to stop once a winner is chosen, not-started ones are not`() {
        val release = CountDownLatch(1)
        val winner = RacingResolver(20) { Thread.sleep(600); media() }
        val running = RacingResolver(10) { release.await(3, TimeUnit.SECONDS); error("killed") }
        val neverStarted = RacingResolver(0) { media() }
        val registry = DefaultMediaResolverRegistry().apply {
            register(winner)
            register(running)
            register(neverStarted)
        }
        try {
            // running starts at 400 ms, neverStarted would at 800 ms; winner lands at 600 ms
            registry.resolve(source)
            assertEquals(1, running.started.get())
            assertEquals(1, running.cancels.get(), "a loser still resolving must be canceled")
            assertEquals(0, winner.cancels.get())
            Thread.sleep(400)
            assertEquals(0, neverStarted.started.get())
            assertEquals(0, neverStarted.cancels.get())
        } finally {
            release.countDown()
        }
    }
}
