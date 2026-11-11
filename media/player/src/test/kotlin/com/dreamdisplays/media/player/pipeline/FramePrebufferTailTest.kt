package com.dreamdisplays.media.player.pipeline

import com.dreamdisplays.api.media.model.FramePixelFormat
import com.dreamdisplays.api.media.player.FrameUploader
import com.dreamdisplays.api.media.player.GpuTextureRef
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** End of file must not cut the prebuffered tail: the end is reported once the queued frames have played. */
class FramePrebufferTailTest {
    private val frameNs = 20_000_000L
    private val frameBytes = 16
    private val stop = AtomicBoolean()
    private val terminated = AtomicBoolean()

    private val surface = FrameSurface("test", {
        object : FrameUploader {
            override fun canUpload() = false
            override fun uploadInterleaved(target: GpuTextureRef, src: ByteBuffer, format: FramePixelFormat) = false
            override fun uploadPlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, src: ByteBuffer) = false
            override fun cleanup() {}
        }
    })

    @AfterTest
    fun tearDown() {
        stop.set(true)
    }

    private fun prebuffer(clock: () -> Long, presented: AtomicInteger): FramePrebuffer =
        FramePrebuffer.create(surface, frameNs, clock, {}, terminated, stop, "test", presentPreview = false)
            .also { it.onPresent = { presented.incrementAndGet() } }

    private fun submitFrames(p: FramePrebuffer, count: Int) {
        var spare = surface.takeOrAllocate(frameBytes)
        for (i in 0 until count) {
            spare.clear(); spare.limit(frameBytes)
            spare = p.submit(spare, i * frameNs, frameBytes)
        }
    }

    @Test
    fun `a short tail that never filled the cushion plays out before the end is reported`() {
        val t0 = System.nanoTime()
        val presented = AtomicInteger()
        val p = prebuffer({ System.nanoTime() - t0 }, presented)
        submitFrames(p, 8) // Fewer than the 400 ms prefill: playout has not started yet
        val started = System.nanoTime()
        assertTrue(p.awaitTailPlayed(), "Tail did not play out.")
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(waitedMs >= 80, "Returned after $waitedMs ms, before the paced tail went out.")
        assertTrue(presented.get() >= 7, "Only ${presented.get()} of 8 frames were presented.")
    }

    @Test
    fun `a pending seek stops the wait at once`() {
        val presented = AtomicInteger()
        val p = prebuffer({ -1L }, presented)
        submitFrames(p, 8)
        val started = System.nanoTime()
        assertFalse(p.awaitTailPlayed { true })
        assertTrue((System.nanoTime() - started) / 1_000_000 < 100)
    }

    @Test
    fun `a pause during the tail holds the wait past its bound, and the end comes once it played after the resume`() {
        val presented = AtomicInteger()
        val heldUntil = System.nanoTime() + 4_000_000_000L // Longer than the 3 s bound
        val firstPts = 10_000_000_000L
        // The clock stands before the frames while held, then runs through them: nothing plays out until the resume
        val p = prebuffer({ if (System.nanoTime() < heldUntil) 0L else firstPts + System.nanoTime() - heldUntil }, presented)
        var spare = surface.takeOrAllocate(frameBytes)
        for (i in 0 until 8) {
            spare.clear(); spare.limit(frameBytes)
            spare = p.submit(spare, firstPts + i * frameNs, frameBytes)
        }
        val started = System.nanoTime()
        val played = p.awaitTailPlayed(held = { System.nanoTime() < heldUntil })
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(played, "The tail was given up while paused (waited $waitedMs ms).")
        assertTrue(waitedMs >= 4_000, "Returned after $waitedMs ms, before the resume.")
    }

    @Test
    fun `a frozen clock gives up after the bound`() {
        val presented = AtomicInteger()
        // Clock stuck well before the frames: the consumer waits on the first one
        val p = prebuffer({ 0L }, presented)
        var spare = surface.takeOrAllocate(frameBytes)
        for (i in 0 until 4) {
            spare.clear(); spare.limit(frameBytes)
            spare = p.submit(spare, 10_000_000_000L + i * frameNs, frameBytes)
        }
        val started = System.nanoTime()
        p.awaitTailPlayed()
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(waitedMs in 400..3_500, "Waited $waitedMs ms.")
        assertEquals(0, presented.get())
    }

    @Test
    fun `the tail bound covers slow playback`() {
        val frame30 = 33_333_333L
        val wallAt01x = 16 * frame30 * 10 // 16 queued frames at 0.1x
        assertTrue(FramePrebuffer.tailWaitBudgetNanos(16, frame30, 0.1) > wallAt01x)
        assertEquals(
            FramePrebuffer.tailWaitBudgetNanos(16, frame30, 1.0), FramePrebuffer.tailWaitBudgetNanos(16, frame30, 4.0),
            "Fast playback keeps the normal bound.",
        )
    }
}
