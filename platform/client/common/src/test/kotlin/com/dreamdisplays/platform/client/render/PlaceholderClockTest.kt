package com.dreamdisplays.platform.client.render

import com.dreamdisplays.platform.client.displays.DisplayFace
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaceholderClockTest {
    private val frame = 16_666_667L

    private data class Look(val shade: Int, val segment: List<Float>?)

    private fun look(clock: PlaceholderClock) =
        Look(PlaceholderLook.backdropShade(clock.breatheNanos), PlaceholderLook.segment(clock.sweepNanos)?.toList())

    private val restLook = Look(PlaceholderLook.IDLE_SHADE, PlaceholderLook.segment(0L)!!.toList())

    /** Runs [clock] through [frames] frames of [face] from [start]; returns the looks and the time after. */
    private fun run(clock: PlaceholderClock, face: DisplayFace, start: Long, frames: Int): Pair<List<Look>, Long> {
        var t = start
        val looks = (0 until frames).map {
            clock.advance(face, t)
            t += frame
            look(clock)
        }
        return looks to t
    }

    @Test
    fun anIdleDisplayStaysAtRest() {
        val clock = PlaceholderClock()
        val (looks, _) = run(clock, DisplayFace.IDLE, 5_000_000_000L, 300)
        assertTrue(looks.all { it == restLook })
        assertTrue(clock.atRest)
    }

    @Test
    fun theFirstLoadingFrameIsTheFrozenBar() {
        val clock = PlaceholderClock()
        val (_, t) = run(clock, DisplayFace.IDLE, 123_456_789L, 30)
        val (loading, _) = run(clock, DisplayFace.LOADING, t, 3)
        assertEquals(restLook, loading[0])
        // and it moves from there: the second frame is one frame of sweep past the middle
        assertContentEquals(PlaceholderLook.segment(frame)!!.toList(), loading[1].segment)
        assertEquals(PlaceholderLook.segment(2 * frame)!!.toList(), loading[2].segment)
    }

    @Test
    fun loadingAnimatesFromTheMiddleWithElapsedTime() {
        val t0 = 777_000_000L
        for (elapsed in listOf(10_000_000L, 400_000_000L, 1_299_000_000L, 2_500_000_000L, 9_000_000_000L)) {
            // a frame every 0.1 s keeps the clock fed
            var t = t0
            val fresh = PlaceholderClock().also { it.advance(DisplayFace.IDLE, t0 - frame); it.advance(DisplayFace.LOADING, t0) }
            while (t < t0 + elapsed) { t = minOf(t + 100_000_000L, t0 + elapsed); fresh.advance(DisplayFace.LOADING, t) }
            assertEquals(elapsed % PlaceholderLook.SWEEP_PERIOD_NANOS, fresh.sweepNanos)
            assertEquals(elapsed % PlaceholderLook.BREATHE_PERIOD_NANOS, fresh.breatheNanos)
        }
    }

    @Test
    fun aClearedDisplaySettlesIntoTheFrozenBarWithoutAJump() {
        val clock = PlaceholderClock()
        var t = 0L
        run(clock, DisplayFace.IDLE, t, 2).also { t = it.second }
        run(clock, DisplayFace.LOADING, t, 47).also { t = it.second } // stop somewhere mid-sweep, mid-breath
        assertTrue(!clock.atRest)
        val (settling, _) = run(clock, DisplayFace.IDLE, t, 120)
        assertEquals(restLook, settling.last())
        assertTrue(clock.atRest)
        // it reaches rest within one sweep period and then stays
        val firstRest = settling.indexOf(restLook)
        assertTrue(firstRest in 0..(PlaceholderLook.SWEEP_PERIOD_NANOS / frame).toInt() + 1)
        assertTrue(settling.drop(firstRest).all { it == restLook })
    }

    @Test
    fun noTransitionBetweenIdleAndLoadingJumps() {
        val clock = PlaceholderClock()
        val script = listOf(
            DisplayFace.IDLE to 20, DisplayFace.LOADING to 37, DisplayFace.IDLE to 15, DisplayFace.LOADING to 90,
            DisplayFace.IDLE to 100, DisplayFace.LOADING to 5, DisplayFace.IDLE to 3, DisplayFace.LOADING to 61,
        )
        var t = 42L
        val looks = mutableListOf<Look>()
        for ((face, n) in script) run(clock, face, t, n).also { looks += it.first; t = it.second }
        // One frame of sweep moves the segment ~1.5% of the screen; anything bigger (bar re-entering the track aside) is a jump
        val maxStep = (PlaceholderLook.TRACK_X1 - PlaceholderLook.TRACK_X0 + PlaceholderLook.SEGMENT_WIDTH) *
                frame.toFloat() / PlaceholderLook.SWEEP_PERIOD_NANOS + 1e-4f
        for ((a, b) in looks.zipWithNext()) {
            assertTrue(abs(a.shade - b.shade) <= 1, "shade $a -> $b")
            val sa = a.segment
            val sb = b.segment
            if (sa != null && sb != null) {
                // the sweep's wrap: the segment has shrunk away at the right end and grows back in at the left
                if (sa[0] > sb[0] + 0.5f) {
                    assertTrue(sa[1] - sa[0] <= 2 * maxStep && sb[1] - sb[0] <= 2 * maxStep, "wrap $sa -> $sb")
                    continue
                }
                val endsMove = maxOf(abs(sa[0] - sb[0]), abs(sa[1] - sb[1]))
                assertTrue(endsMove <= maxStep, "segment $sa -> $sb")
            }
        }
    }

    @Test
    fun anErrorAPictureOrALongGapPutsTheBarBackAtRest() {
        for (face in listOf(DisplayFace.ERROR, DisplayFace.PLAYING)) {
            val clock = PlaceholderClock()
            val (_, t) = run(clock, DisplayFace.LOADING, 0L, 40)
            clock.advance(face, t)
            assertTrue(clock.atRest)
            clock.advance(DisplayFace.LOADING, t + frame)
            assertEquals(restLook, look(clock))
        }
        val clock = PlaceholderClock()
        val (_, t) = run(clock, DisplayFace.LOADING, 0L, 40)
        clock.advance(DisplayFace.IDLE, t + 5_000_000_000L)
        assertEquals(restLook, look(clock))
    }

    @Test
    fun settleStopsAtTheNextRest() {
        assertEquals(0L, PlaceholderClock.settle(0L, 500L, 1000L))
        assertEquals(700L, PlaceholderClock.settle(200L, 500L, 1000L))
        assertEquals(1000L, PlaceholderClock.settle(700L, 500L, 1000L))
        assertEquals(2000L, PlaceholderClock.settle(2000L, 500L, 1000L))
    }
}
