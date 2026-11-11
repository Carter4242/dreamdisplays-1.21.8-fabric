package com.dreamdisplays.media.player.pipeline

import com.dreamdisplays.api.media.model.FramePixelFormat
import com.dreamdisplays.api.media.player.FrameUploaderFactory
import com.dreamdisplays.api.media.player.GpuTextureRef
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.media.player.process.MediaProcess
import com.dreamdisplays.media.player.util.MediaUtil
import com.dreamdisplays.media.player.util.daemon
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Producer-consumer frame buffer and `FFmpeg` video reader loop (pure-JVM pipeline). The reader thread parses raw
 * frames from the process's stdout into [FrameSurface]: PPM (RGB24) by default, or headerless RGBA
 * ([MediaProcess.VideoTransport.RAW_RGBA]) for a video with an alpha channel.
 */
internal class VideoFramePipe(
    private val debugLabel: String,
    uploaderFactory: FrameUploaderFactory,
    private val transport: MediaProcess.VideoTransport = MediaProcess.VideoTransport.PPM,
) : FramePipe {
    init {
        require(transport != MediaProcess.VideoTransport.RAW_NV12) { "NV12 frames are read by the native pipe." }
    }

    private val rgba = transport == MediaProcess.VideoTransport.RAW_RGBA
    private val pixelFormat = if (rgba) FramePixelFormat.RGBA32 else FramePixelFormat.RGB24

    private val logger = LoggerFactory.getLogger(javaClass)

    private companion object {
        const val PARK_POLL_MS = 10L

        /** Read granularity for headerless RGBA frames (a PPM frame is read a row at a time instead). */
        const val RAW_READ_CHUNK = 256 * 1024
    }

    override val lastFrameReceivedNanos = AtomicLong(0)

    @Volatile
    private var rawPopoutFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null
    private val lastFrame = LastFrameCache()

    /** Whether this session keeps its latest frame for a late sink; never for a live relay (see [LastFrameCache]). */
    @Volatile
    private var keepLastFrame = true

    override var popoutFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = rawPopoutFrameSink
        set(value) {
            rawPopoutFrameSink = value
            if (value != null) lastFrame.replay(value)
        }

    @Volatile
    var expectedW = 0; private set

    @Volatile
    var expectedH = 0; private set

    private val surface = FrameSurface(debugLabel, uploaderFactory, pixelFormat)

    @Volatile
    private var activePrebuffer: FramePrebuffer? = null

    @Volatile
    private var parked: AtomicBoolean? = null

    override fun textureFilled(): Boolean = surface.textureFilled()

    override fun updateFrame(texture: GpuTextureRef, actualW: Int, actualH: Int): Boolean =
        surface.updateFrame(texture, actualW, actualH, expectedW, expectedH)

    override fun clear() {
        surface.clear()
        // A sink attached later (a popout, PiP) must not get the cleared picture back
        lastFrame.clear()
    }

    override fun trimForPark() {
        activePrebuffer?.trimForPark()
        surface.clear()
    }

    override fun cleanup() = surface.cleanup()

    /**
     * Starts the video reader thread and returns it (already running). A [liveEdge] session (a live relay stream)
     * shows every frame the moment it is read, with no jitter buffer and no pacing against the audio clock.
     */
    fun start(
        proc: Process, w: Int, h: Int, seekOffsetNanos: Long, sourceFps: Double, stopFlag: AtomicBoolean,
        terminated: AtomicBoolean, getAudioClock: () -> Long, onFirstFrame: () -> Unit, getBrightness: () -> Double,
        onEos: (stderr: String, normalEos: Boolean) -> Unit, parkFlag: AtomicBoolean? = null,
        presentPreview: Boolean = true, tolerateLateness: Boolean = true, liveEdge: Boolean = false,
    ): Thread {
        clear()
        expectedW = w
        expectedH = h
        parked = parkFlag
        lastFrameReceivedNanos.set(System.nanoTime())
        // Must be the same rate FFmpeg was pinned to -r, or synthesized timestamps drift
        val frameNs = (1_000_000_000.0 / MediaProcess.outputFps(sourceFps)).toLong()
        keepLastFrame = !liveEdge
        if (liveEdge) lastFrame.clear()
        val prebuffer = if (liveEdge) null else FramePrebuffer.create(
            surface, frameNs, getAudioClock, onFirstFrame, terminated, stopFlag, debugLabel, presentPreview,
            tolerateLateness, parkFlag, frameBytes = w * h * pixelFormat.bytesPerPixel,
        )
        activePrebuffer = prebuffer
        // Feed the popout / PiP sink from the prebuffer's paced consumer so it stays in sync with the
        // in-world display; feeding at decode time would run the popout ahead by the buffer depth.
        prebuffer?.onPresent = { buf -> feedSink(buf, w, h) }
        return daemon(
            {
                read(
                    proc, w, h, frameNs, seekOffsetNanos, stopFlag, terminated, getBrightness, onEos, prebuffer,
                    onFirstFrame,
                )
            },
            "MediaPlayer-video",
        ).also { it.start() }
    }

    private fun read(
        proc: Process, w: Int, h: Int, frameNs: Long, seekOffsetNanos: Long, stopFlag: AtomicBoolean,
        terminated: AtomicBoolean, getBrightness: () -> Double,
        onEos: (stderr: String, normalEos: Boolean) -> Unit, prebuffer: FramePrebuffer?,
        onFirstFrame: () -> Unit,
    ) {
        var firstFrameArmed = false
        // Dimensions are fixed per session (mismatched frames are skipped in the loop), so the size never changes.
        val frameSize = w * h * pixelFormat.bytesPerPixel
        var spare = surface.allocateFrameBuffer(frameSize)
        surface.recycleFrameBuffer(surface.allocateFrameBuffer(frameSize))

        var videoPts = seekOffsetNanos

        val stderrBuf = StringBuilder()
        val stderrThread = daemon({
            try {
                BufferedReader(InputStreamReader(proc.errorStream)).use { r ->
                    r.lineSequence().forEach { line ->
                        synchronized(stderrBuf) { MediaUtil.appendCapped(stderrBuf, line) }
                        if (MediaUtil.isInterestingStderr(line)) {
                            logger.warn("$debugLabel FFmpeg[V] $line")
                        }
                    }
                }
            } catch (_: IOException) {
            }
        }, "MediaPlayer-vstderr").also { it.start() }

        var normalEos = false

        runCatching {
            proc.inputStream.use { input ->
                val rowBuf = ByteArray(if (rgba) RAW_READ_CHUNK else w * 3)
                while (!terminated.get() && !stopFlag.get()) {
                    // Parked (warm pause / out of render distance): idle without reading; the full pipe
                    // back-pressures FFmpeg into a standstill, keeping the decode and connection warm.
                    val pk = parked
                    if (pk != null && pk.get()) {
                        while (pk.get() && !terminated.get() && !stopFlag.get()) {
                            try {
                                Thread.sleep(PARK_POLL_MS)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt(); break
                            }
                        }
                        lastFrameReceivedNanos.set(System.nanoTime())
                        continue
                    }
                    // Raw RGBA has no header: the stream is a plain run of fixed-size frames
                    if (!rgba && !skipToP6(input)) {
                        normalEos = true; break
                    }
                    val pw = if (rgba) w else readAsciiInt(input)
                    val ph = if (rgba) h else readAsciiInt(input)
                    val maxVal = if (rgba) 255 else readAsciiInt(input)
                    if (pw != w || ph != h || maxVal != 255) {
                        logger.warn("$debugLabel PPM header mismatch: ${pw}x$ph max=$maxVal (expected ${w}x$h max=255).")
                        val skip = pw.toLong() * ph * 3
                        if (pw <= 0 || ph <= 0 || skip <= 0 || !skipBytes(input, skip)) {
                            normalEos = true; break
                        }
                        continue
                    }
                    spare.clear()
                    if (spare.remaining() < frameSize) {
                        logger.warn("$debugLabel Reallocated undersized frame buffer: remaining=${spare.remaining()} required=$frameSize.")
                        spare = surface.allocateFrameBuffer(frameSize)
                        spare.clear()
                    }
                    if (!readFully(input, spare, rowBuf, frameSize)) {
                        normalEos = true; break
                    }
                    applyBrightness(spare, frameSize, getBrightness())
                    spare.flip()

                    lastFrameReceivedNanos.set(System.nanoTime())

                    val pk2 = parked
                    if (pk2 != null && pk2.get()) {
                        continue
                    }

                    if (prebuffer == null) {
                        // Live edge: shown now; the surface keeps only the newest frame for the next render frame
                        if (!firstFrameArmed) {
                            firstFrameArmed = true
                            onFirstFrame()
                        }
                        if (MediaPlayer.captureSamples) {
                            feedSink(spare, w, h)
                            spare = surface.publish(spare, frameSize)
                        }
                    } else if (MediaPlayer.captureSamples) {
                        spare = prebuffer.submit(spare, videoPts, frameSize)
                        if (MediaPlayer.DEBUG) MediaPlayer.samplesIn.incrementAndGet()
                    } else {
                        feedSink(spare, w, h)
                    }
                    videoPts += frameNs
                }
            }
        }.onFailure { e ->
            if (e !is IOException) throw e
            if (MediaPlayer.DEBUG && !terminated.get() && !stopFlag.get()) {
                logger.warn("$debugLabel Read: ${e.message}")
            }
        }

        // Normal EOF should drain queued frames; abort only for teardown / errors. This matters for
        // short replay / cached sources that can fill the prebuffer and EOF before live video is ready.
        if (!terminated.get() && !stopFlag.get() && normalEos) {
            prebuffer?.finish()
            // The end is reported below: let the queued tail play first (see awaitTailPlayed)
            prebuffer?.awaitTailPlayed(held = { parked?.get() == true })
        } else {
            prebuffer?.abort()
        }
        if (activePrebuffer === prebuffer) activePrebuffer = null

        var exitCode = -1
        if (normalEos) {
            runCatching {
                val done = proc.waitFor(500, TimeUnit.MILLISECONDS)
                exitCode = if (done) proc.exitValue() else -1
                if (!done) proc.destroyForcibly()
            }.onFailure { e ->
                if (e !is InterruptedException) throw e
                Thread.currentThread().interrupt()
            }
        }

        if (!terminated.get() && !stopFlag.get()) {
            runCatching { stderrThread.join(500) }
            val stderr = synchronized(stderrBuf) { stderrBuf.toString() }
            onEos(stderr, exitCode == 0)
        }
    }

    private fun feedSink(buf: ByteBuffer, w: Int, h: Int) {
        // Kept with or without a sink, so one opened on a paused video shows it; never for a live relay
        if (keepLastFrame) lastFrame.store(buf, w, h, w * h * pixelFormat.bytesPerPixel, pixelFormat)
        val sink = popoutFrameSink ?: return
        sink(buf, w, h, pixelFormat)
        buf.rewind()
    }

    private fun skipToP6(input: InputStream): Boolean {
        var state = 0 // 0: looking for 'P', 1: saw 'P', expecting '6'
        while (true) {
            val b = input.read()
            if (b < 0) return false
            when (state) {
                0 -> if (b == 'P'.code) state = 1
                1 -> state = if (b == '6'.code) {
                    val w = input.read()
                    if (w < 0) return false
                    if (isWhitespace(w)) return true
                    if (w == 'P'.code) 1 else 0
                } else if (b == 'P'.code) 1 else 0
            }
        }
    }

    private fun readAsciiInt(input: InputStream): Int {
        var b = input.read()
        while (b >= 0) {
            if (b == '#'.code) {
                while (b >= 0 && b != '\n'.code) b = input.read()
                b = input.read()
            } else if (isWhitespace(b)) {
                b = input.read()
            } else break
        }
        if (b < 0 || b < '0'.code || b > '9'.code) return -1
        var n = 0
        while (b in '0'.code..'9'.code) {
            n = n * 10 + (b - '0'.code)
            b = input.read()
            if (b < 0) break
        }
        return n
    }

    private fun isWhitespace(b: Int): Boolean = b == ' '.code || b == '\t'.code || b == '\n'.code || b == '\r'.code

    private fun readFully(input: InputStream, dst: ByteBuffer, tmp: ByteArray, size: Int): Boolean {
        if (dst.remaining() < size) {
            logger.warn("$debugLabel Frame buffer is too small: remaining=${dst.remaining()} required=$size.")
            return false
        }
        var remaining = size
        while (remaining > 0) {
            val want = minOf(tmp.size, remaining, dst.remaining())
            if (want <= 0) {
                logger.warn("$debugLabel Frame buffer overflow guard hit: remaining=$remaining capacity=${dst.capacity()} limit=${dst.limit()}.")
                return false
            }
            val n = input.read(tmp, 0, want)
            if (n < 0) return false
            dst.put(tmp, 0, n)
            remaining -= n
        }
        return true
    }

    private fun applyBrightness(buf: ByteBuffer, size: Int, brightness: Double) {
        val factor = brightness.coerceIn(0.0, 2.0)
        if (factor == 1.0) return

        val savedPosition = buf.position()
        val savedLimit = buf.limit()
        buf.flip()
        for (i in 0 until size) {
            // Brightness scales color only; an RGBA frame's alpha byte is left as decoded
            if (rgba && i and 3 == 3) continue
            val value = ((buf.get(i).toInt() and 0xFF) * factor).toInt().coerceIn(0, 255)
            buf.put(i, value.toByte())
        }
        buf.limit(savedLimit)
        buf.position(savedPosition)
    }

    private fun skipBytes(input: InputStream, size: Long): Boolean {
        var remaining = size
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) return false
                remaining -= 1
            } else remaining -= skipped
        }
        return true
    }
}
