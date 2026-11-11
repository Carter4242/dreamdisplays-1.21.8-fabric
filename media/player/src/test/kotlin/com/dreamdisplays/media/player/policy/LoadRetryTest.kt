package com.dreamdisplays.media.player.policy

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.source.MediaSource
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoadRetryTest {
    private val direct = MediaSource.from("https://files.example.com/movie.mp4")
    private val youtube = MediaSource.from("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

    @Test
    fun `network and timeout failures are retried, wrong links are not`() {
        val reach = "Could not reach this link. Check that it is public and still valid."
        assertTrue(LoadFailures.isTransient(DreamMediaException.Network(reach), direct))
        assertTrue(LoadFailures.isTransient(DreamMediaException.Timeout("slow"), direct))
        assertFalse(LoadFailures.isTransient(DreamMediaException.Network("gone", isFatal = true), direct))

        // The messages a wrong link fails with stay final
        listOf(
            "This link opens a web page, not a video file. Use the link to the file itself.",
            "This link is an audio file. A display needs a video to show.",
            "This link does not serve a video file. Check that it points straight at the media.",
            "$reach (HTTP 404)",
        ).forEach { assertFalse(LoadFailures.isTransient(DreamMediaException.NotFound(it), direct), it) }
        assertFalse(LoadFailures.isTransient(DreamMediaException.Decode("bad codec"), direct))
        assertFalse(
            LoadFailures.isTransient(DreamMediaException.Unknown("non-public host", isFatal = true), direct),
        )
    }

    @Test
    fun `raw network exceptions are retried for any source`() {
        listOf(
            SocketTimeoutException("Read timed out"),
            ConnectException("Connection refused"),
            UnknownHostException("files.example.com"),
            IOException("HTTP 503 for https://files.example.com/movie.mp4"),
            IOException("Too many requests already in flight to files.example.com."),
            IllegalStateException("wrapped", IOException("Connection reset")),
        ).forEach { assertTrue(LoadFailures.isTransient(it, direct), it.toString()) }
        assertFalse(LoadFailures.isTransient(IOException("HTTP 404 for https://x/y.mp4"), direct))
        assertFalse(LoadFailures.isTransient(IllegalStateException("No resolver"), direct))
    }

    @Test
    fun `an extractor failure on a platform link is retried, a resolver gap marked transient too`() {
        assertTrue(LoadFailures.isTransient(IOException("All yt-dlp clients failed for x."), youtube))
        assertTrue(LoadFailures.isTransient(IllegalStateException("NewPipe could not resolve"), youtube))
        assertTrue(LoadFailures.isTransient(DreamMediaException.Unknown("extract failed"), youtube))
        val gap = DreamMediaException.NotFound("No usable video stream", TransientResolveGap("No usable video stream"))
        assertTrue(LoadFailures.isTransient(gap, youtube))
        assertFalse(LoadFailures.isTransient(DreamMediaException.NotFound("No usable video stream"), youtube))
        assertFalse(LoadFailures.isTransient(UnsupportedOperationException("no URL"), youtube))
    }

    @Test
    fun `platform links get fewer attempts than direct links`() {
        assertEquals(LoadFailures.DIRECT_ATTEMPTS, LoadFailures.maxAttempts(direct))
        assertEquals(LoadFailures.PLATFORM_ATTEMPTS, LoadFailures.maxAttempts(youtube))
        assertTrue(LoadFailures.PLATFORM_ATTEMPTS < LoadFailures.DIRECT_ATTEMPTS)
    }

    @Test
    fun `transient statuses`() {
        listOf(408, 425, 429, 500, 502, 503, 504).forEach { assertTrue(LoadFailures.isTransientStatus(it), "$it") }
        listOf(200, 301, 400, 401, 403, 404, 410, 416).forEach { assertFalse(LoadFailures.isTransientStatus(it), "$it") }
    }

    @Test
    fun `schedule stops after the allowed attempts and resets`() {
        val schedule = LoadRetrySchedule(jitter = 0.0)
        assertEquals(listOf(2_000L, 6_000L, 15_000L, 30_000L), List(4) { schedule.next(5)!! })
        assertNull(schedule.next(5), "Five attempts = the first try plus four retries.")
        schedule.reset()
        assertEquals(2_000L, schedule.next(3))
        assertEquals(6_000L, schedule.next(3))
        assertNull(schedule.next(3))
    }

    @Test
    fun `retries of a crowd are spread out`() {
        val total = LoadRetrySchedule.DEFAULT_DELAYS_MS.sum()
        assertTrue(total in 30_000L..60_000L, "A direct link gives up within about a minute ($total ms).")

        val firstDelays = (0 until 60).map { LoadRetrySchedule(random = Random(it)).next(5)!! }
        val base = LoadRetrySchedule.DEFAULT_DELAYS_MS[0]
        val jitter = LoadRetrySchedule.DEFAULT_JITTER
        firstDelays.forEach { assertTrue(it >= (base * (1 - jitter)).toLong() && it <= (base * (1 + jitter)).toLong(), "$it") }
        assertTrue(firstDelays.distinct().size > 50, "60 clients must not retry in the same instant.")
        assertTrue(firstDelays.max() - firstDelays.min() > base / 2, "Retries span a real window.")
    }
}
