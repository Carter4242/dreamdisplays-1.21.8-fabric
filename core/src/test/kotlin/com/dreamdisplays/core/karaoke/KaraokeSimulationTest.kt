package com.dreamdisplays.core.karaoke

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end timing simulation of one singer and listeners with different pings: the singer sings on their shifted
 * timeline, frames reach the server with jitter (and occasional spikes), the server re-times them the way the plugin
 * does (anchor, per-listener delay, send on the first due wake-up), and each listener's client plays a burst from its
 * first frame at its own rate, re-buffering on an underrun (Simple Voice Chat's behaviour). The error is when a frame
 * is heard minus when its track moment plays. Prints the distributions; asserts loose bounds.
 */
class KaraokeSimulationTest {
    private val ms = 1_000_000L
    private val latency = KaraokeLatency(singerOutputCaptureMs = 60, listenerBufferOutputMs = 120)

    private data class Scenario(
        val name: String,
        val singerPing: Long,
        val listenerPings: List<Long>,
        val upJitterMeanMs: Double,
        val upSpikeChance: Double,
        val alwaysOn: Boolean,
        val lockOnArrival: Boolean = false,
    )

    class Result(val ping: Long, val errors: List<Double>, val late: Int, val sent: Int, val underruns: Int) {
        private val sorted = errors.sorted()
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        val mean get() = errors.average()
        val maxAbs get() = errors.maxOf { abs(it) }
        override fun toString() = "ping %4d ms: n=%5d mean %+6.1f p5 %+6.1f p50 %+6.1f p95 %+6.1f max|e| %6.1f ms; late %5.2f%%, underruns %d"
            .format(ping, errors.size, mean, pct(0.05), pct(0.5), pct(0.95), maxAbs, 100.0 * late / sent, underruns)
    }

    private fun simulate(s: Scenario, seed: Int = 42): List<Result> {
        val random = Random(seed)
        fun expo(meanMs: Double) = (-ln(1 - random.nextDouble()) * meanMs * ms).toLong()
        val lead = KaraokeTiming.autoLeadMs(s.singerPing, s.listenerPings.max(), latency, 40, 0, 3_000)

        // Singer: frames (seq, track moment, captured-at) in bursts
        data class Frame(val seq: Long, val momentNanos: Long, val stop: Boolean)
        val frames = ArrayList<Frame>()
        var seq = 0L
        var moment = 10_000 * ms
        val songEnd = moment + 180_000 * ms
        while (moment < songEnd) {
            val length = if (s.alwaysOn) ((songEnd - moment) / (20 * ms)).toInt() else random.nextInt(100, 400)
            repeat(length) { i -> frames += Frame(seq++, moment + i * 20 * ms, false) }
            frames += Frame(seq++, moment + length * 20 * ms, true)
            moment += length * 20 * ms + random.nextLong(200, 1_500) * ms
        }

        // Events in server time; R(m) = m (track moment m plays for every listener at server time m)
        val events = PriorityQueue<Pair<Long, () -> Unit>>(compareBy { it.first })
        val anchor = BurstAnchor()
        val heardAt = s.listenerPings.associateWith { HashMap<Long, Long>() }
        val late = s.listenerPings.associateWith { intArrayOf(0) }
        val sent = s.listenerPings.associateWith { intArrayOf(0) }
        for (f in frames) {
            val captured = f.momentNanos - lead * ms + latency.singerOutputCaptureMs * ms
            var jitter = expo(s.upJitterMeanMs)
            if (random.nextDouble() < s.upSpikeChance) jitter += random.nextLong(20, 100) * ms
            val arrival = captured + s.singerPing / 2 * ms + jitter
            events += arrival to {
                val burst = anchor.onFrame(arrival, f.seq, f.stop)
                if (burst >= 0) {
                    if (s.lockOnArrival) anchor.estimate(burst, f.seq, lock = true)
                    for (ping in s.listenerPings) {
                        val shift = KaraokeTiming.emitDelayMs(lead, s.singerPing, ping, 0, latency) * ms
                        lateinit var send: (Long) -> Unit
                        send = { t ->
                            val target = anchor.estimate(burst, f.seq, lock = false)!! + shift
                            if (target - t > 300_000) {
                                events += target to { send(target) }
                            } else {
                                anchor.estimate(burst, f.seq, lock = true)
                                sent.getValue(ping)[0]++
                                if (t - target > 5 * ms) late.getValue(ping)[0]++
                                if (!f.stop) {
                                    heardAt.getValue(ping)[f.seq] = t + ping / 2 * ms + expo(3.0)
                                }
                            }
                        }
                        val provisional = anchor.estimate(burst, f.seq, lock = false)!! + shift - 30 * ms
                        val at = maxOf(provisional, arrival)
                        events += at to { send(at) }
                    }
                }
            }
        }
        while (events.isNotEmpty()) events.poll().second()

        // Listener clients: a burst plays from its first frame (+ buffer and output), then at the audio rate
        return s.listenerPings.map { ping ->
            val arrivals = heardAt.getValue(ping)
            val errors = ArrayList<Double>()
            var underruns = 0
            var slot = Long.MIN_VALUE
            var previousSeq = Long.MIN_VALUE
            for (f in frames) {
                if (f.stop) {
                    slot = Long.MIN_VALUE
                    continue
                }
                val arrived = arrivals[f.seq] ?: continue
                val play = if (slot == Long.MIN_VALUE || previousSeq != f.seq - 1) {
                    arrived + latency.listenerBufferOutputMs * ms
                } else if (arrived + 20 * ms > slot) {
                    underruns++
                    arrived + latency.listenerBufferOutputMs * ms
                } else slot
                errors += (play - f.momentNanos) / ms.toDouble()
                slot = play + 20 * ms
                previousSeq = f.seq
            }
            Result(ping, errors, late.getValue(ping)[0], sent.getValue(ping)[0], underruns)
        }
    }

    private fun run(s: Scenario): List<Result> {
        val results = simulate(s)
        println("== ${s.name} (singer ping ${s.singerPing} ms, lead ${KaraokeTiming.autoLeadMs(s.singerPing, s.listenerPings.max(), latency, 40, 0, 3_000)} ms)")
        results.forEach { println("   $it") }
        return results
    }

    private val pings = listOf(20L, 60L, 120L, 200L)

    @Test
    fun voiceActivationBurstsLandOnTheBeat() {
        val results = run(Scenario("voice activation, 4 ms jitter, 2% spikes", 60, pings, 4.0, 0.02, alwaysOn = false))
        for (r in results) {
            assertTrue(abs(r.pct(0.5)) < 8.0, "median $r")
            assertTrue(r.pct(0.95) < 40.0, "p95 $r")
        }
    }

    @Test
    fun alwaysOnSingingLandsOnTheBeat() {
        // One burst: the whole song keeps the offset its first frame got (anchor error plus that frame's jitter)
        val results = run(Scenario("always on, 4 ms jitter, 2% spikes", 60, pings, 4.0, 0.02, alwaysOn = true))
        for (r in results) assertTrue(abs(r.pct(0.5)) < 25.0 && r.maxAbs < 40.0, "median $r")
    }

    @Test
    fun printsHeavierJitterAndTheFirstPacketBaseline() {
        run(Scenario("voice activation, 10 ms jitter, 5% spikes", 120, pings, 10.0, 0.05, alwaysOn = false))
        run(Scenario("baseline: anchored on each burst's first packet", 60, pings, 4.0, 0.02, alwaysOn = false, lockOnArrival = true))
    }
}
