package com.dreamdisplays.media.player.pipeline

import com.dreamdisplays.api.media.audio.AudioDspStage
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.media.player.util.MediaBufferEffects
import com.dreamdisplays.media.player.util.MediaUtil
import com.dreamdisplays.media.player.util.daemon
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.*

/**
 * Manages PCM pipeline for `FFmpeg` audio process (owns audio master clock).
 */
internal class AudioSink(private val debugLabel: String) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        /** The PCM sample rate used by every session: 44.1 kHz, stereo, signed 16-bit little-endian. */
        const val SAMPLE_RATE = 44100

        /** Stereo 16-bit PCM: 4 bytes per frame. One second is SAMPLE_RATE * BYTES_PER_FRAME bytes. */
        const val BYTES_PER_FRAME = 4

        private const val CHUNK_BYTES = SAMPLE_RATE * 2 * 2 / 20
        private const val LINE_BUFFER_BYTES = CHUNK_BYTES * 8
        private const val MIN_PACE_BYTES = CHUNK_BYTES
        private const val MAX_PACE_BYTES = CHUNK_BYTES * 6
        private const val PACE_STEP_BYTES = CHUNK_BYTES
        private const val PACE_RECOVER_CHUNKS = 400
        private const val OPEN_RETRIES = 3
        private const val RETRY_DELAY_MS = 200L
        private const val CATCHUP_MIN_NANOS = 40_000_000L
        private const val CATCHUP_MAX_PASSES = 4
        private const val MAX_RESYNC_NANOS = 5_000_000_000L

        /** How long to wait for FFmpeg to exit after its PCM ended, to read its exit code. */
        private const val EXIT_WAIT_MS = 500L

        /** Start of the fault reason of a line that ended because the game's sound device changed (not a failure). */
        const val DEVICE_CHANGED_REASON = "sound device changed"

        /** How often a playing line checks that the game still uses the output device it was opened on. */
        private const val DEVICE_CHECK_NANOS = 1_000_000_000L

        /** At most one audio-fault WARN (with its stack trace) per this interval across all displays. */
        private const val FAULT_WARN_INTERVAL_NANOS = 30_000_000_000L
        private val lastFaultWarnNanos = AtomicLong(0L)

        /** Live edge: PCM read per write, 10 ms, so a chunk never waits on the next one to fill. */
        private const val LIVE_CHUNK_BYTES = SAMPLE_RATE * BYTES_PER_FRAME / 100

        /** Live edge: sound queued on the line, as in [paceTargetBytes]; starts at 40 ms, may grow to 120 ms. */
        private const val LIVE_PACE_START_BYTES = LIVE_CHUNK_BYTES * 4
        private const val LIVE_MIN_PACE_BYTES = LIVE_CHUNK_BYTES * 2
        private const val LIVE_MAX_PACE_BYTES = LIVE_CHUNK_BYTES * 12

        /**
         * Live edge: PCM waiting in the pipe beyond this (100 ms) is a backlog (a hitch, a slow line start, a burst
         * from the relay) and is skipped, down to [LIVE_KEEP_BYTES], so the sound never drifts behind the picture.
         */
        private const val LIVE_MAX_BACKLOG_BYTES = LIVE_CHUNK_BYTES * 10
        private const val LIVE_KEEP_BYTES = LIVE_CHUNK_BYTES * 2

        /** Furthest a sync rate request may move playback from real time (5 %). */
        private const val MAX_RATE_OFFSET = 0.05

        /** Largest rate change per PCM chunk (50 ms), so a new rate is reached smoothly: 1 % takes 1 s. */
        private const val RATE_STEP_PER_CHUNK = 0.0005

        /** True once the Java Sound outputs were listed (see [logMixersOnce]); listed once per game session. */
        @Volatile
        private var mixersLogged = false

        /** Device + format that last had to be used instead of the content format; tried first next time. */
        @Volatile
        private var lastFallback: Pair<Mixer.Info?, PcmLineFormat>? = null

        /** Converts a count of stereo 16-bit PCM frames into nanos of content. */
        fun framesToNanos(frames: Long): Long = frames * 1_000_000_000L / SAMPLE_RATE

        /** Converts a byte count of stereo 16-bit PCM into nanos of content. */
        fun bytesToNanos(bytes: Long): Long = framesToNanos(bytes / BYTES_PER_FRAME)

        /**
         * Content nanos carried by [pcmNanos] of PCM from a process time-stretched to [tempo] x (each PCM second
         * holds [tempo] seconds of the video).
         */
        fun pcmToContentNanos(pcmNanos: Long, tempo: Double): Long =
            if (tempo == 1.0) pcmNanos else (pcmNanos * tempo).toLong()

        /** PCM nanos that carry [contentNanos] of content at [tempo] x. */
        fun contentToPcmNanos(contentNanos: Long, tempo: Double): Long =
            if (tempo == 1.0) contentNanos else (contentNanos / tempo).toLong()

        /**
         * Why a live audio half ended on its own (not stopped): null = nothing to report; [EndKind.FAULT] = it broke
         * (an exception, or FFmpeg exited non-zero), restart it in place; [EndKind.ENDED] = the PCM stream just ended.
         */
        /**
         * How long a line holding [unplayedBytes] at [bytesPerSecond] may take to play them out at a natural end: their
         * duration plus a little slack, never more than [MAX_TAIL_PLAYOUT_NANOS]; 0 when there is nothing to play.
         */
        internal fun tailPlayOutBudgetNanos(unplayedBytes: Int, bytesPerSecond: Int): Long {
            if (unplayedBytes <= 0 || bytesPerSecond <= 0) return 0L
            val playNanos = unplayedBytes.toLong() * 1_000_000_000L / bytesPerSecond
            return (playNanos + TAIL_PLAYOUT_SLACK_NANOS).coerceAtMost(MAX_TAIL_PLAYOUT_NANOS)
        }

        private const val TAIL_PLAYOUT_POLL_MS = 5L
        private const val TAIL_PLAYOUT_SLACK_NANOS = 100_000_000L
        private const val MAX_TAIL_PLAYOUT_NANOS = 1_000_000_000L

        internal fun endKind(failure: Throwable?, pumped: Boolean, exitCode: Int?): EndKind? = when {
            failure != null -> EndKind.FAULT
            !pumped -> null
            exitCode != null && exitCode != 0 -> EndKind.FAULT
            else -> EndKind.ENDED
        }
    }

    internal enum class EndKind { FAULT, ENDED }

    /**
     * The game switched its sound output away from the device this line was opened for; ends the pump as a fault, so
     * the half is restarted in place and opens on the new device.
     */
    internal class OutputDeviceChanged(from: String?, to: String?) :
        IOException("the game's sound device changed (\"${from ?: "default"}\" -> \"${to ?: "default"}\")")

    /**
     * Sample of audio master clock (atomic read of position, session, and meaning).
     */
    class ClockSample(
        @JvmField val nanos: Long,
        @JvmField val epoch: Int,
        @JvmField val originKnown: Boolean,
    ) {
        companion object {
            /** No line clock available. */
            val NONE = ClockSample(-1L, 0, false)
        }
    }

    private class LineSession(
        @JvmField val epoch: Int,
        @JvmField val originKnown: Boolean,
        @Volatile @JvmField var contentStartNanos: Long,
        /** Playback speed the process was started with (`atempo`); PCM time times this is content time. */
        @JvmField val tempo: Double = 1.0,
        /** A live relay stream: small chunks, a short line queue, and backlog skipping (see [AudioSink.liveEdge]). */
        @JvmField val liveEdge: Boolean = false,
    ) {
        /** The game's output device when this session's line was opened (null = system default). */
        @Volatile
        @JvmField
        var device: String? = null

        @Volatile
        @JvmField
        var line: SourceDataLine? = null

        /** True once live PCM has actually been read from the process (see [AudioSink.sampleClock]). */
        @Volatile
        @JvmField
        var pcmFlowing = false

        /**
         * Converts content into [line]'s format and maps its output frames back to content frames (identity for the
         * content format at rate 1). Set before the line is published.
         */
        @Volatile
        @JvmField
        var output: PcmLineOutput? = null
    }

    /** An opened output line and the converter that feeds it. */
    private class OpenedLine(val line: SourceDataLine, val output: PcmLineOutput)

    /**
     * Content catch-up spec for session starting during playback (discard leading PCM). [tempo] is the speed the
     * process plays at, so the content a skipped PCM span stood for is known.
     */
    class CatchUp(val contentStartNanos: Long, val playbackClock: () -> Long, val tempo: Double = 1.0)

    /**
     * Sessions started from now on play a live relay stream: sound goes out as it arrives, with only a few tens of
     * ms queued on the line, and any backlog in the pipe is skipped instead of played late.
     */
    @Volatile
    var liveEdge: Boolean = false

    /** Current volume multiplier applied to each audio chunk. */
    @Volatile
    var currentVolume: Double = 1.0

    /** [System.nanoTime] when PCM last reached an output line (0 before any), for the silent-audio self-heal. */
    @Volatile
    var lastPcmOutNanos: Long = 0L
        private set

    @Volatile
    private var dspStage: AudioDspStage? = null

    /** Gets every chunk of PCM written to the line while set (karaoke: the singer's own track); see [PlayedPcmTap]. */
    @Volatile
    var pcmTap: PlayedPcmTap? = null

    /** The open line's output latency (see [AudioOutputLatency]), or null while no line plays. Any thread. */
    fun outputLatency(): AudioOutputLatency? {
        val s = current ?: return null
        val ln = s.line ?: return null
        val format = s.output?.format ?: return null
        return runCatching {
            val queuedBytes = (ln.bufferSize - ln.available()).coerceAtLeast(0).toLong()
            val queued = queuedBytes * 1_000_000_000L / format.bytesPerSecond.coerceAtLeast(1)
            val device = (ln as? LatencyReportingLine)?.deviceLatencyNanos() ?: -1L
            AudioOutputLatency(queued, device, if (ln is LatencyReportingLine) "openal" else "javasound")
        }.getOrNull()
    }

    /** Hands the chunk just written to [pcmTap] with when its first frame leaves the line. */
    private fun tapWritten(tap: PlayedPcmTap, chunk: ByteArray, n: Int, ln: SourceDataLine, output: PcmLineOutput, outBytes: Int) {
        runCatching {
            val firstFrame = output.outFramesWritten - outBytes / output.format.bytesPerFrame
            val position = ln.longFramePosition
            tap.onPcm(chunk, n, PcmTapTiming.playAtNanos(System.nanoTime(), firstFrame, position, output.format.sampleRate))
        }
    }

    /** Installs (or clears) the acoustics DSP stage for this session; resets its state immediately. */
    fun setDspStage(stage: AudioDspStage?) {
        dspStage = stage
        stage?.reset()
    }

    private var paceTargetBytes = MIN_PACE_BYTES
    private var paceCleanChunks = 0
    private var paceStreaming = false
    private val sessionLock = Any()

    @Volatile
    private var current: LineSession? = null
    private var epochCounter = 0

    /**
     * Samples the audio master clock. See [ClockSample]; [ClockSample.NONE] while no line clock is
     * available, in which case callers fall back to the wall clock.
     */
    fun sampleClock(): ClockSample {
        val s = current ?: return ClockSample.NONE
        val ln = s.line ?: return ClockSample.NONE
        if (!s.pcmFlowing) return ClockSample.NONE
        // Content frames, not output frames: they run apart while the sync rate is not 1 or the device runs at
        // another sample rate
        val position = ln.longFramePosition
        val contentFrames = s.output?.frameMap?.contentAt(position) ?: position
        return ClockSample(
            s.contentStartNanos + pcmToContentNanos(framesToNanos(contentFrames), s.tempo), s.epoch, s.originKnown,
        )
    }

    @Volatile
    private var parked: AtomicBoolean? = null

    /** Installs the session park flag (see [parked]); set once by the owning session manager. */
    fun setParkFlag(flag: AtomicBoolean?) {
        parked = flag
    }

    private val pendingResyncNanos = AtomicLong(0)

    @Volatile
    private var lastLineChoice: String? = null

    /** Hitch attribution for this display (line underruns, buffered audio). */
    private val signals = com.dreamdisplays.media.runtime.diagnostics.DisplaySignalsRegistry.ofLabel(debugLabel)

    /**
     * Requested playback rate as content frames per output frame (1.01 plays 1 % fast), used to keep synced
     * playback on the server timeline. Reached gradually; the audio clock keeps reporting content time.
     */
    @Volatile
    var playbackRate: Double = 1.0
        set(value) {
            field = if (value.isFinite()) value.coerceIn(1.0 - MAX_RATE_OFFSET, 1.0 + MAX_RATE_OFFSET) else 1.0
        }

    /** Asks pump to drop next nanos of audio to restore lip sync after line stall. */
    fun requestResync(nanos: Long) {
        if (nanos <= 0L) return
        pendingResyncNanos.accumulateAndGet(nanos.coerceAtMost(MAX_RESYNC_NANOS), ::maxOf)
    }

    /** Skips PCM owed by [requestResync]; true when anything was skipped (a discontinuity in the content). */
    private fun applyPendingResync(
        session: LineSession, input: InputStream, terminated: AtomicBoolean, stopFlag: AtomicBoolean,
    ): Boolean {
        val target = pendingResyncNanos.getAndSet(0L)
        if (target <= 0L || !owns(session)) return false
        val scratch = ByteArray(CHUNK_BYTES)
        var toSkip = contentToPcmNanos(target, session.tempo) * SAMPLE_RATE / 1_000_000_000L * BYTES_PER_FRAME
        var skipped = 0L
        while (toSkip > 0 && !terminated.get() && !stopFlag.get()) {
            val n = MediaUtil.readFull(input, scratch, minOf(CHUNK_BYTES.toLong(), toSkip).toInt())
            if (n <= 0) break // EOF mid-skip: the pump loop reports it on its next read
            skipped += n
            toSkip -= n
        }
        if (skipped <= 0L) return false
        val skippedContent = pcmToContentNanos(bytesToNanos(skipped), session.tempo)
        session.contentStartNanos += skippedContent
        logger.debug("$debugLabel [audio] re-synced by skipping ${skippedContent / 1_000_000} ms of PCM.")
        return true
    }

    private fun beginSession(contentStartNanos: Long, originKnown: Boolean, tempo: Double): LineSession {
        val session = synchronized(sessionLock) {
            LineSession(++epochCounter, originKnown, contentStartNanos, tempo, liveEdge).also { current = it }
        }
        pendingResyncNanos.set(0L) // A skip owed by the previous line means nothing to a fresh one
        dspStage?.reset()
        return session
    }

    private fun publishLine(session: LineSession, ln: SourceDataLine): Boolean {
        synchronized(sessionLock) {
            if (current !== session) return false
            session.line = ln
            return true
        }
    }

    private fun owns(session: LineSession): Boolean = current === session

    private fun retireSession(session: LineSession, ln: SourceDataLine?) {
        synchronized(sessionLock) {
            if (session.line === ln) session.line = null
            if (current === session) current = null
        }
    }

    /**
     * Starts the audio reading / writing loop. session's master clock (see [ClockSample]). (live HLS), so
     * [contentStartNanos] is the anchor for the session's timeline.
     */
    fun start(
        proc: Process,
        terminated: AtomicBoolean,
        stopFlag: AtomicBoolean,
        contentStartNanos: Long,
        originKnown: Boolean,
        startGate: CountDownLatch? = null,
        onUnexpectedEnd: (stderr: String, hadPcm: Boolean) -> Unit = { _, _ -> },
        catchUp: CatchUp? = null,
        tempo: Double = 1.0,
        onFault: ((reason: String, stderr: String) -> Unit)? = null,
    ): Thread {
        val session = beginSession(contentStartNanos, originKnown, tempo)
        val stderrBuf = drainStderr(proc, stopFlag)
        return daemon(
            { run(session, proc, terminated, stopFlag, startGate, stderrBuf, onUnexpectedEnd, catchUp, onFault) },
            "MediaPlayer-audio",
        ).also { it.start() }
    }

    /**
     * Prewarms a replacement line for a seamless in-place audio-track switch and, once it is primed, flips it in
     */
    fun startSwitch(
        proc: Process,
        terminated: AtomicBoolean,
        stopFlag: AtomicBoolean,
        contentStartNanos: Long,
        originKnown: Boolean,
        catchUp: CatchUp?,
        shouldPromote: () -> Boolean = { true },
        onPromoted: () -> Unit,
        onAborted: () -> Unit = {},
        onUnexpectedEnd: (stderr: String, hadPcm: Boolean) -> Unit = { _, _ -> },
        tempo: Double = 1.0,
        onFault: ((reason: String, stderr: String) -> Unit)? = null,
    ): Thread {
        val stderrBuf = drainStderr(proc, stopFlag)
        return daemon(
            {
                runSwitch(
                    proc,
                    terminated,
                    stopFlag,
                    contentStartNanos,
                    originKnown,
                    catchUp,
                    shouldPromote,
                    onPromoted,
                    onAborted,
                    stderrBuf,
                    onUnexpectedEnd,
                    tempo,
                    onFault,
                )
            },
            "MediaPlayer-audio-switch-line",
        ).also { it.start() }
    }

    private fun runSwitch(
        proc: Process, terminated: AtomicBoolean, stopFlag: AtomicBoolean, contentStartNanos: Long,
        originKnown: Boolean, catchUp: CatchUp?, shouldPromote: () -> Boolean, onPromoted: () -> Unit,
        onAborted: () -> Unit, stderrBuf: StringBuilder, onUnexpectedEnd: (stderr: String, hadPcm: Boolean) -> Unit, tempo: Double,
        onFault: ((String, String) -> Unit)?,
    ) {
        var newLine: SourceDataLine? = null
        var session: LineSession? = null
        var promoted = false
        var pumped = false
        var naturalEnd = false
        var failure: Throwable? = null

        fun isInterrupted() = terminated.get() || stopFlag.get()
        runCatching {
            proc.inputStream.use { input ->
                val device = AudioOutputHooks.gameDeviceName()
                val opened = openLine() ?: run {
                    logger.warn("$debugLabel [audio] switch line failed to open.")
                    return@use
                }
                newLine = opened.line

                if (isInterrupted()) return@use
                val skippedNanos = catchUp?.let { skipCatchUp(input, it, terminated, stopFlag) } ?: 0L

                val primedBytes = if (isInterrupted()) 0 else primeSwitchLine(input, opened, terminated, stopFlag)
                if (isInterrupted() || primedBytes <= 0) return@use

                // The flip publishes the new session and its line together, so no clock reader can ever
                // see the new line paired with the old session's content origin (or vice versa). It is decided
                // under the session lock every seek / restart / stop takes to replace the session, so a switch that
                // lost that race goes no further and never closes the line that replaced it.
                val (old, s) = synchronized(sessionLock) {
                    if (isInterrupted() || !shouldPromote()) return@use
                    val oldLine = current?.line
                    oldLine?.apply {
                        runCatching { stop() }
                        runCatching { flush() }
                    }
                    val flipped = beginSession(contentStartNanos + skippedNanos, originKnown, tempo)
                    flipped.output = opened.output
                    flipped.device = device
                    publishLine(flipped, opened.line)
                    oldLine to flipped
                }
                session = s
                opened.line.start()
                // Only now may the clock be read: the line is running and its primed buffer is the
                // content at contentStartNanos, so frame position 0 really is that position.
                s.pcmFlowing = true
                old?.let { runCatching { it.close() } }

                promoted = true
                logger.debug("$debugLabel [audio] switch line promoted — new track playing.")
                onPromoted()

                naturalEnd = pumpLive(s, input, opened.line, terminated, stopFlag)
                pumped = true
            }
        }.onFailure { e ->
            if (isInterrupted()) return@onFailure
            // Before the flip the old track still plays and onAborted keeps it; after it this is the live half
            if (promoted) failure = e
            else if (e !is IOException || MediaPlayer.DEBUG) {
                logger.warn("$debugLabel [audio] switch line failed before going live; keeping the current track.", e)
            }
        }

        newLine?.let { ln ->
            if (naturalEnd && !isInterrupted()) playOutTail(ln, session?.output, terminated, stopFlag)
            session?.let { retireSession(it, ln) }
            runCatching { ln.flush() }
            runCatching { ln.stop() }
            runCatching { ln.close() }
        }

        if (!promoted) onAborted()

        if (promoted && !isInterrupted()) reportEnd(proc, failure, pumped, session?.pcmFlowing == true, stderrBuf, onUnexpectedEnd, onFault)
    }

    /**
     * Reports how a live half ended on its own: a fault (exception, FFmpeg exiting non-zero mid-stream) goes to
     * [onFault] so the half is restarted in place right away, with one rate-limited WARN; a plain end of PCM to
     * [onUnexpectedEnd] as before, saying whether this half ever got PCM ([hadPcm]: false when the process ended
     * cleanly without decoding anything, e.g. opened past the end of its track).
     */
    private fun reportEnd(
        proc: Process, failure: Throwable?, pumped: Boolean, hadPcm: Boolean, stderrBuf: StringBuilder,
        onUnexpectedEnd: (stderr: String, hadPcm: Boolean) -> Unit, onFault: ((String, String) -> Unit)?,
    ) {
        val exitCode = if (failure == null && pumped) exitCodeAfterEof(proc) else null
        val stderr = synchronized(stderrBuf) { stderrBuf.toString() }
        when (endKind(failure, pumped, exitCode)) {
            null -> {}
            EndKind.ENDED -> onUnexpectedEnd(stderr, hadPcm)
            EndKind.FAULT -> {
                val reason = when (failure) {
                    is OutputDeviceChanged -> "$DEVICE_CHANGED_REASON (${failure.message})"
                    null -> "FFmpeg audio process exited with code $exitCode"
                    else -> "audio pipeline failed ($failure)"
                }
                if (onFault == null) {
                    if (failure != null) logger.warn("$debugLabel [audio] $reason.", failure)
                    if (pumped) onUnexpectedEnd(stderr, hadPcm)
                    return
                }
                if (failure is OutputDeviceChanged) logger.info("$debugLabel [audio] ${failure.message}; reopening the line there.")
                else warnFault("$reason; restarting the audio half in place", failure)
                onFault(reason, stderr)
            }
        }
    }

    private fun exitCodeAfterEof(proc: Process): Int? = try {
        if (proc.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)) proc.exitValue() else null
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt(); null
    }

    /** WARNs [message] with [cause]'s stack trace at most once per [FAULT_WARN_INTERVAL_NANOS]; DEBUG otherwise. */
    private fun warnFault(message: String, cause: Throwable?) {
        val now = System.nanoTime()
        val last = lastFaultWarnNanos.get()
        if ((last == 0L || now - last >= FAULT_WARN_INTERVAL_NANOS) && lastFaultWarnNanos.compareAndSet(last, now)) {
            if (cause != null) logger.warn("$debugLabel [audio] $message.", cause) else logger.warn("$debugLabel [audio] $message.")
        } else {
            logger.debug("$debugLabel [audio] {}.", message, cause)
        }
    }

    /** Pre-fills the line at rate 1; returns the content bytes written (0 when nothing could be read). */
    private fun primeSwitchLine(
        input: InputStream, opened: OpenedLine, terminated: AtomicBoolean, stopFlag: AtomicBoolean,
    ): Int {
        val chunk = ByteArray(CHUNK_BYTES)
        val primeTarget = paceTargetBytes.coerceIn(CHUNK_BYTES * 3, LINE_BUFFER_BYTES - CHUNK_BYTES)
        var buffered = 0
        while (buffered < primeTarget && !terminated.get() && !stopFlag.get()) {
            val n = MediaUtil.readFull(input, chunk, CHUNK_BYTES)
            if (n <= 0) return buffered
            MediaBufferEffects.applyVolumeS16LE(chunk, n, currentVolume)
            val out = opened.output.convert(chunk, n, 1.0)
            writeFully(opened.line, opened.output.output, out, terminated, stopFlag)
            buffered += n
        }
        return buffered
    }

    /** Flushes and closes the audio line immediately, retiring the clock. Safe to call from any thread. */
    fun stop() {
        val s = synchronized(sessionLock) { current.also { current = null } } ?: return
        val ln = s.line ?: return
        s.line = null
        runCatching { ln.flush() }
        runCatching { ln.stop() }
        runCatching { ln.close() }
    }

    /** One line of sink state for a silent-audio report: line running / queued bytes, last write, gain, rate. */
    fun describe(): String {
        val s = current
        val ln = s?.line
        val now = System.nanoTime()
        val lastOut = lastPcmOutNanos.let { if (it == 0L) "never" else "${(now - it) / 1_000_000} ms ago" }
        val line = if (ln == null) "none" else runCatching {
            "open=${ln.isOpen} running=${ln.isRunning} active=${ln.isActive} " +
                    "queued=${ln.bufferSize - ln.available()}/${ln.bufferSize} frame=${ln.longFramePosition}"
        }.getOrElse { "error(${it.message})" }
        return "session=${s?.epoch ?: "none"} pcmFlowing=${s?.pcmFlowing} line[$line] lastPcmOut=$lastOut " +
                "gain=$currentVolume rate=$playbackRate parkFlag=${parked?.get()} pendingResync=${pendingResyncNanos.get()}"
    }

    /** Pauses playback for a warm park without closing or flushing the queued PCM. */
    fun pauseForPark() {
        current?.line?.let { runCatching { it.stop() } }
    }

    /** Resumes a line paused by [pauseForPark]. */
    fun resumeFromPark() {
        current?.line?.let { runCatching { it.start() } }
    }

    private fun run(
        session: LineSession, proc: Process, terminated: AtomicBoolean, stopFlag: AtomicBoolean,
        startGate: CountDownLatch?, stderrBuf: StringBuilder, onUnexpectedEnd: (stderr: String, hadPcm: Boolean) -> Unit,
        catchUp: CatchUp? = null, onFault: ((String, String) -> Unit)? = null,
    ) {
        var ln: SourceDataLine? = null
        var pumped = false
        var naturalEnd = false
        var failure: Throwable? = null
        runCatching {
            proc.inputStream.use { input ->
                val device = AudioOutputHooks.gameDeviceName()
                val opened = openLine() ?: run {
                    logger.warn("$debugLabel [audio] line failed to open.")
                    return@runCatching
                }
                ln = opened.line
                session.output = opened.output
                session.device = device
                if (terminated.get() || stopFlag.get()) return@runCatching
                logger.debug("$debugLabel [audio] line open, waiting for start gate...")
                if (!awaitStartGate(startGate, terminated, stopFlag)) {
                    logger.debug("$debugLabel [audio] aborted before start gate opened (terminated / stopped).")
                    return@runCatching
                }
                catchUp?.let { session.contentStartNanos += skipCatchUp(input, it, terminated, stopFlag) }
                if (terminated.get() || stopFlag.get()) return@runCatching
                if (!publishLine(session, ln)) {
                    logger.debug("$debugLabel [audio] session superseded before its line started; abandoning it.")
                    return@runCatching
                }
                ln.start()
                logger.debug("$debugLabel [audio] start gate passed, line started — audio is now playing.")
                naturalEnd = pumpLive(session, input, ln, terminated, stopFlag)
                pumped = true
            }
        }.onFailure { e ->
            // Left alone this silences the session while its video plays on: reported below as a fault
            if (!terminated.get() && !stopFlag.get()) failure = e
        }

        ln?.let {
            if (naturalEnd) playOutTail(it, session.output, terminated, stopFlag)
            retireSession(session, it)
            runCatching { it.flush() }
            runCatching { it.stop() }
            runCatching { it.close() }
        }

        if (!terminated.get() && !stopFlag.get()) {
            reportEnd(proc, failure, pumped, session.pcmFlowing, stderrBuf, onUnexpectedEnd, onFault)
        }
    }

    private fun skipCatchUp(
        input: InputStream, catchUp: CatchUp, terminated: AtomicBoolean, stopFlag: AtomicBoolean,
    ): Long {
        val scratch = ByteArray(CHUNK_BYTES)
        var skippedBytes = 0L
        // Content skipped so far: PCM played at catchUp.tempo carries that many times its own length of video
        fun skippedContent() = pcmToContentNanos(bytesToNanos(skippedBytes), catchUp.tempo)
        repeat(CATCHUP_MAX_PASSES) {
            val contentNow = catchUp.contentStartNanos + skippedContent()
            val gapNanos = catchUp.playbackClock() - contentNow
            if (contentToPcmNanos(gapNanos, catchUp.tempo) < CATCHUP_MIN_NANOS) {
                if (skippedBytes > 0L) {
                    logger.debug(
                        "$debugLabel [audio] catch-up skipped ${skippedContent() / 1_000_000} ms " +
                                "of PCM to join in sync."
                    )
                }
                return skippedContent()
            }
            var toSkip = contentToPcmNanos(gapNanos, catchUp.tempo) * SAMPLE_RATE / 1_000_000_000L * BYTES_PER_FRAME
            while (toSkip > 0 && !terminated.get() && !stopFlag.get()) {
                val n = MediaUtil.readFull(input, scratch, minOf(CHUNK_BYTES.toLong(), toSkip).toInt())
                if (n <= 0) return skippedContent() // EOF mid-skip: pump loop will report it
                skippedBytes += n
                toSkip -= n
            }
            if (terminated.get() || stopFlag.get()) return skippedContent()
        }
        return skippedContent()
    }

    /** Pumps PCM to [ln] until it ends or is stopped; true when the PCM stream itself ended (its natural end). */
    private fun pumpLive(
        session: LineSession, input: InputStream, ln: SourceDataLine,
        terminated: AtomicBoolean, stopFlag: AtomicBoolean,
    ): Boolean {
        val live = session.liveEdge
        val chunkBytes = if (live) LIVE_CHUNK_BYTES else CHUNK_BYTES
        val chunk = ByteArray(chunkBytes)
        val output = session.output ?: return false
        var rate = 1.0
        var firstChunk = true
        var totalBytes = 0L
        var deviceCheckedNanos = System.nanoTime()
        // Fresh line for this session: don't let its initially-empty buffer read as an underrun on the
        // first paced write. The learned target itself persists across sessions.
        paceStreaming = false
        paceCleanChunks = 0
        // A live session's short target would starve the next ordinary one: bring it back into the normal range
        paceTargetBytes = if (live) LIVE_PACE_START_BYTES else paceTargetBytes.coerceIn(MIN_PACE_BYTES, MAX_PACE_BYTES)
        while (!terminated.get() && !stopFlag.get()) {
            if (parkIfRequested(ln, terminated, stopFlag)) break
            val now = System.nanoTime()
            if (now - deviceCheckedNanos >= DEVICE_CHECK_NANOS) {
                deviceCheckedNanos = now
                val wanted = AudioOutputHooks.gameDeviceName()
                if (wanted != session.device) throw OutputDeviceChanged(session.device, wanted)
            }
            if (!firstChunk && applyPendingResync(session, input, terminated, stopFlag)) output.discontinuity()
            if (live && skipLiveBacklog(input, chunk, terminated, stopFlag) && !firstChunk) output.discontinuity()
            val n = MediaUtil.readFull(input, chunk, chunkBytes)
            if (n <= 0) {
                // A deliberate teardown destroys the process mid-read; that EOF is expected and not
                // worth a warning (during a session-restart loop it reads as a phantom audio crash).
                if (terminated.get() || stopFlag.get()) break
                if (firstChunk) {
                    logger.warn("$debugLabel [audio] no PCM data from ffmpeg (EOF on first read).")
                } else {
                    val seconds = totalBytes / (SAMPLE_RATE * BYTES_PER_FRAME).toDouble()
                    logger.warn("$debugLabel [audio] PCM stream ended after ${"%.1f".format(seconds)} s.")
                }
                return !firstChunk
            }
            totalBytes += n
            if (firstChunk) {
                logger.debug("$debugLabel [audio] first PCM chunk received ($n bytes)."); firstChunk = false
                session.pcmFlowing = true
            }
            val dsp = if (owns(session)) dspStage else null
            dsp?.process(chunk, n, currentVolume) ?: MediaBufferEffects.applyVolumeS16LE(chunk, n, currentVolume)
            rate = stepRate(rate, playbackRate)
            // For the content format at rate 1 this is a plain copy; otherwise the chunk is resampled to the line's
            // rate (times the sync rate) and repacked to its sample format
            val outBytes = output.convert(chunk, n, rate)
            if (outBytes > 0) paceLiveWrite(ln, output.output, outBytes, output.format, terminated, stopFlag, live)
            lastPcmOutNanos = System.nanoTime()
            pcmTap?.let { if (outBytes > 0) tapWritten(it, chunk, n, ln, output, outBytes) }
        }
        return false
    }

    /**
     * Live edge: drops PCM piled up in the pipe past [LIVE_MAX_BACKLOG_BYTES], keeping the newest [LIVE_KEEP_BYTES].
     * Returns true when anything was dropped. The line clock is not moved: nothing is paced to it at the live edge.
     */
    private fun skipLiveBacklog(
        input: InputStream, scratch: ByteArray, terminated: AtomicBoolean, stopFlag: AtomicBoolean,
    ): Boolean {
        val pending = runCatching { input.available() }.getOrDefault(0)
        if (pending <= LIVE_MAX_BACKLOG_BYTES) return false
        // Whole stereo frames only, or every later sample would be read from the wrong byte
        var toSkip = ((pending - LIVE_KEEP_BYTES) / BYTES_PER_FRAME * BYTES_PER_FRAME).toLong()
        var skipped = 0L
        while (toSkip > 0 && !terminated.get() && !stopFlag.get()) {
            val n = MediaUtil.readFull(input, scratch, minOf(scratch.size.toLong(), toSkip).toInt())
            if (n <= 0) break
            skipped += n
            toSkip -= n
        }
        if (skipped > 0 && MediaPlayer.DEBUG) {
            logger.debug("$debugLabel [audio] live edge: skipped ${bytesToNanos(skipped) / 1_000_000} ms of backlog.")
        }
        return skipped > 0
    }

    /** Moves [current] one chunk's worth toward [target], landing exactly on it (and on 1.0) when close. */
    private fun stepRate(current: Double, target: Double): Double {
        val delta = target - current
        return if (kotlin.math.abs(delta) <= RATE_STEP_PER_CHUNK) target
        else current + if (delta > 0) RATE_STEP_PER_CHUNK else -RATE_STEP_PER_CHUNK
    }

    /**
     * The PCM ended on its own: lets what [ln] still holds (the pacing cushion, tens to hundreds of ms) play out before
     * the line is flushed, so a video's last sound is heard. Bounded, and abandoned at once on a stop, teardown or park.
     */
    private fun playOutTail(ln: SourceDataLine, output: PcmLineOutput?, terminated: AtomicBoolean, stopFlag: AtomicBoolean) {
        val bytesPerSecond = output?.format?.bytesPerSecond ?: return
        val unplayed = { (ln.bufferSize - ln.available()).coerceAtLeast(0) }
        val deadline = System.nanoTime() + tailPlayOutBudgetNanos(unplayed(), bytesPerSecond)
        while (!terminated.get() && !stopFlag.get() && parked?.get() != true && System.nanoTime() < deadline) {
            if (unplayed() <= 0) return
            try {
                Thread.sleep(TAIL_PLAYOUT_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return
            }
        }
    }

    private fun parkIfRequested(ln: SourceDataLine, terminated: AtomicBoolean, stopFlag: AtomicBoolean): Boolean {
        val pk = parked ?: return false
        if (!pk.get()) return false
        runCatching { ln.stop() } // Pause playback, keep the buffered tail and the line open
        while (pk.get() && !terminated.get() && !stopFlag.get()) {
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return true
            }
        }
        if (terminated.get() || stopFlag.get()) return true
        runCatching { ln.start() } // Resume from exactly where it paused (frame position is continuous)
        return false
    }

    private fun writeFully(
        ln: SourceDataLine,
        chunk: ByteArray,
        n: Int,
        terminated: AtomicBoolean,
        stopFlag: AtomicBoolean
    ) {
        var written = 0
        while (written < n && !terminated.get() && !stopFlag.get()) {
            val w = ln.write(chunk, written, n - written)
            if (w <= 0) break
            written += w
        }
    }

    private fun paceLiveWrite(
        ln: SourceDataLine,
        chunk: ByteArray,
        n: Int,
        format: PcmLineFormat,
        terminated: AtomicBoolean,
        stopFlag: AtomicBoolean,
        live: Boolean = false,
    ) {
        val minPace = if (live) LIVE_MIN_PACE_BYTES else MIN_PACE_BYTES
        val maxPace = if (live) LIVE_MAX_PACE_BYTES else MAX_PACE_BYTES
        val paceStep = if (live) LIVE_CHUNK_BYTES else PACE_STEP_BYTES
        // The pacing target is kept in content bytes; a line in another format holds a different byte count for
        // the same duration
        val lineTargetBytes = if (format.isContent) paceTargetBytes
        else (paceTargetBytes.toLong() * format.bytesPerSecond / (SAMPLE_RATE * BYTES_PER_FRAME)).toInt()
        val lineBytesPerSecond = format.bytesPerSecond
        val unplayedBefore = (ln.bufferSize - ln.available()).coerceAtLeast(0)
        signals?.audioBufferedMs = (unplayedBefore.toLong() * 1_000L / lineBytesPerSecond.coerceAtLeast(1)).toInt()
        if (paceStreaming) {
            if (unplayedBefore == 0) {
                // The line ran dry: the audio clock (and the picture paced to it) stalled
                signals?.audioUnderrunAtNanos = System.nanoTime()
                paceTargetBytes = (paceTargetBytes + paceStep).coerceAtMost(maxPace)
                paceCleanChunks = 0
            } else if (++paceCleanChunks >= PACE_RECOVER_CHUNKS) {
                paceTargetBytes = (paceTargetBytes - paceStep).coerceAtLeast(minPace)
                paceCleanChunks = 0
            }
        }
        writeFully(ln, chunk, n, terminated, stopFlag)
        paceStreaming = true
        // Hold the backlog at the target: wait for playout to drain the surplus before feeding the next chunk.
        while (!terminated.get() && !stopFlag.get()) {
            val surplus = (ln.bufferSize - ln.available()).coerceAtLeast(0) - lineTargetBytes
            if (surplus <= 0) break
            val drainMs = (surplus.toLong() * 1_000L / lineBytesPerSecond).coerceIn(1L, 20L)
            try {
                Thread.sleep(drainMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); break
            }
        }
    }

    private fun awaitStartGate(
        gate: CountDownLatch?,
        terminated: AtomicBoolean,
        stopFlag: AtomicBoolean,
    ): Boolean {
        if (gate == null || gate.count == 0L) return true
        while (!terminated.get() && !stopFlag.get()) {
            try {
                if (gate.await(50L, TimeUnit.MILLISECONDS)) return true
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    /**
     * Opens an output line, never giving up just because the device rejects the content format. Java Sound first:
     * 44.1 kHz / 16-bit stereo on the game's device (then the system default) exactly as before, else a format those
     * devices advertise or accept (converted). If the game's device can't be opened that way, OpenAL on the game's
     * device, and only then the system default or any other output. The `audio-backend` setting can force either backend.
     */
    private fun openLine(): OpenedLine? {
        val backend = AudioOutputHooks.effectiveBackend()
        if (backend == "openal") {
            openOpenAl("forced by the audio-backend setting")?.let { return it }
            logger.warn("$debugLabel OpenAL output was requested but could not be opened; trying Java Sound.")
        }
        var lastError: String? = null
        repeat(OPEN_RETRIES) { attempt ->
            val (preferred, other) = AudioOutputHooks.candidateMixers()
            var firstTry = true
            var firstTryFailed = false
            val tryOpen = { mixer: Mixer.Info?, format: PcmLineFormat ->
                val line = tryOpenLine(mixer, format).onFailure { lastError = it.message ?: it.javaClass.simpleName }
                    .getOrNull()
                if (firstTry && line == null) firstTryFailed = true
                firstTry = false
                line
            }
            // When the game plays on a named device, only that device counts as "right": if Java Sound can't open
            // it, OpenAL on that same device comes next, and the system default / other devices only after that,
            // so display audio never silently lands on different speakers than the game.
            val gameDevice = runCatching { AudioOutputHooks.preferredDeviceName() }.getOrNull()?.trim().orEmpty()
            val primary: List<Mixer.Info?> = if (gameDevice.isEmpty()) preferred else preferred.filterNotNull()
            val secondary: List<Mixer.Info?> = if (gameDevice.isEmpty()) emptyList() else listOf(null)
            val cached = lastFallback
            var chosen = cached?.takeIf { it.first in primary }?.let { (mixer, format) ->
                tryOpen(mixer, format)?.let { Triple(mixer, format, it) }
            } ?: PcmLineFormat.choose(primary, emptyList(), AudioOutputHooks::advertisedFormats, tryOpen)
            if (chosen == null && attempt == 0 && backend == "auto") {
                logMixersOnce(lastError)
                openOpenAl("Java Sound could not open the game's output (${lastError ?: "no matching device"})")?.let { return it }
            }
            if (chosen == null) {
                chosen = PcmLineFormat.choose(secondary, other, AudioOutputHooks::advertisedFormats, tryOpen)
            }
            if (firstTryFailed || chosen == null) logMixersOnce(lastError)
            if (chosen != null) {
                val (mixer, format, line) = chosen
                lastFallback = if (format.isContent) null else mixer to format
                logLineChoice("Java Sound", mixer?.name ?: "system default", format)
                return OpenedLine(line, PcmLineOutput(format))
            }
            if (attempt == OPEN_RETRIES - 1) {
                logger.warn("$debugLabel Line unavailable: $lastError.")
                return null
            }
            try {
                Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return null
            }
        }
        return null
    }

    /** Opens an OpenAL line on the game's device (content format, no conversion), or null when unavailable. */
    private fun openOpenAl(why: String): OpenedLine? {
        val factory = AudioOutputHooks.openAlLine ?: return null
        val device = AudioOutputHooks.gameDeviceName()
        val line = runCatching { factory(device) }
            .onFailure { logger.warn("$debugLabel OpenAL output failed to open: $it.") }
            .getOrNull() ?: return null
        logLineChoice("OpenAL", device ?: "default device", PcmLineFormat.CONTENT, why)
        return OpenedLine(line, PcmLineOutput(PcmLineFormat.CONTENT))
    }

    private fun logMixersOnce(error: String?) {
        if (mixersLogged) return
        mixersLogged = true
        logger.info(
            "Display audio: the usual output format did not open ({}). Java Sound outputs and the formats they " +
                    "advertise:\n{}", error ?: "no error given", AudioOutputHooks.describeMixers(),
        )
    }

    private fun tryOpenLine(mixer: Mixer.Info?, format: PcmLineFormat): Result<SourceDataLine> = runCatching {
        val audioFormat = format.toAudioFormat()
        val info = DataLine.Info(SourceDataLine::class.java, audioFormat)
        val line = (if (mixer == null) AudioSystem.getLine(info) else AudioSystem.getMixer(mixer).getLine(info))
            as SourceDataLine
        try {
            line.open(audioFormat, lineBufferBytes(format))
        } catch (e: Throwable) {
            runCatching { line.close() }
            throw e
        }
        line
    }

    /** The same ~400 ms buffer in any format (exactly [LINE_BUFFER_BYTES] for the content format). */
    private fun lineBufferBytes(format: PcmLineFormat): Int {
        if (format.isContent) return LINE_BUFFER_BYTES
        val bytes = format.bytesPerSecond.toLong() * LINE_BUFFER_BYTES / (SAMPLE_RATE * BYTES_PER_FRAME)
        return (bytes / format.bytesPerFrame * format.bytesPerFrame).toInt()
    }

    private fun logLineChoice(backend: String, device: String, format: PcmLineFormat, why: String? = null) {
        val message = when {
            why != null -> "Display audio line: $backend on \"$device\", $format ($why)."
            format.isContent -> "Display audio line: $backend on \"$device\", $format."
            else -> "Display audio line: $backend on \"$device\" rejects ${PcmLineFormat.CONTENT}; " +
                    "playing $format with conversion."
        }
        // Every unusual line (converted / OpenAL) is logged; the normal one only when it changes
        if (why != null || !format.isContent || lastLineChoice != message) logger.info("$debugLabel $message")
        lastLineChoice = message
        com.dreamdisplays.media.runtime.diagnostics.PlaybackDiagnostics.audioLine =
            message.removePrefix("Display audio line: ").removeSuffix(".")
    }

    private fun drainStderr(proc: Process, stopFlag: AtomicBoolean): StringBuilder {
        val buf = StringBuilder()
        daemon({
            try {
                proc.errorStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) {
                        val trimmed = line.trim()
                        if (stopFlag.get()) {
                            logger.debug("$debugLabel [audio] FFmpeg stderr during teardown: $trimmed.")
                        } else if (MediaUtil.isInterestingStderr(trimmed)) {
                            logger.warn("$debugLabel [audio] FFmpeg stderr: $trimmed.")
                        }
                        synchronized(buf) { MediaUtil.appendCapped(buf, line) }
                    }
                }
            } catch (_: IOException) {
            }
        }, "MediaPlayer-astderr").start()
        return buf
    }
}
