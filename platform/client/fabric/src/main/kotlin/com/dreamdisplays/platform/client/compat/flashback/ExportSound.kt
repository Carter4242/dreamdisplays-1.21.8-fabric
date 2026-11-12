package com.dreamdisplays.platform.client.compat.flashback

import com.dreamdisplays.media.player.process.FFmpegBinary
import com.dreamdisplays.media.player.process.MediaProcess
import org.lwjgl.BufferUtils
import org.lwjgl.openal.AL10
import org.lwjgl.openal.ALC10
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToLong

/**
 * Export only: one display's sound in a Flashback export. Flashback renders the game's OpenAL context into the export
 * frame by frame (a loopback device: exactly the samples of one frame after it is drawn); this plays the display's sound
 * on that context from an OpenAL source of its own, with exactly the samples that belong to the frame queued before
 * the frame's samples are rendered, so they land in the export on that frame.
 *
 * The samples come from an FFmpeg pipe on the display's audio stream (with the players' request headers) opened at the
 * content position: mono, 48 kHz, float, read ahead on a thread of its own. The render thread never waits on the
 * network for more than [READ_DEADLINE_MS]: past that the frame is silent and the pipe is dropped. A small step forward
 * is skipped in the pipe, a small step back or overlap is read on (the content per frame follows the replay, which a
 * speed keyframe changes); only a real jump, a pause or another stream reopens it. Render thread only.
 */
internal class ExportSound(private val displayId: UUID) {
    private var source = 0
    private var sourceContext = 0L
    private var pipe: Pipe? = null
    private var pipeUrl: String? = null
    private var pipeAtNanos = Long.MIN_VALUE // content position of the next sample in the pipe
    private var silentUrl: String? = null // a stream that gave no sound at all: not tried again every frame
    private var retryAfterNanos = 0L
    private var failed = false

    /**
     * Queues the [outSamples] samples of one export frame: the content from [fromNanos] to [toNanos] of [audioUrl] (or
     * silence when [audioUrl] is null or [playing] is false), at ([x], [y], [z]) with [gain], fading out linearly to
     * [maxDistance] blocks.
     */
    fun frame(
        audioUrl: String?, playing: Boolean, fromNanos: Long, toNanos: Long, outSamples: Int,
        x: Double, y: Double, z: Double, gain: Float, maxDistance: Float,
    ) {
        if (outSamples <= 0 || failed) return
        if (source == 0 && !createSource()) return
        if (ALC10.alcGetCurrentContext() != sourceContext) return // the game's sound moved off the export device
        reclaimProcessed()
        AL10.alSource3f(source, AL10.AL_POSITION, x.toFloat(), y.toFloat(), z.toFloat())
        AL10.alSourcef(source, AL10.AL_GAIN, gain.coerceIn(0f, 2f))
        AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, maxDistance)
        val pcm = FloatArray(outSamples)
        if (audioUrl != null && playing && toNanos > fromNanos) {
            read(audioUrl, fromNanos, toNanos)?.let { resample(it, pcm) }
        } else {
            closePipe() // a pause or a stop: whatever plays next opens where it is then
        }
        val buffer = AL10.alGenBuffers()
        val data = BufferUtils.createFloatBuffer(outSamples).put(pcm).flip()
        AL10.alBufferData(buffer, AL_FORMAT_MONO_FLOAT32, data, RATE)
        AL10.alSourceQueueBuffers(source, buffer)
        // Each frame's samples are all rendered with that frame, so the source runs dry between frames: start it again
        if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) AL10.alSourcePlay(source)
        val err = AL10.alGetError()
        if (err != AL10.AL_NO_ERROR) {
            logger.warn("Export: OpenAL error {} for display {}; its sound is left out.", err, displayId)
            failed = true
            close()
        }
    }

    private fun createSource(): Boolean {
        AL10.alGetError()
        source = AL10.alGenSources()
        if (AL10.alGetError() != AL10.AL_NO_ERROR || source == 0) {
            logger.warn("Export: no OpenAL source for display {}; its sound is left out.", displayId)
            source = 0
            failed = true
            return false
        }
        sourceContext = ALC10.alcGetCurrentContext()
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE)
        AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 0f)
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1f)
        AL10.alSourcef(source, AL10.AL_PITCH, 1f)
        return true
    }

    private fun reclaimProcessed() {
        var processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED)
        while (processed-- > 0) AL10.alDeleteBuffers(AL10.alSourceUnqueueBuffers(source))
    }

    /** The content from [fromNanos] to [toNanos], or null (silence) when the pipe can't give it in time. */
    private fun read(audioUrl: String, fromNanos: Long, toNanos: Long): FloatArray? {
        if (audioUrl == silentUrl) return null
        val step = fromNanos - pipeAtNanos
        val usable = pipe != null && audioUrl == pipeUrl && step in -MAX_OVERLAP_NANOS..MAX_SKIP_NANOS
        if (!usable) {
            if (System.nanoTime() < retryAfterNanos) return null
            if (!openPipe(audioUrl, fromNanos)) return null
        } else if (step > 0) {
            // A little ahead of the pipe (the replay sped up): skip to it rather than reopen
            val skip = (step * RATE / 1_000_000_000.0).roundToLong().toInt()
            if (pipe!!.take(skip, READ_DEADLINE_MS) == null) return stalled()
            pipeAtNanos += skip * 1_000_000_000L / RATE
        }
        // A little behind it (slowed down) is read on from where the pipe is: never reopened for a few ms
        val start = pipeAtNanos
        val count = ((toNanos - fromNanos) * RATE / 1_000_000_000.0).roundToLong().toInt().coerceAtLeast(1)
        val samples = pipe!!.take(count, READ_DEADLINE_MS) ?: return stalled()
        pipeAtNanos = start + samples.size * 1_000_000_000L / RATE
        if (samples.size < count && pipe!!.ended) {
            if (pipe!!.total == 0L) {
                logger.info("Export: no sound in the audio stream of display {}.", displayId)
                silentUrl = audioUrl
            }
            closePipe() // past the end: silence from here, until the content jumps back
        }
        return samples.copyOf(count)
    }

    private fun stalled(): FloatArray? {
        logger.warn("Export: the audio stream of display {} stalled; silence until it can be opened again.", displayId)
        closePipe()
        retryAfterNanos = System.nanoTime() + RETRY_NANOS
        return null
    }

    private fun openPipe(audioUrl: String, atNanos: Long): Boolean {
        closePipe()
        val ffmpeg = FFmpegBinary.getPath() ?: run {
            failed = true
            return false
        }
        val network = audioUrl.startsWith("http://") || audioUrl.startsWith("https://")
        val cmd = buildList {
            addAll(listOf(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error"))
            if (network) {
                addAll(MediaProcess.inputHeaderArgs(audioUrl))
                addAll(listOf("-rw_timeout", (READ_DEADLINE_MS * 1_000L).toString()))
            }
            // A seek on the input lands where the container can (HLS: a segment, up to half a second off): seek the
            // input a little before, keep the stream's own timestamps, and cut exactly at the position on the way out
            val coarse = (atNanos - COARSE_SEEK_NANOS).coerceAtLeast(0L)
            addAll(listOf("-ss", seconds(coarse), "-copyts", "-start_at_zero", "-i", audioUrl, "-ss", seconds(atNanos)))
            addAll(listOf("-vn", "-ac", "1", "-ar", RATE.toString(), "-f", "f32le", "-"))
        }
        val p = runCatching { ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start() }.getOrNull()
            ?: return false
        pipe = Pipe(p)
        pipeUrl = audioUrl
        pipeAtNanos = atNanos
        return true
    }

    private fun seconds(nanos: Long): String = String.format(Locale.ROOT, "%.6f", nanos / 1e9)

    private fun closePipe() {
        pipe?.close()
        pipe = null
        pipeAtNanos = Long.MIN_VALUE
    }

    /**
     * Ends this display's sound. Its OpenAL source belongs to the export's context: when the game's sound has already
     * moved back to its own device, that context and everything in it is gone, and no AL call is made for it.
     */
    fun close() {
        closePipe()
        if (source != 0) {
            if (ALC10.alcGetCurrentContext() == sourceContext) {
                runCatching {
                    AL10.alSourceStop(source)
                    reclaimProcessed()
                    var queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED)
                    while (queued-- > 0) AL10.alDeleteBuffers(AL10.alSourceUnqueueBuffers(source))
                    AL10.alDeleteSources(source)
                    AL10.alGetError()
                }
            }
            source = 0
        }
    }

    /** An FFmpeg process whose float output a thread of its own reads ahead (bounded) into memory. */
    private class Pipe(private val process: Process) {
        private val lock = ReentrantLock()
        private val ready = lock.newCondition()
        private val chunks = ArrayDeque<FloatArray>()
        private var head = 0 // read position in the first chunk
        private var buffered = 0
        @Volatile var ended = false; private set
        @Volatile var total = 0L; private set
        @Volatile private var closed = false

        init {
            Thread({ pump(process.inputStream) }, "DreamDisplays-ExportAudio").apply { isDaemon = true }.start()
        }

        private fun pump(input: InputStream) {
            val bytes = ByteArray(1 shl 15)
            var carry = 0
            try {
                while (!closed) {
                    lock.withLock { while (buffered > MAX_BUFFERED && !closed) ready.await(100, TimeUnit.MILLISECONDS) }
                    val n = input.read(bytes, carry, bytes.size - carry)
                    if (n < 0) break
                    val avail = carry + n
                    val floats = avail / 4
                    val buf = ByteBuffer.wrap(bytes, 0, floats * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val chunk = FloatArray(floats).also { buf.get(it) }
                    carry = avail - floats * 4
                    System.arraycopy(bytes, floats * 4, bytes, 0, carry)
                    if (floats > 0) lock.withLock {
                        chunks.addLast(chunk)
                        buffered += floats
                        total += floats
                        ready.signalAll()
                    }
                }
            } catch (_: Exception) {
            } finally {
                lock.withLock {
                    ended = true
                    ready.signalAll()
                }
            }
        }

        /** [count] samples, fewer at the end of the stream, or null when they did not come within [deadlineMs]. */
        fun take(count: Int, deadlineMs: Long): FloatArray? = lock.withLock {
            val deadline = System.nanoTime() + deadlineMs * 1_000_000L
            while (buffered < count && !ended) {
                val left = deadline - System.nanoTime()
                if (left <= 0) return null
                ready.awaitNanos(left)
            }
            val n = minOf(count, buffered)
            val out = FloatArray(n)
            var i = 0
            while (i < n) {
                val c = chunks.first()
                val m = minOf(n - i, c.size - head)
                System.arraycopy(c, head, out, i, m)
                i += m
                head += m
                if (head == c.size) {
                    chunks.removeFirst()
                    head = 0
                }
            }
            buffered -= n
            ready.signalAll()
            out
        }

        fun close() {
            closed = true
            process.destroyForcibly()
            lock.withLock { ready.signalAll() }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger("DreamDisplays/Flashback")
        const val RATE = 48_000

        /** AL_EXT_float32's mono format (OpenAL Soft). */
        private const val AL_FORMAT_MONO_FLOAT32 = 0x10010

        /** Longest the render thread waits on the audio stream for one frame. */
        private const val READ_DEADLINE_MS = 10_000L

        /** After a stall, how long before the stream is tried again. */
        private const val RETRY_NANOS = 5_000_000_000L

        /** The replay's content ahead of the pipe by up to this is skipped in the pipe instead of reopening it. */
        private const val MAX_SKIP_NANOS = 2_000_000_000L

        /** Behind the pipe by up to this, the frame is read on from the pipe (a few ms early or late, no reopen). */
        private const val MAX_OVERLAP_NANOS = 100_000_000L

        /** The input is opened this far before the position, which is then cut exactly. */
        private const val COARSE_SEEK_NANOS = 3_000_000_000L

        /** Read-ahead cap: 10 s of sound. */
        private const val MAX_BUFFERED = RATE * 10

        /** [content] stretched or squeezed to fill [out] (linear interpolation): another replay speed, pitched. */
        fun resample(content: FloatArray, out: FloatArray) {
            if (content.isEmpty()) return
            if (content.size == out.size) {
                content.copyInto(out)
                return
            }
            val step = (content.size - 1).toDouble() / (out.size - 1).coerceAtLeast(1)
            for (i in out.indices) {
                val pos = i * step
                val j = pos.toInt().coerceAtMost(content.size - 1)
                val f = (pos - j).toFloat()
                val a = content[j]
                val b = content[(j + 1).coerceAtMost(content.size - 1)]
                out[i] = a + (b - a) * f
            }
        }
    }
}
