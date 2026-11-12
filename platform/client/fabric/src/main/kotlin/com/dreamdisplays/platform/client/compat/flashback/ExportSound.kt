package com.dreamdisplays.platform.client.compat.flashback

import com.dreamdisplays.media.player.process.FFmpegBinary
import org.lwjgl.BufferUtils
import org.lwjgl.openal.AL10
import org.slf4j.LoggerFactory
import java.io.DataInputStream
import java.io.EOFException
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Export only: one display's sound in a Flashback export. Flashback renders the game's OpenAL context into the export
 * frame by frame (a loopback device: exactly the samples of one frame after it is drawn); this plays the display's sound
 * on that context from an OpenAL source of its own, with exactly the samples that belong to the frame queued before
 * the frame's samples are rendered, so they land in the export on that frame. The samples come from an FFmpeg pipe
 * opened at the content position (mono, 48 kHz, float), resampled when the replay plays it at another speed; a jump,
 * a pause or another video reopens it. Render thread only (the game's OpenAL context is current there).
 */
internal class ExportSound(private val displayId: UUID) {
    private var source = 0
    private var url: String? = null
    private var process: Process? = null
    private var input: DataInputStream? = null
    private var pipeAtNanos = Long.MIN_VALUE // content position of the next sample in the pipe
    private var failed = false

    /**
     * Queues the [outSamples] samples of one export frame: the content from [fromNanos] to [toNanos] of [sourceUrl] (or
     * silence when [sourceUrl] is null or [playing] is false), at ([x], [y], [z]) with [gain], fading out linearly to
     * [maxDistance] blocks.
     */
    fun frame(
        sourceUrl: String?, playing: Boolean, fromNanos: Long, toNanos: Long, outSamples: Int,
        x: Double, y: Double, z: Double, gain: Float, maxDistance: Float,
    ) {
        if (outSamples <= 0 || failed) return
        if (source == 0 && !createSource()) return
        reclaimProcessed()
        AL10.alSource3f(source, AL10.AL_POSITION, x.toFloat(), y.toFloat(), z.toFloat())
        AL10.alSourcef(source, AL10.AL_GAIN, gain.coerceIn(0f, 2f))
        AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, maxDistance)
        val pcm = FloatArray(outSamples)
        if (sourceUrl != null && playing && toNanos > fromNanos) {
            val content = read(sourceUrl, fromNanos, toNanos)
            if (content != null) resample(content, pcm)
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

    /**
     * The content samples from [fromNanos] to [toNanos], reopening the pipe at [fromNanos] unless it already stands
     * there. Counted from where the pipe stands, so rounding never adds up over a long export.
     */
    private fun read(sourceUrl: String, fromNanos: Long, toNanos: Long): FloatArray? {
        if (sourceUrl != url || input == null || abs(fromNanos - pipeAtNanos) > PIPE_SLACK_NANOS) {
            if (!openPipe(sourceUrl, fromNanos)) return null
        }
        val startNanos = pipeAtNanos
        val count = ((toNanos - startNanos) * RATE / 1_000_000_000.0).roundToLong().toInt().coerceAtLeast(1)
        val out = FloatArray(count)
        val stream = input ?: return null
        try {
            for (i in 0 until count) out[i] = java.lang.Float.intBitsToFloat(Integer.reverseBytes(stream.readInt()))
        } catch (_: EOFException) {
            closePipe() // past the end: silence from here
        } catch (e: Exception) {
            closePipe()
        }
        if (input != null) pipeAtNanos = startNanos + count * 1_000_000_000L / RATE
        return out
    }

    private fun openPipe(sourceUrl: String, atNanos: Long): Boolean {
        closePipe()
        url = sourceUrl
        val ffmpeg = FFmpegBinary.getPath() ?: run {
            failed = true
            return false
        }
        val cmd = listOf(
            ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error",
            "-ss", String.format(Locale.ROOT, "%.6f", atNanos / 1e9), "-i", sourceUrl,
            "-vn", "-ac", "1", "-ar", RATE.toString(), "-f", "f32le", "-",
        )
        val p = runCatching { ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start() }.getOrNull()
            ?: return false
        process = p
        input = DataInputStream(p.inputStream.buffered(1 shl 16))
        pipeAtNanos = atNanos
        return true
    }

    private fun closePipe() {
        runCatching { input?.close() }
        process?.destroyForcibly()
        input = null
        process = null
        pipeAtNanos = Long.MIN_VALUE
    }

    fun close() {
        closePipe()
        if (source != 0) {
            runCatching {
                AL10.alSourceStop(source)
                reclaimProcessed()
                var queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED)
                while (queued-- > 0) AL10.alDeleteBuffers(AL10.alSourceUnqueueBuffers(source))
                AL10.alDeleteSources(source)
            }
            source = 0
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger("DreamDisplays/Flashback")
        const val RATE = 48_000

        /** AL_EXT_float32's mono format (OpenAL Soft). */
        private const val AL_FORMAT_MONO_FLOAT32 = 0x10010

        /** The pipe is reopened when the content moved further than this from where it stands. */
        private const val PIPE_SLACK_NANOS = 5_000_000L

        /** [content] stretched or squeezed to fill [out] (linear interpolation): another replay speed, pitched. */
        fun resample(content: FloatArray, out: FloatArray) {
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
