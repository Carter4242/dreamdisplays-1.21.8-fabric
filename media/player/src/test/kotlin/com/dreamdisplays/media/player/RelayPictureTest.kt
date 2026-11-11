package com.dreamdisplays.media.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayPictureTest {
    private val second = 1_000_000_000L
    private val now = 100 * second

    @Test
    fun `a still picture stays up while the connection is open and playing`() {
        // A still window: the last frame came a minute ago, the stream is still connected
        assertFalse(RelayPicture.stale(connected = true, paused = false, lastFrameNanos = now - 60 * second, nowNanos = now, maxAgeNanos = second))
    }

    @Test
    fun `the picture goes the moment the stream ends, however fresh its last frame`() {
        // Closed, at its end, reconnecting or errored: not connected
        assertTrue(RelayPicture.stale(connected = false, paused = false, lastFrameNanos = now, nowNanos = now, maxAgeNanos = second))
        // Nothing shown yet
        assertTrue(RelayPicture.stale(connected = true, paused = false, lastFrameNanos = 0L, nowNanos = now, maxAgeNanos = second))
    }

    @Test
    fun `a paused stream still ages out, since a paused reader may miss the relay cutting it`() {
        assertFalse(RelayPicture.stale(connected = true, paused = true, lastFrameNanos = now - second / 2, nowNanos = now, maxAgeNanos = second))
        assertTrue(RelayPicture.stale(connected = true, paused = true, lastFrameNanos = now - 2 * second, nowNanos = now, maxAgeNanos = second))
    }

    @Test
    fun `sound playing under a still picture is progress, not a stall`() {
        assertEquals(now, RelayPicture.progressNanos(lastFrameNanos = now - 30 * second, lastSoundNanos = now))
        assertEquals(now, RelayPicture.progressNanos(lastFrameNanos = now, lastSoundNanos = 0L))
    }
}
