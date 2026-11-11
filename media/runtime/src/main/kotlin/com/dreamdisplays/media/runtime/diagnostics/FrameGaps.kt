package com.dreamdisplays.media.runtime.diagnostics

import java.lang.management.ManagementFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.management.NotificationEmitter
import javax.management.openmbean.CompositeData

/**
 * Pipeline events of one display, written by the media threads the moment they happen (a volatile store, no
 * allocation) and read by the frame-gap detector only when it attributes a hitch.
 */
class DisplaySignals {
    /** Last time the decoded-frame queue ran dry while playing (the consumer waited for the decoder). */
    @Volatile
    @JvmField
    var decodeStarvedAtNanos: Long = 0L

    /** Last time a decoded frame arrived too late and was dropped. */
    @Volatile
    @JvmField
    var lateDropAtNanos: Long = 0L

    /** Decoded frames waiting to be shown, -1 when unknown (native pipeline). */
    @Volatile
    @JvmField
    var decodeQueue: Int = -1

    /** Last time the audio line ran dry (the audio clock, and the picture paced to it, stalled). */
    @Volatile
    @JvmField
    var audioUnderrunAtNanos: Long = 0L

    /** Audio queued in the output line at the last write, in ms; -1 when unknown. */
    @Volatile
    @JvmField
    var audioBufferedMs: Int = -1
}

/** Stop-the-world GC pauses, kept in a small ring so a hitch can tell whether one overlapped it. */
object GcPauses {
    private const val SIZE = 64
    private val endNanos = LongArray(SIZE)
    private val durationMs = LongArray(SIZE)
    private val names = arrayOfNulls<String>(SIZE)
    private var next = 0

    @Volatile
    private var installed = false

    /** Listens to the JVM's collectors (once). Concurrent cycles (ZGC / Shenandoah cycles, G1 concurrent) are skipped. */
    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            runCatching {
                for (bean in ManagementFactory.getGarbageCollectorMXBeans()) {
                    val name = bean.name
                    if (name.contains("Cycles") || name.contains("Concurrent")) continue
                    (bean as? NotificationEmitter)?.addNotificationListener({ n, _ ->
                        val data = n.userData as? CompositeData ?: return@addNotificationListener
                        val info = runCatching { data.get("gcInfo") as? CompositeData }.getOrNull()
                        val duration = (info?.get("duration") as? Long) ?: return@addNotificationListener
                        val action = (data.get("gcAction") as? String).orEmpty()
                        record(System.nanoTime(), duration, "$name${if ("major" in action) " (major)" else ""}")
                    }, null, null)
                }
            }
        }
    }

    @Synchronized
    fun record(endedAtNanos: Long, pauseMs: Long, collector: String) {
        endNanos[next] = endedAtNanos
        durationMs[next] = pauseMs
        names[next] = collector
        next = (next + 1) % SIZE
    }

    /** Total pause time overlapping [fromNanos]..[toNanos] and the longest pause in it (null when none). */
    @Synchronized
    fun overlapping(fromNanos: Long, toNanos: Long): Pair<Long, String?> {
        var total = 0L
        var worst = 0L
        var worstName: String? = null
        for (i in 0 until SIZE) {
            val name = names[i] ?: continue
            val end = endNanos[i]
            val start = end - durationMs[i] * 1_000_000L
            if (end < fromNanos || start > toNanos) continue
            val overlap = (minOf(end, toNanos) - maxOf(start, fromNanos)).coerceAtLeast(0L) / 1_000_000L
            total += overlap
            if (durationMs[i] > worst) {
                worst = durationMs[i]
                worstName = "$name ${durationMs[i]}ms"
            }
        }
        return total to worstName
    }

    @Synchronized
    internal fun clear() {
        Arrays.fill(names, null)
        next = 0
    }
}

/**
 * Finds stutters in one display's presented frames: called on the render thread every time the display is drawn, it
 * flags a gap between two presented frames of at least [factor] expected frame intervals (and [minGapNanos]) while
 * the playback clock kept running. The expected interval is the slower of the video's frame interval and the game's
 * own (smoothed) frame interval, so a game running below the video's rate does not count as stutter. A display that
 * was not drawn for a while (off screen) restarts the measurement instead of reporting a gap. No allocation.
 */
class FrameGapDetector(
    private val factor: Double = DEFAULT_FACTOR,
    private val minGapNanos: Long = DEFAULT_MIN_GAP_NANOS,
    private val unobservedNanos: Long = 1_000_000_000L,
) {
    private var lastPassNanos = 0L
    private var lastPresentNanos = 0L
    private var windowWorstPassNanos = 0L
    private var windowWorstUploadNanos = 0L
    private var windowClockStopped = false
    private var gameFrameEmaNanos = 0.0

    /** The last hitch found: gap between the two frames, its start, the expected interval, worst game frame and upload. */
    var gapNanos = 0L; private set
    var gapStartNanos = 0L; private set
    var expectedNanos = 0L; private set
    var worstGameFrameNanos = 0L; private set
    var worstUploadNanos = 0L; private set
    val gameFrameNanos: Long get() = gameFrameEmaNanos.toLong()

    /**
     * One draw of the display at [now]: [presented] when a new video frame landed in this draw, [uploadNanos] how long
     * the upload took, [clockRunning] whether playback was advancing, [videoFrameNanos] the video's frame interval at
     * the current speed (0 = unknown). Returns true when the frame just presented ended a stutter; the gap figures
     * are then in this object's properties.
     */
    fun onDraw(now: Long, presented: Boolean, uploadNanos: Long, clockRunning: Boolean, videoFrameNanos: Long): Boolean {
        val previousPass = lastPassNanos
        lastPassNanos = now
        if (previousPass != 0L) {
            val pass = now - previousPass
            if (pass >= unobservedNanos) {
                restart()
                if (presented) lastPresentNanos = now
                return false
            }
            if (pass < SMOOTHING_CAP_NANOS) {
                gameFrameEmaNanos = if (gameFrameEmaNanos == 0.0) pass.toDouble() else gameFrameEmaNanos * 0.95 + pass * 0.05
            }
            if (pass > windowWorstPassNanos) windowWorstPassNanos = pass
        }
        if (!clockRunning) windowClockStopped = true
        if (uploadNanos > windowWorstUploadNanos) windowWorstUploadNanos = uploadNanos
        if (!presented) return false
        val last = lastPresentNanos
        lastPresentNanos = now
        var hitch = false
        if (last != 0L && !windowClockStopped) {
            val gap = now - last
            val expected = maxOf(videoFrameNanos, gameFrameEmaNanos.toLong())
            if (expected > 0L && gap >= minGapNanos && gap >= factor * expected) {
                hitch = true
                gapNanos = gap
                gapStartNanos = last
                expectedNanos = expected
                worstGameFrameNanos = windowWorstPassNanos
                worstUploadNanos = windowWorstUploadNanos
            }
        }
        windowWorstPassNanos = 0L
        windowWorstUploadNanos = 0L
        windowClockStopped = !clockRunning
        return hitch
    }

    /** The display was not drawn this frame (culled): the next draw starts a fresh measurement. */
    fun hidden() {
        restart()
        lastPassNanos = 0L
    }

    /** Forget the current window (playback stopped, seeked, display hidden). */
    fun restart() {
        lastPresentNanos = 0L
        windowWorstPassNanos = 0L
        windowWorstUploadNanos = 0L
        windowClockStopped = false
    }

    companion object {
        /** Gap, in expected frame intervals, that counts as a stutter (`-Ddreamdisplays.hitchFrames`). */
        val DEFAULT_FACTOR: Double = System.getProperty("dreamdisplays.hitchFrames")?.toDoubleOrNull()?.coerceIn(1.5, 20.0) ?: 2.0
        const val DEFAULT_MIN_GAP_NANOS = 40_000_000L
        private const val SMOOTHING_CAP_NANOS = 250_000_000L
    }
}

/** What was going on during one stutter; [HitchClassifier] turns it into a cause. */
data class HitchSample(
    val gapMs: Long,
    val expectedMs: Long,
    val worstGameFrameMs: Long,
    val gameFrameMs: Long,
    val gcPauseMs: Long,
    val gcDetail: String?,
    val uploadMs: Long,
    val decodeStarved: Boolean,
    val lateDrops: Boolean,
    val decodeQueue: Int,
    val audioUnderrun: Boolean,
    val audioBufferedMs: Int,
    /** Bytes the read-ahead holds for this display, -1 when it has none (streamed directly by `FFmpeg`). */
    val netBufferedBytes: Long,
    /** A sync correction that moves the picture (seek / snap / in-place seek) in or just before the gap, else null. */
    val syncAction: String?,
    val pipeline: String,
)

/** Best-guess cause of a stutter, from the strongest signal to the weakest. */
object HitchClassifier {
    const val GC = "gc"
    const val GAME_STALL = "game-stall"
    const val SYNC_SEEK = "sync-seek"
    const val UPLOAD = "upload"
    const val NETWORK_STARVED = "network-starved"
    const val DECODE = "decode"
    const val AUDIO = "audio"
    const val UNKNOWN = "unknown"

    fun classify(s: HitchSample): String {
        val excess = (s.gapMs - s.expectedMs).coerceAtLeast(1L)
        return when {
            // A stop-the-world pause stops the render thread too, so it comes before the game stall it causes
            s.gcPauseMs >= 15 && s.gcPauseMs * 10 >= excess * 4 -> GC
            s.worstGameFrameMs * 10 >= s.gapMs * 6 -> GAME_STALL
            s.syncAction != null -> SYNC_SEEK
            s.uploadMs * 10 >= s.gapMs * 4 -> UPLOAD
            s.decodeStarved && s.netBufferedBytes in 0L until NET_LOW_BYTES -> NETWORK_STARVED
            s.decodeStarved || s.lateDrops -> DECODE
            s.audioUnderrun -> AUDIO
            else -> UNKNOWN
        }
    }

    /** The cause with its strongest detail, as in the log line: `gc(G1 Young Generation 140ms)`. */
    fun describe(cause: String, s: HitchSample): String = when (cause) {
        GC -> "gc(${s.gcDetail ?: "${s.gcPauseMs}ms"})"
        GAME_STALL -> "game-stall(frame ${s.worstGameFrameMs}ms)"
        SYNC_SEEK -> "sync-seek(${s.syncAction})"
        UPLOAD -> "upload(${s.uploadMs}ms)"
        else -> cause
    }

    /** Raw figures for the report, keyed as in the log line. */
    fun metrics(s: HitchSample): Map<String, String> = buildMap {
        put("gap_ms", s.gapMs.toString())
        put("expected_ms", s.expectedMs.toString())
        put("mc_frame_ms", s.worstGameFrameMs.toString())
        put("mc_avg_frame_ms", s.gameFrameMs.toString())
        if (s.gcPauseMs > 0) put("gc", s.gcDetail ?: "${s.gcPauseMs}ms")
        put("gc_ms", s.gcPauseMs.toString())
        put("upload_ms", s.uploadMs.toString())
        put("decode_starved", s.decodeStarved.toString())
        put("late_drops", s.lateDrops.toString())
        if (s.decodeQueue >= 0) put("decode_q", s.decodeQueue.toString())
        put("audio_underrun", s.audioUnderrun.toString())
        if (s.audioBufferedMs >= 0) put("audio_buf_ms", s.audioBufferedMs.toString())
        if (s.netBufferedBytes >= 0) put("net_buf_kib", (s.netBufferedBytes / 1024).toString())
        s.syncAction?.let { put("sync", it) }
        put("pipeline", s.pipeline)
    }

    /** The compact `[dd-hitch]` log line. */
    fun logLine(display: String, cause: String, s: HitchSample): String = buildString {
        append("[dd-hitch] ").append(display)
        append(" gap=").append(s.gapMs).append("ms")
        append(" cause=").append(describe(cause, s))
        append(" mc_frame=").append(s.worstGameFrameMs).append("ms")
        append(" upload=").append(s.uploadMs).append("ms")
        append(" decode_q=").append(if (s.decodeQueue >= 0) s.decodeQueue.toString() else "?")
        if (s.decodeStarved) append(" decode_starved")
        append(" audio_buf=").append(if (s.audioBufferedMs >= 0) "${s.audioBufferedMs}ms" else "?")
        if (s.audioUnderrun) append(" audio_underrun")
        append(" net_buf=").append(if (s.netBufferedBytes >= 0) "${s.netBufferedBytes / 1024}KiB" else "-")
        s.syncAction?.let { append(" sync=").append(it) }
        append(" pipeline=").append(s.pipeline)
    }

    /** Read-ahead below this is as good as empty. */
    const val NET_LOW_BYTES = 256L * 1024
}

/** Per-display [DisplaySignals], looked up once by each media component. */
object DisplaySignalsRegistry {
    private val byDisplay = ConcurrentHashMap<UUID, DisplaySignals>()

    fun of(displayId: UUID): DisplaySignals = byDisplay.computeIfAbsent(displayId) { DisplaySignals() }

    /** For a component that only knows its debug label (`<uuid>/<hash>`). */
    fun ofLabel(debugLabel: String): DisplaySignals? = PlaybackDiagnostics.displayIdOf(debugLabel)?.let(::of)

    fun forget(displayId: UUID) {
        byDisplay.remove(displayId)
    }
}
