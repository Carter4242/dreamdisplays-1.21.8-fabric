package com.dreamdisplays.core.karaoke

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BurstAnchorTest {
    private val frame = 20_000_000L
    private val ms = 1_000_000L

    @Test
    fun frameOffsetsAnchorOnTheLeastDelayedFrameNotTheFirst() {
        val anchor = BurstAnchor()
        val base = 1_000 * ms
        // Frame 0 is 15 ms late, frame 2 arrives without jitter
        val jitter = longArrayOf(15, 6, 0, 9, 4)
        var burst = -1
        for (i in jitter.indices) burst = anchor.onFrame(base + i * frame + jitter[i] * ms, 100L + i)
        assertEquals(base, anchor.estimate(burst, 100, lock = false))
        assertEquals(base + 4 * frame, anchor.estimate(burst, 104, lock = false))
    }

    @Test
    fun aLockedAnchorIgnoresLaterImprovements() {
        val anchor = BurstAnchor()
        val b = anchor.onFrame(10 * ms, 0)
        assertEquals(10 * ms, anchor.estimate(b, 0, lock = true))
        anchor.onFrame(frame + 2 * ms, 1) // 8 ms earlier on the grid than frame 0
        assertEquals(10 * ms + frame, anchor.estimate(b, 1, lock = false))
    }

    @Test
    fun aStopFrameEndsTheBurstAndTheNextFrameStartsANewOne() {
        val anchor = BurstAnchor()
        val first = anchor.onFrame(0, 10)
        anchor.onFrame(frame, 11)
        assertEquals(first, anchor.onFrame(2 * frame, 12, stop = true))
        // Silence of 3 s: the numbering continued at 13, but capture time did not
        val second = anchor.onFrame(3_000 * ms, 13)
        assertNotEquals(first, second)
        assertEquals(3_000 * ms, anchor.estimate(second, 13, lock = false))
        assertEquals(2, anchor.bursts)
    }

    @Test
    fun aLostStopFrameStillStartsANewBurstWhenTheGridIsFarOff() {
        val anchor = BurstAnchor()
        val first = anchor.onFrame(0, 0)
        anchor.onFrame(frame, 1)
        // Stop frame 2 lost; frame 3 comes after 1.5 s of silence
        val second = anchor.onFrame(1_500 * ms, 3)
        assertNotEquals(first, second)
    }

    @Test
    fun lostFramesInsideABurstKeepTheGrid() {
        val anchor = BurstAnchor()
        val first = anchor.onFrame(0, 0)
        anchor.onFrame(frame, 1)
        // Frames 2..4 lost on the way
        assertEquals(first, anchor.onFrame(5 * frame + 3 * ms, 5))
        assertEquals(5 * frame, anchor.estimate(first, 5, lock = false))
    }

    @Test
    fun aStragglerOfThePreviousBurstIsStillPlaced() {
        val anchor = BurstAnchor()
        val first = anchor.onFrame(0, 0)
        anchor.onFrame(frame, 1)
        anchor.onFrame(3 * frame, 3, stop = true)
        val second = anchor.onFrame(2_000 * ms, 4)
        // Frame 2 of the first burst, reordered on the way
        assertEquals(first, anchor.onFrame(2 * frame + 40 * ms, 2))
        assertEquals(2 * frame, anchor.estimate(first, 2, lock = false))
        assertNotEquals(first, second)
        assertEquals(-1, anchor.onFrame(2_100 * ms, -5))
    }

    @Test
    fun aNewBurstWhoseFirstFrameArrivesSecondKeepsTheNewGrid() {
        val anchor = BurstAnchor()
        anchor.onFrame(0, 0)
        anchor.onFrame(frame, 1, stop = true)
        // After 2 s of silence, frame 3 overtakes frame 2 on the way
        val burst = anchor.onFrame(2_000 * ms + frame + 1 * ms, 3)
        assertEquals(burst, anchor.onFrame(2_000 * ms + 30 * ms, 2))
        assertEquals(2_000 * ms + 1 * ms, anchor.estimate(burst, 2, lock = false))
        assertEquals(2_000 * ms + 1 * ms + frame, anchor.estimate(burst, 3, lock = false))
    }

    @Test
    fun anUnknownBurstHasNoEstimate() {
        assertNull(BurstAnchor().estimate(42, 0, lock = false))
    }

    @Test
    fun aLastingDelayStepMovesALockedAnchorLater() {
        val anchor = BurstAnchor(windowFrames = 50)
        val b = anchor.onFrame(0, 0)
        anchor.estimate(b, 0, lock = true)
        for (i in 1 until 40) anchor.onFrame(i * frame, i.toLong())
        // The singer's path gets 80 ms slower for good
        for (i in 40 until 200) anchor.onFrame(i * frame + 80 * ms, i.toLong())
        assertEquals(1, anchor.reanchors)
        assertEquals(199 * frame + 80 * ms, anchor.estimate(b, 199, lock = false))
    }

    @Test
    fun singleSpikesDoNotMoveALockedAnchor() {
        val anchor = BurstAnchor()
        val random = Random(7)
        val b = anchor.onFrame(0, 0)
        anchor.estimate(b, 0, lock = true)
        for (i in 1 until 500) {
            val spike = if (random.nextInt(50) == 0) 120 * ms else 0L
            anchor.onFrame(i * frame + random.nextLong(0, 8 * ms) + spike, i.toLong())
        }
        assertEquals(0, anchor.reanchors)
    }

    @Test
    fun theLearnedPriorTakesOutTheTypicalExcessOfTheFirstFrames() {
        val anchor = BurstAnchor()
        // Every burst: the frames seen before the lock all arrive 12 ms late, later ones without jitter
        var t = 0L
        var seq = 0L
        repeat(10) {
            val start = t
            val b = anchor.onFrame(start + 12 * ms, seq)
            anchor.estimate(b, seq, lock = true)
            for (i in 1 until 60) anchor.onFrame(start + i * frame, seq + i)
            anchor.onFrame(start + 60 * frame, seq + 60, stop = true)
            seq += 61
            t += 5_000 * ms
        }
        assertEquals(12 * ms, anchor.prior())
        val b = anchor.onFrame(t + 12 * ms, seq)
        assertEquals(t, anchor.estimate(b, seq, lock = true))
    }

    @Test
    fun anchorEstimatesStayCloseToTheTruthUnderJitter() {
        val random = Random(1)
        val anchor = BurstAnchor()
        var worst = 0L
        var t = 0L
        var seq = 0L
        repeat(30) {
            val start = t
            val length = random.nextInt(50, 300)
            var burst = -1
            for (i in 0 until length) {
                val jitter = (-kotlin.math.ln(1 - random.nextDouble()) * 4.0 * ms).toLong()
                burst = anchor.onFrame(start + i * frame + jitter, seq + i)
                // Lock after five frames, as a real send would about 100 ms in
                if (i == 5) anchor.estimate(burst, seq, lock = true)
            }
            val error = anchor.estimate(burst, seq, lock = false)!! - start
            worst = maxOf(worst, kotlin.math.abs(error))
            anchor.onFrame(start + length * frame, seq + length, stop = true)
            seq += length + 1
            t += (length + random.nextInt(15, 100)) * frame
        }
        assertTrue(worst < 10 * ms, "worst anchor error ${worst / ms} ms")
    }
}
