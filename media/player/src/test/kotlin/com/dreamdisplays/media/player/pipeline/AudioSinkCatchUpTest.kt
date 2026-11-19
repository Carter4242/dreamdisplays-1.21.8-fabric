package com.dreamdisplays.media.player.pipeline

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

class AudioSinkCatchUpTest {
    private val ms = 1_000_000L

    /** PCM for [seconds] of sound, written after [delayMs] (a separate audio stream that is slow to open). */
    private fun lateSound(delayMs: Long, seconds: Int): PipedInputStream {
        val input = PipedInputStream(1 shl 20)
        val out = PipedOutputStream(input)
        thread(isDaemon = true) {
            Thread.sleep(delayMs)
            val chunk = ByteArray(AudioSink.SAMPLE_RATE * AudioSink.BYTES_PER_FRAME / 10)
            runCatching { repeat(seconds * 10) { out.write(chunk) } }
            runCatching { out.close() }
        }
        return input
    }

    @Test
    fun `sound that arrives late skips to where the picture got meanwhile`() {
        val sink = AudioSink("test")
        val started = System.nanoTime()
        // The picture's wall clock started at the seek target (0) when its first frame showed, before any sound
        val picture = { System.nanoTime() - started }
        val input = lateSound(delayMs = 600, seconds = 3)
        val skipped = sink.skipCatchUp(input, AudioSink.CatchUp(0L, picture), AtomicBoolean(), AtomicBoolean())
        assertTrue(skipped >= 500 * ms, "Late sound must join the picture, not the old target (skipped ${skipped / ms} ms).")
        assertTrue(skipped <= 1_500 * ms, "Skipped ${skipped / ms} ms, far past the picture.")
    }

    @Test
    fun `sound that is ready at once skips nothing`() {
        val sink = AudioSink("test")
        val input = lateSound(delayMs = 0, seconds = 1)
        Thread.sleep(50)
        val skipped = sink.skipCatchUp(input, AudioSink.CatchUp(5_000 * ms, { 5_000 * ms }), AtomicBoolean(), AtomicBoolean())
        assertTrue(skipped == 0L, "Skipped ${skipped / ms} ms with the picture still at the target.")
    }
}
