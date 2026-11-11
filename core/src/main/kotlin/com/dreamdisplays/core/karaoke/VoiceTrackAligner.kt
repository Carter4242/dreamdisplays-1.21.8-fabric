package com.dreamdisplays.core.karaoke

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * A vocal-band energy envelope on an absolute time grid: audio goes in with the time its first sample is (or was)
 * heard, and comes out as one mean-square energy per [binNanos] bin, kept for the last [capacity] bins.
 *
 * Each sample is down-mixed to mono, band-limited to roughly 200 Hz..4 kHz (two one-pole high-passes and a one-pole
 * low-pass: cheap, and enough to take the bass and cymbals out of a track and the rumble out of a mic), squared and
 * averaged into its bin. A bin no audio reached stays missing ([energyAt] returns -1).
 *
 * Feeding ([addStereo16], [addMono16]) allocates nothing and costs a few operations per sample; it is synchronised,
 * so audio threads can feed while another thread reads ([copyEnergies]).
 */
class EnvelopeRecorder(
    val binNanos: Long = 5_000_000L,
    val capacity: Int = 4_096,
) {
    private val energies = FloatArray(capacity)
    private val bins = LongArray(capacity) { Long.MIN_VALUE }
    private var sampleRate = 0
    private var hp = 0.0
    private var lp = 0.0
    private var hp1Prev = 0.0
    private var hp1Out = 0.0
    private var hp2Prev = 0.0
    private var hp2Out = 0.0
    private var lpOut = 0.0
    private var currentBin = Long.MIN_VALUE
    private var sum = 0.0
    private var count = 0

    /** The newest bin written so far (complete or not), or [Long.MIN_VALUE]. */
    @Volatile
    var latestBin: Long = Long.MIN_VALUE
        private set

    /** Feeds [len] bytes of 16-bit little-endian stereo at [rate] Hz whose first frame is heard at [firstNanos]. */
    @Synchronized
    fun addStereo16(pcm: ByteArray, len: Int, rate: Int, firstNanos: Long) {
        if (rate <= 0) return
        prepare(rate)
        val frames = len / 4
        val period = 1e9 / rate
        var bin = Math.floorDiv(firstNanos, binNanos)
        var edge = ((bin + 1) * binNanos - firstNanos).toDouble()
        for (f in 0 until frames) {
            val i = f * 4
            val l = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            val r = ((pcm[i + 2].toInt() and 0xFF) or (pcm[i + 3].toInt() shl 8)).toShort().toInt()
            val t = f * period
            while (t >= edge) {
                bin++
                edge += binNanos
            }
            push((l + r) * (0.5 / 32_768.0), bin)
        }
    }

    /** Feeds [count] 16-bit mono samples of [samples] at [rate] Hz whose first one is heard at [firstNanos]. */
    @Synchronized
    fun addMono16(samples: ShortArray, count: Int, rate: Int, firstNanos: Long) {
        if (rate <= 0) return
        prepare(rate)
        val period = 1e9 / rate
        var bin = Math.floorDiv(firstNanos, binNanos)
        var edge = ((bin + 1) * binNanos - firstNanos).toDouble()
        for (i in 0 until minOf(count, samples.size)) {
            val t = i * period
            while (t >= edge) {
                bin++
                edge += binNanos
            }
            push(samples[i] / 32_768.0, bin)
        }
    }

    private fun prepare(rate: Int) {
        if (rate == sampleRate) return
        sampleRate = rate
        val dt = 1.0 / rate
        val rcHigh = 1.0 / (2 * PI * HIGH_PASS_HZ)
        val rcLow = 1.0 / (2 * PI * LOW_PASS_HZ)
        hp = rcHigh / (rcHigh + dt)
        lp = dt / (rcLow + dt)
    }

    private fun push(x: Double, bin: Long) {
        hp1Out = hp * (hp1Out + x - hp1Prev)
        val hp2In = hp1Out
        hp2Out = hp * (hp2Out + hp2In - hp2Prev)
        hp2Prev = hp2In
        hp1Prev = x
        lpOut += lp * (hp2Out - lpOut)
        // Out-of-order audio (a chunk stamped a little early) counts toward the bin in progress
        if (bin > currentBin) {
            flush()
            currentBin = bin
        }
        sum += lpOut * lpOut
        count++
    }

    private fun flush() {
        if (count > 0 && currentBin != Long.MIN_VALUE) {
            val slot = Math.floorMod(currentBin, capacity.toLong()).toInt()
            energies[slot] = (sum / count).toFloat()
            bins[slot] = currentBin
            latestBin = currentBin
        }
        sum = 0.0
        count = 0
    }

    /** The energy of [bin], or -1 when no audio reached it (or it is too old). */
    @Synchronized
    fun energyAt(bin: Long): Float {
        val slot = Math.floorMod(bin, capacity.toLong()).toInt()
        return if (bins[slot] == bin) energies[slot] else -1f
    }

    /** Copies the energies of bins [from] until [from] + [n] into [out] (-1 = missing). */
    @Synchronized
    fun copyEnergies(from: Long, n: Int, out: FloatArray) {
        for (i in 0 until n) {
            val bin = from + i
            val slot = Math.floorMod(bin, capacity.toLong()).toInt()
            out[i] = if (bins[slot] == bin) energies[slot] else -1f
        }
    }

    /** Forgets everything (a new song or a new singer). */
    @Synchronized
    fun reset() {
        bins.fill(Long.MIN_VALUE)
        currentBin = Long.MIN_VALUE
        latestBin = Long.MIN_VALUE
        sum = 0.0
        count = 0
        hp1Prev = 0.0
        hp1Out = 0.0
        hp2Prev = 0.0
        hp2Out = 0.0
        lpOut = 0.0
    }

    private companion object {
        const val HIGH_PASS_HZ = 200.0
        const val LOW_PASS_HZ = 4_000.0
    }
}

/**
 * Finds how late a singer's voice is against the track they heard, from the two vocal-band envelopes.
 *
 * The singer's client feeds [track] with the display audio as it leaves the speakers, and [mic] with voice chat's mic
 * frames at the time the voice reached the mic (by the same latency model the server uses, so the result is exactly
 * what the server's timing gets wrong for this singer). [estimate] takes the last [windowMs] and looks for the lag
 * within [minLagMs]..[maxLagMs] where the mic's onsets (rises of log energy) line up best with the track's, by
 * normalised cross-correlation, refined to below a bin by a parabola through the peak.
 *
 * It answers nothing when the window is not usable: track audio missing, a mic that is mostly silent (the singer is
 * not singing), or no clear peak. [Estimate.confidence] (0..1) grows with the peak correlation above what unrelated
 * audio reaches, and is held down unless the peak stands out of the correlation at lags over 50 ms away from it (a
 * beat that repeats gives several peaks alike).
 *
 * PRIVACY: this runs only on the singer's own client, only while they sing; the mic audio is reduced to this envelope
 * in place and never stored or sent anywhere. Only the resulting offset is reported.
 *
 * [estimate] allocates only its result and must be called from one thread at a time (a background thread); the
 * recorders take audio from any thread.
 */
class VoiceTrackAligner(
    val binMs: Int = 5,
    val windowMs: Int = 8_000,
    val minLagMs: Int = -300,
    val maxLagMs: Int = 800,
    /** Fraction of the window the singer's mic must be clearly above its own floor. */
    private val minMicActivity: Double = 0.2,
    private val minTrackPresence: Double = 0.9,
) {
    val track = EnvelopeRecorder(binMs * 1_000_000L)
    val mic = EnvelopeRecorder(binMs * 1_000_000L)

    /**
     * One answer: the voice is [offsetMs] late (negative: early). [peak] is the correlation there, [prominence] how
     * many standard deviations it stands above the correlation at lags well away from it.
     */
    data class Estimate(
        val offsetMs: Double, val confidence: Double, val peak: Double, val prominence: Double, val micActivity: Double,
    )

    /** Why the last [estimate] returned null (for logs). */
    enum class Skip { NONE, NO_DATA, TRACK_MISSING, SILENT_MIC, QUIET_TRACK, NO_PEAK }

    @Volatile
    var lastSkip: Skip = Skip.NONE
        private set

    private val windowBins = windowMs / binMs
    private val minLag = Math.floorDiv(minLagMs, binMs)
    private val maxLag = maxLagMs / binMs
    private val lagCount = maxLag - minLag + 1

    private val trackRaw = FloatArray(windowBins + PRE_BINS)
    private val micRaw = FloatArray(windowBins + lagCount - 1 + PRE_BINS)
    private val a = DoubleArray(windowBins)
    private val b = DoubleArray(windowBins + lagCount - 1)
    private val logScratch = DoubleArray(maxOf(trackRaw.size, micRaw.size))
    private val sortScratch = FloatArray(micRaw.size)
    private val r = DoubleArray(lagCount)
    private val smoothScratch = DoubleArray(b.size)

    /**
     * The voice's offset over the window ending [micSettleMs] before [nowNanos] (mic frames arrive a little after the
     * voice), or null when the window is not usable ([lastSkip] says why).
     */
    fun estimate(nowNanos: Long, micSettleMs: Int = 100): Estimate? {
        val binNanos = binMs * 1_000_000L
        val micEnd = Math.floorDiv(nowNanos - micSettleMs * 1_000_000L, binNanos)
        val trackLatest = track.latestBin
        if (trackLatest == Long.MIN_VALUE || mic.latestBin == Long.MIN_VALUE) return skip(Skip.NO_DATA)
        // a[t] = track at t, b[t + lag] = mic at t + lag: the mic must reach maxLag past the window's end
        val end = minOf(trackLatest, micEnd - maxLag)
        val start = end - windowBins
        track.copyEnergies(start - PRE_BINS, trackRaw.size, trackRaw)
        mic.copyEnergies(start + minLag - PRE_BINS, micRaw.size, micRaw)

        var present = 0
        for (i in PRE_BINS until trackRaw.size) if (trackRaw[i] >= 0f) present++
        if (present < windowBins * minTrackPresence) return skip(Skip.TRACK_MISSING)
        // No mic frame = voice chat sent nothing (not talking): silence
        for (i in micRaw.indices) if (micRaw[i] < 0f) micRaw[i] = 0f
        for (i in trackRaw.indices) if (trackRaw[i] < 0f) trackRaw[i] = 0f

        val micActivity = activity(micRaw)
        if (micActivity < minMicActivity) return skip(Skip.SILENT_MIC)
        // Music never drops far below its own level, so the track only needs to be audible (not muted or silent)
        var audible = 0
        for (i in PRE_BINS until trackRaw.size) if (trackRaw[i] >= TRACK_FLOOR) audible++
        if (audible < windowBins / 2) return skip(Skip.QUIET_TRACK)

        onsets(trackRaw, a)
        onsets(micRaw, b)
        val peak = correlate() ?: return skip(Skip.NO_PEAK)
        lastSkip = Skip.NONE
        return peak.copy(micActivity = micActivity)
    }

    private fun skip(why: Skip): Estimate? {
        lastSkip = why
        return null
    }

    /** Fraction of bins at least [ACTIVE_RATIO] (12 dB) above the 10th percentile and above an absolute floor. */
    private fun activity(raw: FloatArray): Double {
        val n = raw.size
        System.arraycopy(raw, 0, sortScratch, 0, minOf(n, sortScratch.size))
        val m = minOf(n, sortScratch.size)
        java.util.Arrays.sort(sortScratch, 0, m)
        val floor = maxOf(sortScratch[m / 10], ABS_FLOOR)
        val threshold = floor * ACTIVE_RATIO
        var active = 0
        for (i in 0 until m) if (raw[i] >= threshold) active++
        return active.toDouble() / m
    }

    /**
     * Onset strength: the rise of the 3-bin-smoothed log energy over two bins, half-wave rectified. [raw] carries
     * [PRE_BINS] bins of history before [out]'s first.
     */
    private fun onsets(raw: FloatArray, out: DoubleArray) {
        val n = raw.size
        for (i in 0 until n) logScratch[i] = ln(raw[i] + LOG_FLOOR)
        for (o in out.indices) {
            val i = o + PRE_BINS
            val now = (logScratch[i] + logScratch[i - 1] + logScratch[i - 2]) / 3.0
            val before = (logScratch[i - 2] + logScratch[i - 3] + logScratch[i - 4]) / 3.0
            out[o] = (now - before).coerceAtLeast(0.0)
        }
        // Spread each onset over a few tens of ms (two box passes = a triangle): a person never hits every syllable
        // as exactly as the record, and sharp onsets would only line up where they happen to
        smooth(out)
        smooth(out)
    }

    private fun smooth(x: DoubleArray) {
        val n = x.size
        val half = SMOOTH_BINS / 2
        var sum = 0.0
        for (i in 0 until minOf(half, n)) sum += x[i]
        for (i in 0 until n) {
            val add = i + half
            val drop = i - half - 1
            if (add < n) sum += x[add]
            if (drop >= 0) sum -= smoothScratch[drop]
            smoothScratch[i] = x[i]
            x[i] = sum / SMOOTH_BINS
        }
    }

    private fun correlate(): Estimate? {
        val w = windowBins
        var sa = 0.0
        var saa = 0.0
        for (t in 0 until w) {
            sa += a[t]
            saa += a[t] * a[t]
        }
        val va = saa - sa * sa / w
        if (va <= 1e-12) return null
        // Sliding sums over b for every lag
        var sb = 0.0
        var sbb = 0.0
        for (t in 0 until w) {
            sb += b[t]
            sbb += b[t] * b[t]
        }
        var best = -1
        for (k in 0 until lagCount) {
            if (k > 0) {
                val out = b[k - 1]
                val inn = b[k + w - 1]
                sb += inn - out
                sbb += inn * inn - out * out
            }
            val vb = sbb - sb * sb / w
            if (vb <= 1e-12) {
                r[k] = 0.0
                continue
            }
            var sab = 0.0
            for (t in 0 until w) sab += a[t] * b[t + k]
            r[k] = (sab - sa * sb / w) / sqrt(va * vb)
            if (best < 0 || r[k] > r[best]) best = k
        }
        if (best < 0 || r[best] <= 0.0) return null
        // How far the peak stands out of the correlation at lags away from it: unrelated audio has peaks too, but only
        // a few standard deviations high
        val apart = DISTINCT_MS / binMs
        var n = 0
        var sum = 0.0
        var sumSq = 0.0
        for (k in 0 until lagCount) {
            if (abs(k - best) <= apart) continue
            n++
            sum += r[k]
            sumSq += r[k] * r[k]
        }
        val peak = r[best]
        val mean = if (n > 0) sum / n else 0.0
        val sd = if (n > 1) sqrt(((sumSq - sum * sum / n) / (n - 1)).coerceAtLeast(1e-12)) else 1.0
        val prominence = (peak - mean) / sd
        // A parabola through the peak and its neighbours puts it between bins
        val shift = if (best in 1 until lagCount - 1) {
            val l = r[best - 1]
            val c = r[best]
            val rr = r[best + 1]
            val denominator = l - 2 * c + rr
            if (denominator < 0) (0.5 * (l - rr) / denominator).coerceIn(-0.5, 0.5) else 0.0
        } else 0.0
        val lagBins = best + minLag + shift
        val strength = ((peak - NOISE_PEAK) / PEAK_RANGE).coerceIn(0.0, 1.0)
        val distinct = ((prominence - MIN_PROMINENCE) / PROMINENCE_RANGE).coerceIn(0.0, 1.0)
        val confidence = strength * distinct
        return Estimate(lagBins * binMs, confidence, peak, prominence, 0.0)
    }

    /** Forgets both envelopes (a new song or a new singer). */
    fun reset() {
        track.reset()
        mic.reset()
    }

    private companion object {
        /** History bins before the window, for the smoothed onset difference. */
        const val PRE_BINS = 4
        const val LOG_FLOOR = 1e-8
        const val ABS_FLOOR = 1e-7f
        /** About -60 dBFS of vocal-band energy. */
        const val TRACK_FLOOR = 1e-6f
        /** 12 dB above the floor. */
        val ACTIVE_RATIO = exp(ln(10.0) * 1.2).toFloat()
        const val DISTINCT_MS = 50
        /** Onset smoothing: two passes of this many bins (35 ms each). */
        const val SMOOTH_BINS = 7
        /**
         * The best correlation unrelated audio reaches over an 8 s window (smoothed onsets leave ~100 independent
         * samples: noise peaks reach ~0.25), and the span above it up to full trust.
         */
        const val NOISE_PEAK = 0.3
        const val PEAK_RANGE = 0.4

        /** Prominence (standard deviations) below which a peak is one of several alike, and the span to full trust. */
        const val MIN_PROMINENCE = 3.0
        const val PROMINENCE_RANGE = 3.0
    }
}
