package com.dreamdisplays.core.karaoke

import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [VoiceTrackAligner] on synthetic audio: a "vocal" of syllables in the track, and a singer copying its timing. */
class KaraokeAlignerTest {
    private val t0 = 5_000_000_000_000L
    private val seconds = 12.0

    /** Syllable onsets / lengths / levels: what a melody's timing looks like to an envelope. */
    private class Syllables(seed: Int, totalMs: Double) {
        val starts = ArrayList<Double>()
        val lengths = ArrayList<Double>()
        val levels = ArrayList<Double>()

        /** A human copy: every syllable up to [jitterMs] early or late, its own length and level. */
        fun sungLoosely(seed: Int, jitterMs: Double): Syllables {
            val random = Random(seed)
            val copy = Syllables(seed, 0.0)
            copy.starts.clear(); copy.lengths.clear(); copy.levels.clear()
            for (i in starts.indices) {
                copy.starts += starts[i] + (random.nextDouble() * 2 - 1) * jitterMs
                copy.lengths += lengths[i] * (0.7 + random.nextDouble() * 0.6)
                copy.levels += 0.3 + random.nextDouble() * 0.7
            }
            copy.starts.sort()
            return copy
        }

        init {
            val random = Random(seed)
            var t = 100.0
            while (t < totalMs) {
                starts += t
                lengths += 80.0 + random.nextDouble() * 170.0
                levels += 0.3 + random.nextDouble() * 0.7
                t += 150.0 + random.nextDouble() * 300.0
            }
        }

        /** Envelope at [ms]: a quick attack and a decay, so each syllable has a clear onset. */
        fun at(ms: Double): Double {
            var i = starts.binarySearch(ms).let { if (it < 0) -it - 2 else it }
            if (i < 0) return 0.0
            val into = ms - starts[i]
            if (into > lengths[i]) return 0.0
            val attack = (into / 10.0).coerceAtMost(1.0)
            return levels[i] * attack * (1.0 - 0.5 * into / lengths[i])
        }
    }

    private fun feedTrack(
        aligner: VoiceTrackAligner, vocal: Syllables, seed: Int, bed: Double = 0.05,
        gapMs: ClosedFloatingPointRange<Double>? = null,
    ) {
        val random = Random(seed)
        val rate = 44_100
        val chunkFrames = 2_205 // 50 ms, like the player's chunks
        val pcm = ByteArray(chunkFrames * 4)
        val total = (seconds * rate).toInt()
        var frame = 0
        while (frame < total) {
            for (f in 0 until chunkFrames) {
                val ms = (frame + f) * 1000.0 / rate
                // A steady instrumental bed (a hum and noise) under the vocal
                val v = vocal.at(ms) * (random.nextDouble() * 2 - 1) * 0.8 +
                        bed * (random.nextDouble() * 2 - 1) + 0.2 * sin(ms * 0.69)
                val s = (v.coerceIn(-1.0, 1.0) * 32_000).toInt()
                pcm[f * 4] = s.toByte(); pcm[f * 4 + 1] = (s shr 8).toByte()
                pcm[f * 4 + 2] = s.toByte(); pcm[f * 4 + 3] = (s shr 8).toByte()
            }
            if (gapMs == null || frame * 1000.0 / rate !in gapMs) {
                aligner.track.addStereo16(pcm, pcm.size, rate, t0 + frame * 1_000_000_000L / rate)
            }
            frame += chunkFrames
        }
    }

    /** The singer: the same timing, [delayMs] late, their own voice (other noise), plus a mic noise floor. */
    private fun feedMic(aligner: VoiceTrackAligner, voice: Syllables?, delayMs: Double, seed: Int, noise: Double = 0.01) {
        val random = Random(seed)
        val rate = 48_000
        val frame = ShortArray(960)
        val total = (seconds * rate).toInt()
        var sample = 0
        while (sample < total) {
            for (i in frame.indices) {
                val ms = (sample + i) * 1000.0 / rate
                val v = (voice?.at(ms - delayMs) ?: 0.0) * (random.nextDouble() * 2 - 1) + noise * (random.nextDouble() * 2 - 1)
                frame[i] = (v.coerceIn(-1.0, 1.0) * 32_000).toInt().toShort()
            }
            aligner.mic.addMono16(frame, frame.size, rate, t0 + sample * 1_000_000_000L / rate)
            sample += frame.size
        }
    }

    private val now = t0 + (seconds * 1e9).toLong() + 100_000_000L

    @Test
    fun findsAKnownDelayToWithinAFewMs() {
        val vocal = Syllables(1, seconds * 1000)
        for (delay in listOf(-150.0, 0.0, 120.0, 333.0, 600.0)) {
            val aligner = VoiceTrackAligner()
            feedTrack(aligner, vocal, seed = 2)
            feedMic(aligner, vocal, delay, seed = 3)
            val estimate = assertNotNull(aligner.estimate(now), "delay $delay: ${aligner.lastSkip}")
            println("delay $delay ms -> $estimate")
            assertTrue(abs(estimate.offsetMs - delay) <= 5.0, "delay $delay estimated ${estimate.offsetMs}")
            assertTrue(estimate.confidence >= 0.35, "delay $delay confidence ${estimate.confidence}")
        }
    }

    @Test
    fun stillFindsItThroughANoisyMic() {
        val vocal = Syllables(4, seconds * 1000)
        val aligner = VoiceTrackAligner()
        feedTrack(aligner, vocal, seed = 5, bed = 0.15)
        feedMic(aligner, vocal, 250.0, seed = 6, noise = 0.06)
        val estimate = assertNotNull(aligner.estimate(now), "${aligner.lastSkip}")
        println("noisy, delay 250 ms -> $estimate")
        assertTrue(abs(estimate.offsetMs - 250.0) <= 10.0, "estimated ${estimate.offsetMs}")
    }

    @Test
    fun aLooseHumanCopyStillGivesTheAverageDelay() {
        val vocal = Syllables(17, seconds * 1000)
        val aligner = VoiceTrackAligner()
        feedTrack(aligner, vocal, seed = 18, bed = 0.1)
        feedMic(aligner, vocal.sungLoosely(19, jitterMs = 40.0), 200.0, seed = 20, noise = 0.03)
        val estimate = assertNotNull(aligner.estimate(now), "${aligner.lastSkip}")
        println("loose, delay 200 ms -> $estimate")
        assertTrue(abs(estimate.offsetMs - 200.0) <= 15.0, "estimated ${estimate.offsetMs}")
        assertTrue(estimate.confidence >= 0.35, "confidence ${estimate.confidence}")
    }

    @Test
    fun saysNothingWhileTheSingerIsSilent() {
        val vocal = Syllables(7, seconds * 1000)
        val noMic = VoiceTrackAligner()
        feedTrack(noMic, vocal, seed = 8)
        assertNull(noMic.estimate(now))
        assertEquals(VoiceTrackAligner.Skip.NO_DATA, noMic.lastSkip)

        val quiet = VoiceTrackAligner()
        feedTrack(quiet, vocal, seed = 8)
        feedMic(quiet, null, 0.0, seed = 9, noise = 0.01)
        assertNull(quiet.estimate(now))
        assertEquals(VoiceTrackAligner.Skip.SILENT_MIC, quiet.lastSkip)
    }

    @Test
    fun aSingerNotFollowingTheSongGivesNoConfidentAnswer() {
        for (seed in 0 until 8) {
            val aligner = VoiceTrackAligner()
            feedTrack(aligner, Syllables(100 + seed, seconds * 1000), seed = 200 + seed)
            feedMic(aligner, Syllables(300 + seed, seconds * 1000), 0.0, seed = 400 + seed)
            val estimate = aligner.estimate(now)
            println("unrelated singing $seed -> $estimate (${aligner.lastSkip})")
            assertTrue(estimate == null || estimate.confidence < 0.35, "seed $seed confidence ${estimate?.confidence}")
        }
    }

    @Test
    fun missingTrackAudioIsNotGuessedAround() {
        val vocal = Syllables(14, seconds * 1000)
        val aligner = VoiceTrackAligner()
        // The display's audio stalled for two seconds inside the window
        feedTrack(aligner, vocal, seed = 15, gapMs = 5_000.0..7_000.0)
        feedMic(aligner, vocal, 100.0, seed = 16)
        assertNull(aligner.estimate(now))
        assertEquals(VoiceTrackAligner.Skip.TRACK_MISSING, aligner.lastSkip)
    }
}
