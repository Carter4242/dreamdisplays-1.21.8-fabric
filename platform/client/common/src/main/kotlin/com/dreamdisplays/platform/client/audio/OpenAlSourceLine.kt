package com.dreamdisplays.platform.client.audio

import org.lwjgl.openal.AL10
import org.lwjgl.openal.AL11
import org.lwjgl.openal.ALC10
import org.lwjgl.openal.EXTThreadLocalContext
import org.lwjgl.openal.SOFTDeviceClock
import com.dreamdisplays.media.player.pipeline.LatencyReportingLine
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.Control
import javax.sound.sampled.Line
import javax.sound.sampled.LineListener
import javax.sound.sampled.SourceDataLine

/**
 * A [SourceDataLine] that plays through OpenAL instead of Java Sound, for machines where Java Sound has no usable
 * output (and for testing, when forced). Plays 44.1 kHz 16-bit stereo, the player's content format, as is.
 *
 * It opens its own OpenAL device and context on the game's output device and only ever makes that context current
 * per thread (`ALC_EXT_thread_local_context`) around its own calls, so the game's sound context is never touched.
 * Audio is streamed through a queue of OpenAL buffers on one source; the played position (what the audio clock
 * reads) comes from the processed buffers plus the source's sample offset, refreshed by a small monitor thread so
 * readers never call into OpenAL themselves. The same thread reads the device's own output latency
 * (`ALC_SOFT_device_clock`) for karaoke timing.
 */
internal class OpenAlSourceLine private constructor(
    private val device: Long,
    private val context: Long,
    private val bufferBytes: Int,
) : SourceDataLine, LatencyReportingLine {
    private val lock = Any()
    private val source: Int
    private val freeBuffers = ArrayDeque<Int>()
    private val queued = ArrayDeque<Pair<Int, Int>>() // AL buffer id to its frame count, oldest first
    private val staging: ByteBuffer = ByteBuffer.allocateDirect(bufferBytes).order(ByteOrder.nativeOrder())

    @Volatile
    private var open = true

    @Volatile
    private var started = false

    /** Frames written so far (all buffers ever queued). */
    private var writtenFrames = 0L

    /** Frames of buffers already played and unqueued. */
    private var unqueuedFrames = 0L

    @Volatile
    private var playedFrames = 0L

    private val monitor: Thread

    /** The device's output latency (ns) as last read, -1 when OpenAL can't tell. */
    @Volatile
    private var deviceLatency = -1L
    private var lastLatencyReadNanos = 0L
    private val deviceClock = runCatching { ALC10.alcIsExtensionPresent(device, "ALC_SOFT_device_clock") }.getOrDefault(false)

    init {
        source = withContext { AL10.alGenSources() }
        withContext {
            AL10.alSourcef(source, AL10.AL_GAIN, 1f)
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE)
            AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f)
        }
        monitor = Thread({ monitorLoop() }, "DreamDisplays-OpenAL-line").apply { isDaemon = true; start() }
    }

    private inline fun <T> withContext(block: () -> T): T = synchronized(lock) {
        EXTThreadLocalContext.alcSetThreadContext(context)
        try {
            block()
        } finally {
            EXTThreadLocalContext.alcSetThreadContext(0L)
        }
    }

    private fun monitorLoop() {
        while (open) {
            runCatching { refresh() }
            readDeviceLatency()
            try {
                Thread.sleep(MONITOR_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** Recycles played buffers, updates [playedFrames] and restarts the source after an underrun. */
    private fun refresh() {
        withContext {
            if (!open) return@withContext
            var processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED)
            while (processed-- > 0 && queued.isNotEmpty()) {
                val buffer = AL10.alSourceUnqueueBuffers(source)
                val (_, frames) = queued.removeFirst()
                unqueuedFrames += frames
                freeBuffers.addLast(buffer)
            }
            val offset = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET).toLong()
            val position = (unqueuedFrames + offset).coerceAtMost(writtenFrames)
            if (position > playedFrames) playedFrames = position
            if (started && queued.isNotEmpty() && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                AL10.alSourcePlay(source)
            }
        }
    }

    private fun readDeviceLatency() {
        if (!deviceClock) return
        val now = System.nanoTime()
        if (now - lastLatencyReadNanos < LATENCY_READ_NANOS) return
        lastLatencyReadNanos = now
        val latency = runCatching {
            synchronized(lock) { if (open) SOFTDeviceClock.alcGetInteger64vSOFT(device, SOFTDeviceClock.ALC_DEVICE_LATENCY_SOFT) else -1L }
        }.getOrDefault(-1L)
        deviceLatency = if (latency in 0..MAX_LATENCY_NANOS) latency else -1L
    }

    override fun deviceLatencyNanos(): Long = deviceLatency

    private fun unplayedBytes(): Int = ((writtenFrames - playedFrames).coerceAtLeast(0L) * FRAME_BYTES).toInt()

    override fun write(b: ByteArray, off: Int, len: Int): Int {
        var done = 0
        val whole = len - len % FRAME_BYTES
        while (done < whole && open) {
            val room = synchronized(lock) { bufferBytes - unplayedBytes() }
            val n = minOf(whole - done, room - room % FRAME_BYTES, MAX_PIECE_BYTES)
            if (n <= 0) {
                if (!started) break // A stopped line never drains; report what fit, like a full Java Sound line
                try {
                    Thread.sleep(2)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt(); break
                }
                refresh()
                continue
            }
            withContext {
                if (!open) return@withContext
                val buffer = freeBuffers.pollFirst() ?: AL10.alGenBuffers()
                staging.clear()
                staging.put(b, off + done, n).flip()
                AL10.alBufferData(buffer, AL10.AL_FORMAT_STEREO16, staging, SAMPLE_RATE)
                AL10.alSourceQueueBuffers(source, buffer)
                queued.addLast(buffer to n / FRAME_BYTES)
                writtenFrames += n / FRAME_BYTES
                if (started && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                    AL10.alSourcePlay(source)
                }
            }
            done += n
        }
        return done
    }

    override fun start() {
        withContext {
            started = true
            if (queued.isNotEmpty()) AL10.alSourcePlay(source)
        }
    }

    override fun stop() {
        withContext {
            started = false
            AL10.alSourcePause(source)
        }
    }

    override fun flush() {
        withContext {
            AL10.alSourceStop(source)
            // Stopping marks every queued buffer processed; take them all back
            while (queued.isNotEmpty()) {
                val buffer = AL10.alSourceUnqueueBuffers(source)
                queued.removeFirst()
                freeBuffers.addLast(buffer)
            }
            AL10.alSourceRewind(source)
            // What was dropped counts as played, so the position stays continuous and nothing is waited for
            unqueuedFrames = writtenFrames
            playedFrames = writtenFrames
        }
    }

    override fun drain() {
        while (open && started && synchronized(lock) { unplayedBytes() } > 0) {
            try {
                Thread.sleep(5)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return
            }
            refresh()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!open) return
            open = false
        }
        monitor.interrupt()
        runCatching { monitor.join(500) }
        synchronized(lock) {
            runCatching {
                EXTThreadLocalContext.alcSetThreadContext(context)
                try {
                    AL10.alSourceStop(source)
                    AL10.alSourcei(source, AL10.AL_BUFFER, 0)
                    AL10.alDeleteSources(source)
                    (freeBuffers + queued.map { it.first }).forEach { AL10.alDeleteBuffers(it) }
                    freeBuffers.clear()
                    queued.clear()
                } finally {
                    EXTThreadLocalContext.alcSetThreadContext(0L)
                }
            }
            runCatching { ALC10.alcDestroyContext(context) }
            runCatching { ALC10.alcCloseDevice(device) }
        }
    }

    override fun available(): Int = synchronized(lock) { (bufferSize - unplayedBytes()).coerceAtLeast(0) }
    override fun getBufferSize(): Int = bufferBytes
    override fun getFormat(): AudioFormat = FORMAT
    override fun getFramePosition(): Int = playedFrames.toInt()
    override fun getLongFramePosition(): Long = playedFrames
    override fun getMicrosecondPosition(): Long = playedFrames * 1_000_000L / SAMPLE_RATE
    override fun getLevel(): Float = AudioSystemNotSpecified
    override fun isRunning(): Boolean = started && open
    override fun isActive(): Boolean = started && open
    override fun isOpen(): Boolean = open
    override fun open(format: AudioFormat, bufferSize: Int) = Unit
    override fun open(format: AudioFormat) = Unit
    override fun open() = Unit
    override fun getLineInfo(): Line.Info = Line.Info(SourceDataLine::class.java)
    override fun getControls(): Array<Control> = emptyArray()
    override fun isControlSupported(control: Control.Type?): Boolean = false
    override fun getControl(control: Control.Type?): Control =
        throw IllegalArgumentException("OpenAL display line has no controls")
    override fun addLineListener(listener: LineListener?) = Unit
    override fun removeLineListener(listener: LineListener?) = Unit

    companion object {
        private val logger = LoggerFactory.getLogger(OpenAlSourceLine::class.java)

        private const val SAMPLE_RATE = 44_100
        private const val FRAME_BYTES = 4
        private const val MONITOR_MS = 5L
        private const val LATENCY_READ_NANOS = 500_000_000L
        private const val MAX_LATENCY_NANOS = 1_000_000_000L
        private const val MAX_PIECE_BYTES = SAMPLE_RATE * FRAME_BYTES / 20
        private const val AudioSystemNotSpecified = -1f

        /** Same ~400 ms buffer as the Java Sound line. */
        private const val BUFFER_BYTES = MAX_PIECE_BYTES * 8

        private val FORMAT = AudioFormat(SAMPLE_RATE.toFloat(), 16, 2, true, false)

        /**
         * Opens a line on the OpenAL device called [deviceName] (the game's choice; null or unknown = default), or
         * returns null when OpenAL or its per-thread contexts are not available.
         */
        fun open(deviceName: String?): SourceDataLine? {
            val device = deviceName?.let { ALC10.alcOpenDevice(it) }?.takeIf { it != 0L }
                ?: ALC10.alcOpenDevice(null as CharSequence?).takeIf { it != 0L }
                ?: run {
                    logger.warn("OpenAL display output: no OpenAL device could be opened.")
                    return null
                }
            if (!ALC10.alcIsExtensionPresent(device, "ALC_EXT_thread_local_context")) {
                logger.warn("OpenAL display output: ALC_EXT_thread_local_context is missing; not using OpenAL.")
                ALC10.alcCloseDevice(device)
                return null
            }
            val context = ALC10.alcCreateContext(device, null as java.nio.IntBuffer?)
            if (context == 0L) {
                logger.warn("OpenAL display output: could not create a context.")
                ALC10.alcCloseDevice(device)
                return null
            }
            return runCatching { OpenAlSourceLine(device, context, BUFFER_BYTES) }.getOrElse { e ->
                logger.warn("OpenAL display output failed to start: {}", e.toString())
                runCatching { ALC10.alcDestroyContext(context) }
                runCatching { ALC10.alcCloseDevice(device) }
                null
            }
        }
    }
}
