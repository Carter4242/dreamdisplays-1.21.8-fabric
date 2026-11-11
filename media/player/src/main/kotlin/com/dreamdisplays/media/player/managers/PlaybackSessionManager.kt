package com.dreamdisplays.media.player.managers

import com.dreamdisplays.api.media.audio.AudioDspStage
import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.model.FramePixelFormat
import com.dreamdisplays.api.media.player.FrameUploaderFactory
import com.dreamdisplays.api.media.player.GpuTextureRef
import com.dreamdisplays.api.media.player.RenderExecutor
import com.dreamdisplays.api.media.source.LiveStreamUrls
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.media.player.events.PlayerEvents
import com.dreamdisplays.media.player.nativebridge.NativeMedia
import com.dreamdisplays.media.player.nativebridge.lavMayOpen
import com.dreamdisplays.media.player.pipeline.*
import com.dreamdisplays.media.player.process.AlphaVideo
import com.dreamdisplays.media.player.process.FFmpegBinary
import com.dreamdisplays.media.player.process.FFmpegCapabilities
import com.dreamdisplays.media.player.process.GoogleVideoRangeProxy
import com.dreamdisplays.media.player.process.HlsAudioFeeder
import com.dreamdisplays.media.player.process.HwAccelBackend
import com.dreamdisplays.media.player.process.MediaProcess
import com.dreamdisplays.media.player.stream.ActiveStreams
import com.dreamdisplays.media.player.stream.MediaStreamSelector
import com.dreamdisplays.media.player.util.MediaUtil
import com.dreamdisplays.media.player.util.MediaUtil.ms
import com.dreamdisplays.media.player.util.daemon
import com.dreamdisplays.media.player.util.joinSafely
import com.dreamdisplays.media.runtime.security.MediaHostGuard
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages `FFmpeg` session (processes, threads, pipes). Quality switch runs parallel video channel.
 */
internal class PlaybackSessionManager(
    private val debugLabel: String,
    private val clock: PlaybackClock,
    private val events: PlayerEvents,
    private val terminated: AtomicBoolean,

    private val getTextureSize: () -> Pair<Int, Int>,
    private val getBrightness: () -> Double,

    private val onStreamEnd: (stderr: String, normalEos: Boolean) -> Unit,

    private val onQualitySwitchAborted: (appliedAnyway: Boolean) -> Unit = {},

    private val onAudioFailure: (stderr: String) -> Unit = {},

    /**
     * A live audio half broke (an exception in its pump, or FFmpeg exiting non-zero mid-stream). `isCurrent` says
     * whether that half is still the session's, so a late report never restarts its replacement.
     */
    private val onAudioFault: (reason: String, stderr: String, isCurrent: () -> Boolean) -> Unit = { _, _, _ -> },

    private val onAudioTrackSwitchSettled: () -> Unit = {},

    private val renderExecutor: RenderExecutor,

    private val uploaderFactory: FrameUploaderFactory,

    private val gpuYuvActive: Boolean,

    /** Optional per-display acoustics DSP stage; null keeps the legacy distance-gain-only pipeline. */
    audioStage: AudioDspStage? = null,

    /** Whether a video with an alpha channel may play with transparency; read whenever a channel launches. */
    private val transparencyAllowed: () -> Boolean = { true },
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private companion object {
        /** Pacing cadence for replay-only video; PTS still drives pacing, this is only the fallback. */
        const val REPLAY_FPS = 30.0

        /** How many silent-source verdicts to remember; well past any one player's stream ladder. */
        const val SILENT_MEMO_LIMIT = 64

        /**
         * Fastest speed the in-process decoder serves. It decodes and converts every frame, so past this the
         * session uses the FFmpeg process, which thins frames out before converting them ([MediaProcess.speedFps]).
         */
        const val LAV_MAX_TEMPO = 2.0

        /** Audio URLs proven to carry no audio track (process-wide, insertion-bounded). */
        val SILENT_SOURCES: MutableSet<String> = Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(16, 0.75f, false) {
                override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>): Boolean =
                    size > SILENT_MEMO_LIMIT
            }.let { Collections.synchronizedMap(it) },
        )
    }

    private class AudioHalf(val process: Process?, val thread: Thread, val stop: AtomicBoolean)

    /**
     * One decode channel. With an [alpha] plan it always runs an `FFmpeg` process into the JVM pipe as straight-alpha
     * RGBA, since the native and in-process decoders all drop the alpha channel; otherwise the usual pipe is picked.
     */
    private inner class VideoChannel(val alpha: AlphaVideo.Plan? = null) {
        val nativePipe: NativeVideoFramePipe? =
            if (alpha == null && NativeMedia.isAvailable) NativeVideoFramePipe(debugLabel, uploaderFactory, gpuYuvActive)
            else null
        private val jvmPipe: VideoFramePipe? = when {
            nativePipe != null -> null
            alpha != null -> VideoFramePipe(debugLabel, uploaderFactory, MediaProcess.VideoTransport.RAW_RGBA)
            else -> VideoFramePipe(debugLabel, uploaderFactory)
        }
        val pipe: FramePipe = nativePipe ?: jvmPipe!!

        @Volatile
        var process: Process? = null

        @Volatile
        var thread: Thread? = null

        /**
         * True when decoding via in-process libav (only path that supports warm park).
         */
        @Volatile
        var inProcess = false
            private set
        val stop = AtomicBoolean()

        /**
         * Launches video decode into channel's pipe (in-process libav, native, or JVM FFmpeg).
         */
        fun launch(
            ffmpeg: String, streamSet: ActiveStreams, w: Int, h: Int, offsetNanos: Long,
            hwAccel: HwAccelBackend, onFirstFrame: () -> Unit, onEos: (String, Boolean) -> Unit,
            getAudioClock: () -> Long = pacingClock, parkFlag: AtomicBoolean? = null,
            presentPreview: Boolean = true, tolerateLateness: Boolean = true,
        ) {
            // SSRF guard for the in-process libav path, which bypasses MediaProcess.baseCommand
            val safeUrl = MediaHostGuard.resolveSafeUrl(streamSet.currentVideo.url)
            // One sanitized rate for both FFmpeg's -r and the pipe's timestamp arithmetic, so the
            // two can never disagree (see MediaProcess.outputFps).
            val sourceFps = MediaProcess.outputFps(streamSet.currentVideo.fps)
            // A sped-up process session keeps fewer frames per content second (FFmpeg drops the rest)
            val fps = MediaProcess.speedFps(sourceFps, tempo)
            val dropToFps = fps < sourceFps
            // The in-process decoder seeks through the same libav demuxer that gets this container
            // wrong, and unlike the process path it has no way to fall back to decoding forward.
            val seekByDecoding = streamSet.currentVideo.seekByDecoding
            // A live relay stream runs at the live edge (no jitter buffer, see liveEdge) on the FFmpeg process:
            // the in-process decoder only speaks http(s)
            val liveEdge = LiveStreamUrls.isLive(safeUrl)
            if (alpha != null) {
                val vp = MediaProcess.buildAlphaVideo(
                    ffmpeg, safeUrl, w, h, offsetNanos, alpha.decoder, fps, seekByDecoding = seekByDecoding,
                )
                val vt = jvmPipe!!.start(
                    proc = vp, w = w, h = h, seekOffsetNanos = offsetNanos, sourceFps = fps,
                    stopFlag = stop, terminated = terminated, getAudioClock = getAudioClock,
                    onFirstFrame = onFirstFrame, getBrightness = getBrightness, onEos = onEos,
                    parkFlag = parkFlag, presentPreview = presentPreview, tolerateLateness = tolerateLateness,
                )
                process = vp; thread = vt; return
            }
            val lavThread = if (nativePipe != null && NativeMedia.lavAvailable && !seekByDecoding && !liveEdge &&
                !GoogleVideoRangeProxy.needsProxy(safeUrl) && lavServes(tempo) && lavMayOpen(safeUrl)
            ) {
                nativePipe.startInProcess(
                    url = safeUrl, w = w, h = h, seekOffsetNanos = offsetNanos,
                    sourceFps = sourceFps, hwAccel = hwAccel, stopFlag = stop, terminated = terminated,
                    getAudioClock = getAudioClock, onFirstFrame = onFirstFrame,
                    getBrightness = getBrightness, onEos = onEos, parkFlag = parkFlag,
                    presentPreview = presentPreview, tolerateLateness = tolerateLateness,
                )
            } else null
            if (lavThread != null) {
                process = null; thread = lavThread; inProcess = true; return
            }
            if (nativePipe != null) {
                val args = MediaProcess.videoArgs(
                    ffmpeg, safeUrl, w, h, offsetNanos, hwAccel, MediaProcess.VideoTransport.RAW_NV12, fps,
                    alreadyResolved = true, seekByDecoding = seekByDecoding, dropToFps = dropToFps,
                )
                val vt = nativePipe.start(
                    args = args, w = w, h = h, seekOffsetNanos = offsetNanos, sourceFps = fps,
                    stopFlag = stop, terminated = terminated, getAudioClock = getAudioClock,
                    onFirstFrame = onFirstFrame, getBrightness = getBrightness, onEos = onEos,
                    parkFlag = parkFlag, presentPreview = presentPreview, tolerateLateness = tolerateLateness,
                    liveEdge = liveEdge,
                ) ?: throw IOException("Native FFmpeg session failed to start")
                process = null; thread = vt; return
            }
            val vp = MediaProcess.buildVideo(
                ffmpeg, safeUrl, w, h, offsetNanos, hwAccel, fps,
                alreadyResolved = true, seekByDecoding = seekByDecoding, dropToFps = dropToFps,
            )
            val vt = jvmPipe!!.start(
                proc = vp, w = w, h = h, seekOffsetNanos = offsetNanos, sourceFps = fps,
                stopFlag = stop, terminated = terminated, getAudioClock = getAudioClock,
                onFirstFrame = onFirstFrame, getBrightness = getBrightness, onEos = onEos,
                parkFlag = parkFlag, presentPreview = presentPreview, tolerateLateness = tolerateLateness,
                liveEdge = liveEdge,
            )
            process = vp; thread = vt
        }

        /** Captures this channel's live LAV packet-ring snapshot, when one exists. */
        fun snapshotCache(positionNanos: Long): ByteArray? = nativePipe?.lavCacheSnapshot(positionNanos)

        /** Seeks the in-process LAV decoder without replacing this channel. */
        fun seekInProcess(offsetNanos: Long, startGate: (() -> Boolean)?, onFirstFrame: () -> Unit): Boolean =
            inProcess && nativePipe?.seekInProcess(offsetNanos, startGate, onFirstFrame) == true

        /** Stops the decode and joins the reader thread (blocking). Must not run on the render thread. */
        fun teardownProcess() {
            stop.set(true)
            nativePipe?.kill()
            MediaProcess.gracefulDestroy(process)
            thread?.let { joinSafely(it) }
            nativePipe?.release()
        }
    }

    private val audio = AudioSink(debugLabel)

    /**
     * Current audio-track switch; bumped by every restart of the audio half (seek, restart, speed, stop), so a switch
     * still warming up from the old position / speed never goes live over the restarted one.
     */
    private val audioSwitchGeneration = AtomicLong()

    @Volatile
    private var liveSession = false

    @Volatile
    private var audioFeeder: HlsAudioFeeder? = null

    /**
     * Playback speed of this session's processes (content time per real time). Pitch-preserving: the audio
     * process time-stretches ([MediaProcess.tempoFilter]) and every clock reports content time, so the picture,
     * paced against those clocks, follows at the same speed. Change it through [setTempo].
     */
    @Volatile
    var tempo: Double = 1.0
        private set

    /**
     * Sets the speed the next started session (start, seek, restart) plays at; the running processes keep theirs,
     * so the caller restarts them at the current position.
     */
    fun setTempo(value: Double) {
        if (value == tempo) return
        tempo = value
        clock.setRate(value)
        audioSwitchGeneration.incrementAndGet()
        // Pre-warmed tracks were spawned at the old speed
        audioWarmPool.invalidateAll()
    }

    /** True when the in-process decoder may serve a session at [tempo] (see [LAV_MAX_TEMPO]). */
    private fun lavServes(tempo: Double): Boolean = tempo <= LAV_MAX_TEMPO

    /** [pacingClockNanos] as the pipes see it, carrying the playback speed for their sleeps. */
    private val pacingClock = RatedClock(::pacingClockNanos) { tempo }

    @Throws(IOException::class)
    private fun buildAudioProcess(
        ffmpeg: String, streamSet: ActiveStreams, offsetNanos: Long, stopFlag: AtomicBoolean,
        tempo: Double = 1.0,
    ): Process? {
        val url = streamSet.currentAudio.url
        if (url in SILENT_SOURCES) {
            silentSession = true
            audioFeeder = null
            return null
        }
        if (liveSession && HlsAudioFeeder.supports(url)) {
            // Same SSRF gate the FFmpeg URL path applies in MediaProcess.baseCommand
            val safeUrl = MediaHostGuard.resolveSafeUrl(url)
            val proc = MediaProcess.buildAudioPiped(ffmpeg, AudioSink.SAMPLE_RATE)
            // A session that ended at an ad splice is continued from that very segment, not from the live edge
            val resumeSeq = audioFeeder?.splicedAtSeq ?: -1L
            audioFeeder = HlsAudioFeeder(safeUrl, proc.outputStream, stopFlag, terminated, debugLabel, resumeSeq)
                .also { it.start() }
            return proc
        }
        audioFeeder = null
        return MediaProcess.buildAudio(
            ffmpeg, url, offsetNanos, AudioSink.SAMPLE_RATE,
            seekByDecoding = streamSet.currentAudio.seekByDecoding, tempo = tempo,
        )
    }

    private fun audioOriginKnown(): Boolean = !liveSession && audioFeeder == null

    fun audioSourceGone(): Boolean = audioFeeder?.sourceGone == true

    /**
     * The video pipeline in use, for hitch reports: `lav` (in-process libav), `native` (native NV12 / GPU-YUV),
     * `ffmpeg` (JVM pipe), `ffmpeg-rgba` (alpha), `none`; with `-gpuyuv` when planes are converted on the GPU.
     */
    fun pipelineLabel(): String {
        val channel = active ?: return "none"
        val base = when {
            channel.alpha != null -> "ffmpeg-rgba"
            channel.inProcess -> "lav"
            channel.nativePipe != null -> "native"
            else -> "ffmpeg"
        }
        return if (gpuYuvActive && channel.alpha == null) "$base-gpuyuv" else base
    }

    fun audioSpliced(): Boolean = (audioFeeder?.splicedAtSeq ?: -1L) >= 0L

    @Volatile
    private var silentSession = false

    /** Marks URL as silent source (no process spawned, returns true on first mark). */
    fun markSourceSilent(audioUrl: String): Boolean {
        silentSession = true
        return SILENT_SOURCES.add(audioUrl)
    }

    private val parkFlag = AtomicBoolean(false)

    private val audioWarmPool = AudioTrackWarmPool(
        debugLabel = debugLabel,
        terminated = terminated,
        positionNanos = { clock.currentTime() },
        eligible = { isPlaying && !terminated.get() && !parkFlag.get() && audioOriginKnown() && tempo == 1.0 },
    )

    /** Declares the audio tracks to keep pre-warmed; the one currently playing must not be among them. */
    fun setWarmAudioTracks(tracks: List<WarmTrack>) =
        audioWarmPool.setTracks(tracks.filter { it.url !in SILENT_SOURCES })

    init {
        audio.setParkFlag(parkFlag)
        audio.setDspStage(audioStage)
    }

    /** Fault callback for the audio half stopped by [aStop]. */
    private fun faultFor(aStop: AtomicBoolean): (String, String) -> Unit =
        { reason, stderr -> onAudioFault(reason, stderr) { audioHalf?.stop === aStop && !aStop.get() } }

    /** Installs (or clears) the acoustics DSP stage after construction; see [MediaPlayer.attachAudioStage]. */
    fun setAudioStage(stage: AudioDspStage?) = audio.setDspStage(stage)

    private val switchLock = Any()

    @Volatile
    private var active: VideoChannel? = null

    @Volatile
    private var incoming: VideoChannel? = null

    @Volatile
    private var incomingGeneration: Long = 0L

    @Volatile
    private var audioHalf: AudioHalf? = null
    private val noFrames = AtomicLong(0)

    private sealed interface Phase {
        data object Stopped : Phase
        data object Active : Phase

        class Bridging(val liveEdgeNanos: Long) : Phase
        class Parked(val frozenNanos: Long, val sinceNanos: Long) : Phase
    }

    private val phase = AtomicReference<Phase>(Phase.Stopped)

    val isPlaying: Boolean get() = phase.get() !is Phase.Stopped

    private val bridging: Boolean get() = phase.get() is Phase.Bridging

    private val bridgeCeilingNanos: Long
        get() = (phase.get() as? Phase.Bridging)?.liveEdgeNanos ?: Long.MAX_VALUE

    /**
     * True when the live channel decodes straight-alpha RGBA frames, so the display has to draw them with an RGBA
     * texture and a blending render type. Kept across [stop] (and reset by the next launch) so the texture does not
     * flip back and forth between a session and its restart.
     */
    @Volatile
    var outputAlpha = false
        private set

    private val opaqueAlphaLogged = AtomicBoolean()

    /** The alpha decode plan for [streamSet]'s video, or null to play it on the ordinary opaque path. */
    private fun alphaPlan(ffmpeg: String, streamSet: ActiveStreams): AlphaVideo.Plan? {
        val video = streamSet.currentVideo
        if (!video.hasAlpha) return null
        val allowed = transparencyAllowed()
        val plan = AlphaVideo.plan(video, allowed) { FFmpegCapabilities.hasDecoder(ffmpeg, it) }
        if (plan == null && opaqueAlphaLogged.compareAndSet(false, true)) {
            logger.info(
                "$debugLabel Video has an alpha channel but plays opaque ({}).",
                if (!allowed) "transparency is off here" else "this FFmpeg has no ${AlphaVideo.alphaDecoder(video.codec)
                    ?: "alpha-capable"} decoder for codec ${video.codec}",
            )
        }
        return plan
    }

    /** True once the first decoded frame of the live channel is ready for GPU upload. */
    fun textureFilled(): Boolean = active?.pipe?.textureFilled() == true

    /** Uploads the latest live frame to [texture]. Returns true if a frame was uploaded. Render thread only. */
    fun updateFrame(texture: GpuTextureRef, w: Int, h: Int): Boolean = active?.pipe?.updateFrame(texture, w, h) == true

    /** Uploads the latest live planar I420 frame into the three plane textures. Returns true if uploaded. */
    fun updateFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, w: Int, h: Int): Boolean =
        active?.pipe?.updateFramePlanar(y, u, v, w, h) == true

    /** True while an incoming (quality-switch) channel is warming up in parallel. */
    fun hasIncoming(): Boolean = incoming != null

    /** Uploads the latest incoming-channel frame to [texture] (the staged texture). Returns true if uploaded. */
    fun updateIncomingFrame(texture: GpuTextureRef, w: Int, h: Int): Boolean =
        incoming?.pipe?.updateFrame(texture, w, h) == true

    /** Uploads the latest incoming-channel planar I420 frame into the staged plane textures. */
    fun updateIncomingFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, w: Int, h: Int): Boolean =
        incoming?.pipe?.updateFramePlanar(y, u, v, w, h) == true

    /** Discards the live channel's ready frame. Call when stopping or seeking. */
    fun clearFrame() = active?.pipe?.clear() ?: Unit

    /** Sets the effective volume (user volume * distance attenuation). */
    fun setVolume(volume: Double) {
        audio.currentVolume = volume
    }

    /** The gain the audio sink applies now. */
    val sinkVolume: Double get() = audio.currentVolume

    /** When PCM last reached the output line ([System.nanoTime], 0 before any). */
    val lastPcmOutNanos: Long get() = audio.lastPcmOutNanos

    /** The display audio line's output latency, or null while none plays (see [AudioSink.outputLatency]). */
    fun audioOutputLatency(): com.dreamdisplays.media.player.pipeline.AudioOutputLatency? = audio.outputLatency()

    /** Taps the PCM this player writes (see [com.dreamdisplays.media.player.pipeline.PlayedPcmTap]); null removes it. */
    fun setPcmTap(tap: com.dreamdisplays.media.player.pipeline.PlayedPcmTap?) {
        audio.pcmTap = tap
    }

    /** Audio-side state for a silent-audio report: phase, the audio half's process / thread, feeder, and sink. */
    fun describeAudio(): String {
        val half = audioHalf
        val proc = half?.process
        val procState = when {
            half == null -> "no-half"
            proc == null -> "no-process"
            proc.isAlive -> "alive"
            else -> "exited(${runCatching { proc.exitValue() }.getOrNull()})"
        }
        return "phase=${phase.get()::class.simpleName} parked=${parkFlag.get()} clockRunning=${clock.isRunning} " +
                "tempo=$tempo silent=$silentSession incoming=${incoming != null} process=$procState " +
                "thread=${half?.thread?.state} stopFlag=${half?.stop?.get()} feeder=${audioFeeder != null} " +
                "pipeline=${pipelineLabel()} ${audio.describe()}"
    }

    /** True when this session should be putting sound on the line now: active, running, not a silent source. */
    fun audioExpected(): Boolean =
        phase.get() is Phase.Active && !parkFlag.get() && clock.isRunning && !silentSession &&
                incoming == null

    /** Timestamp of the live channel's last decoded video frame; read by [StreamWatchdog]. */
    val lastFrameNanos: AtomicLong get() = active?.pipe?.lastFrameReceivedNanos ?: noFrames

    @Volatile
    private var popoutSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null

    @Volatile
    private var previewSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null

    /** Routes raw frames to the popout window. Null = no popout active. */
    var popoutFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = popoutSink
        set(value) {
            popoutSink = value
            updateRawFrameSink()
        }

    /** Routes raw frames to the display menu preview when the main texture is GPU-YUV only. */
    var previewFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = previewSink
        set(value) {
            previewSink = value
            updateRawFrameSink()
        }

    private fun updateRawFrameSink() {
        val popout = popoutSink
        val preview = previewSink
        val sink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? =
            if (popout == null && preview == null) null else { buf, w, h, format ->
                val pos = buf.position()
                val limit = buf.limit()
                popout?.invoke(buf, w, h, format)
                buf.position(pos).limit(limit)
                preview?.invoke(buf, w, h, format)
                buf.position(pos).limit(limit)
            }
        // The raw sink follows the live channel; it is re-applied to the new live channel on promotion
        active?.pipe?.popoutFrameSink = sink
    }

    /** Stops any running session, then launches new FFmpeg processes for [streamSet] starting at [offsetNanos]. */
    fun start(
        streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int, hwAccel: HwAccelBackend, live: Boolean = false,
        onFirstFrame: () -> Unit = {},
    ) {
        stop()
        if (terminated.get()) return
        liveSession = live
        if (live) setTempo(1.0) // A live edge cannot be played faster or slower
        audio.liveEdge = live && LiveStreamUrls.isLive(streamSet.currentAudio.url)
        silentSession = false

        val ffmpeg = FFmpegBinary.getPath() ?: run {
            logger.error("$debugLabel FFmpeg binary not available.")
            events.onError(DreamMediaException.Decode("FFmpeg binary not available", isFatal = true)); return
        }
        clock.reset(offsetNanos)

        parkFlag.set(false)
        val alpha = alphaPlan(ffmpeg, streamSet)
        val (w, h) = targetDims(streamSet, lastQuality, alpha != null)
        val channel = VideoChannel(alpha)
        val tempo = this.tempo
        try {
            val firstVideoFrame = CountDownLatch(1)
            val aStop = AtomicBoolean()
            val ap = buildAudioProcess(ffmpeg, streamSet, offsetNanos, aStop, tempo)
            try {
                channel.launch(ffmpeg, streamSet, w, h, offsetNanos, hwAccel, onFirstFrame = {
                    clock.markFirstFrame()
                    firstVideoFrame.countDown()
                    onFirstFrame()
                }, onEos = onStreamEnd, parkFlag = parkFlag)
            } catch (e: Exception) {
                aStop.set(true)
                MediaProcess.gracefulDestroy(ap)
                throw e
            }
            active = channel
            outputAlpha = alpha != null
            audioHalf = ap?.let {
                AudioHalf(
                    it,
                    audio.start(
                        it, terminated, aStop,
                        contentStartNanos = offsetNanos, originKnown = audioOriginKnown(),
                        startGate = firstVideoFrame, onUnexpectedEnd = onAudioFailure, onFault = faultFor(aStop), tempo = tempo,
                    ),
                    aStop,
                )
            }
            updateRawFrameSink()
            phase.set(Phase.Active)
        } catch (e: IOException) {
            logger.error("$debugLabel Failed to start FFmpeg", e)
            events.onError(DreamMediaException.Decode("Failed to start FFmpeg: ${e.message}", e))
        }
    }

    /** Seamless in-place seek: silences old audio, freezes picture on last frame, warms new stream at offset. */
    fun beginSeek(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int, hwAccel: HwAccelBackend): Boolean {
        if (!isPlaying || terminated.get() || parkFlag.get()) return false
        // The shadows hold PCM for the position we are leaving; the refresh pass re-warms at the new one.
        audioWarmPool.invalidateAll()
        // The seek reopens the audio on the current track (a pending switch's included) at the new position
        audioSwitchGeneration.incrementAndGet()
        if (bridging) return false
        synchronized(switchLock) { if (incoming != null) return false }
        val old = active ?: return false
        val ffmpeg = FFmpegBinary.getPath() ?: return false
        val alpha = alphaPlan(ffmpeg, streamSet)
        val (w, h) = targetDims(streamSet, lastQuality, alpha != null)
        val seekStartedNanos = System.nanoTime()
        val tempo = this.tempo

        if (alpha == null && old.inProcess && old.nativePipe?.expectedW == w && old.nativePipe.expectedH == h &&
            lavServes(tempo)
        ) {
            val firstVideoFrame = CountDownLatch(1)
            val aStop = AtomicBoolean()
            val ap = try {
                buildAudioProcess(ffmpeg, streamSet, offsetNanos, aStop, tempo)
            } catch (e: IOException) {
                logger.error("$debugLabel Failed to start seek audio session.", e)
                return false
            }
            val oldAudio = audioHalf
            audioHalf = null
            oldAudio?.stop?.set(true)
            audio.stop()
            clock.reset(offsetNanos)

            // The new audio process decodes its first PCM while the video pre-rolls; playout restarts once both
            // are ready (bytes waiting in its pipe), not after a fixed cushion. A dead or absent process never holds it.
            val soundReady: () -> Boolean = {
                ap == null || !ap.isAlive || runCatching { ap.inputStream.available() > 0 }.getOrDefault(true)
            }
            val seeked = old.seekInProcess(offsetNanos, soundReady) {
                clock.markFirstFrame()
                firstVideoFrame.countDown()
                logSeekDone(offsetNanos, seekStartedNanos, "in-place")
            }
            if (seeked) {
                audioHalf = ap?.let {
                    AudioHalf(
                        it,
                        audio.start(
                            it, terminated, aStop,
                            contentStartNanos = offsetNanos, originKnown = audioOriginKnown(),
                            startGate = firstVideoFrame, onUnexpectedEnd = onAudioFailure, onFault = faultFor(aStop), tempo = tempo,
                        ),
                        aStop,
                    )
                }
                updateRawFrameSink()
                discardHalvesAsync(null, oldAudio)
                return true
            }
            logger.warn("$debugLabel In-place seek rejected by the pipe; falling back to a channel reopen.")
            MediaProcess.gracefulDestroy(ap)
            discardHalvesAsync(null, oldAudio)
        } else {
            logger.warn(
                "$debugLabel Seek can't go in place (inProcess=${old.inProcess}, " +
                        "pipe=${old.nativePipe?.expectedW}x${old.nativePipe?.expectedH}, target=${w} x $h); " +
                        "reopening the channel."
            )
        }

        // Freeze the picture and cut the sound right away: the old consumer stops presenting within one
        // poll (the GPU texture keeps the last frame on screen), and the clock parks at the target so the
        // UI reads the seeked position immediately.
        old.stop.set(true)
        val oldAudio = audioHalf
        audioHalf = null
        oldAudio?.stop?.set(true)
        audio.stop()
        clock.reset(offsetNanos)

        val channel = VideoChannel(alpha)
        try {
            val firstVideoFrame = CountDownLatch(1)
            val aStop = AtomicBoolean()
            val ap = buildAudioProcess(ffmpeg, streamSet, offsetNanos, aStop, tempo)
            try {
                channel.launch(ffmpeg, streamSet, w, h, offsetNanos, hwAccel, onFirstFrame = {
                    clock.markFirstFrame()
                    firstVideoFrame.countDown()
                    logSeekDone(offsetNanos, seekStartedNanos, "reopen")
                }, onEos = onStreamEnd, parkFlag = parkFlag)
            } catch (e: Exception) {
                aStop.set(true)
                MediaProcess.gracefulDestroy(ap)
                throw e
            }
            synchronized(switchLock) { active = channel }
            outputAlpha = alpha != null
            audioHalf = ap?.let {
                AudioHalf(
                    it,
                    audio.start(
                        it, terminated, aStop,
                        contentStartNanos = offsetNanos, originKnown = audioOriginKnown(),
                        startGate = firstVideoFrame, onUnexpectedEnd = onAudioFailure, onFault = faultFor(aStop), tempo = tempo,
                    ),
                    aStop,
                )
            }
            updateRawFrameSink()
            // The old halves are already stopping; finish dismantling them off-thread so the new decode
            // never waits on process destruction or reader joins.
            discardHalvesAsync(old, oldAudio)
            return true
        } catch (e: IOException) {
            logger.error("$debugLabel Failed to start seek session.", e)
            // Leave the old (stopping) channel as active: the caller's full restart will tear it down.
            discardHalvesAsync(null, oldAudio)
            return false
        }
    }

    /** One line per seek with how long playback took to resume, for sync tuning (see `[dd-sync]` client logs). */
    /** How long the last seek (in place or reopen) took to reach its first frame, in ms; -1 before one has. */
    @Volatile
    var lastSeekMs: Long = -1L
        private set

    /** Whether the last seek went in place (false after a reopen). */
    @Volatile
    var lastSeekInPlace: Boolean = false
        private set

    /** True while the live channel decodes in-process, the precondition for an in-place seek. */
    val seeksInPlace: Boolean get() = active?.inProcess == true && !parkFlag.get() && !outputAlpha && lavServes(tempo)

    private fun logSeekDone(offsetNanos: Long, startedNanos: Long, how: String) {
        lastSeekInPlace = how == "in-place"
        lastSeekMs = (System.nanoTime() - startedNanos) / 1_000_000
        logger.info(
            "[dd-sync] {} seek to {} ms took {} ms ({})",
            debugLabel.take(8), offsetNanos / 1_000_000, (System.nanoTime() - startedNanos) / 1_000_000, how,
        )
    }

    /** Replaces audio half of playing session with fresh process on same audio URL, leaving video unchanged. */
    fun restartAudio(streamSet: ActiveStreams, offsetNanos: Long): Boolean {
        if (!isPlaying || terminated.get() || parkFlag.get()) return false
        audioSwitchGeneration.incrementAndGet()
        if (bridging) return false
        synchronized(switchLock) { if (incoming != null) return false }
        val ffmpeg = FFmpegBinary.getPath() ?: return false
        val oldAudio = audioHalf
        audioHalf = null
        oldAudio?.stop?.set(true)
        audio.stop()
        val aStop = AtomicBoolean()
        val tempo = this.tempo
        val ap = try {
            buildAudioProcess(ffmpeg, streamSet, offsetNanos, aStop, tempo)
        } catch (e: IOException) {
            logger.error("$debugLabel Failed to start replacement audio process.", e)
            discardHalvesAsync(null, oldAudio)
            return false
        }
        discardHalvesAsync(null, oldAudio)
        if (ap == null) {
            // The source has no audio track: there is nothing to restart, and the session is healthy
            logger.debug("$debugLabel Audio restart skipped: this source plays silently.")
            return true
        }
        val originKnown = audioOriginKnown()
        val at = audio.start(
            ap, terminated, aStop,
            contentStartNanos = offsetNanos, originKnown = originKnown,
            startGate = null, onUnexpectedEnd = onAudioFailure, onFault = faultFor(aStop),
            catchUp = if (originKnown) AudioSink.CatchUp(offsetNanos, { clock.currentTime() }, tempo) else null,
            tempo = tempo,
        )
        audioHalf = AudioHalf(ap, at, aStop)
        logger.debug("$debugLabel Audio half restarted in place at ${offsetNanos / 1_000_000} ms.")
        return true
    }

    private val audioSwitchWarmupTimeoutNanos = 15_000_000_000L

    /** Seamless audio-track switch for seekable content: spawns new track's FFmpeg on background thread, then swaps. */
    fun beginAudioTrackSwitch(streamSet: ActiveStreams): Boolean {
        if (!isPlaying || terminated.get() || parkFlag.get()) return false
        if (bridging) return false
        synchronized(switchLock) { if (incoming != null) return false }
        val ffmpeg = FFmpegBinary.getPath() ?: return false
        val generation = audioSwitchGeneration.incrementAndGet()
        daemon({ runAudioTrackSwitch(ffmpeg, streamSet, generation) }, "MediaPlayer-audio-switch").start()
        return true
    }

    private fun audioSwitchStillCurrent(generation: Long): Boolean =
        audioSwitchGeneration.get() == generation && !terminated.get() && isPlaying && !parkFlag.get()

    private class PreparedAudioLine(
        val process: Process,
        val stop: AtomicBoolean,
        val contentStartNanos: Long,
        val tempo: Double = 1.0,
    )

    /**
     * Claims the pre-warmed line for the target track, or null when none is pooled. Its PCM starts at
     * the position it was spawned at rather than at the playhead, which is exactly what the catch-up
     * skip in [AudioSink.startSwitch] exists to absorb — so this only applies where that skip runs
     * ([audioOriginKnown]); a live join anchors on its own PES PTS and must always start fresh.
     */
    private fun takeWarmAudioLine(streamSet: ActiveStreams): PreparedAudioLine? {
        if (!audioOriginKnown() || tempo != 1.0) return null
        val w = audioWarmPool.take(streamSet.currentAudio.url) ?: return null
        logger.debug(
            "$debugLabel Audio-track switch served from the warm pool " +
                    "(spawned at ${w.contentStartNanos / 1_000_000} ms)."
        )
        return PreparedAudioLine(w.process, w.stop, w.contentStartNanos)
    }

    private fun coldStartAudioLine(
        ffmpeg: String, streamSet: ActiveStreams, generation: Long,
    ): PreparedAudioLine? {
        val seekNanos = clock.currentTime().coerceAtLeast(0L)
        val aStop = AtomicBoolean()
        val tempo = this.tempo
        val ap = try {
            buildAudioProcess(ffmpeg, streamSet, seekNanos, aStop, tempo)
        } catch (e: IOException) {
            logger.error("$debugLabel Failed to start replacement audio-track process.", e)
            return null
        } ?: return null
        // Old track keeps playing while the replacement warms up; wait for its first stdout bytes
        val deadline = System.nanoTime() + audioSwitchWarmupTimeoutNanos
        var ready = false
        while (System.nanoTime() < deadline && audioSwitchStillCurrent(generation)) {
            try {
                if (ap.inputStream.available() > 0) {
                    ready = true
                    break
                }
            } catch (_: IOException) {
                break
            }
            if (!ap.isAlive) break
            try {
                Thread.sleep(15)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (!ready) {
            aStop.set(true)
            MediaProcess.gracefulDestroy(ap)
            if (audioSwitchGeneration.get() == generation) {
                logger.warn("$debugLabel Audio-track switch delivered no PCM in time; keeping the current track.")
            }
            return null
        }
        return PreparedAudioLine(ap, aStop, seekNanos, tempo)
    }

    private fun runAudioTrackSwitch(ffmpeg: String, streamSet: ActiveStreams, generation: Long) {
        val line = takeWarmAudioLine(streamSet) ?: coldStartAudioLine(ffmpeg, streamSet, generation)
        if (line == null || !audioSwitchStillCurrent(generation)) {
            line?.let {
                it.stop.set(true)
                MediaProcess.gracefulDestroy(it.process)
            }
            onAudioTrackSwitchSettled()
            return
        }
        val ap = line.process
        val aStop = line.stop
        val seekNanos = line.contentStartNanos
        // Seamless swap: the replacement line pre-buffers (silent) while the OLD one keeps playing,
        // then flips in with no audible gap (see [AudioSink.startSwitch]) — this removes the silence
        // the old stop-then-start swap left while the new line opened and filled. A catch-up skip
        // drops the span the new track fell behind the live clock so it joins already lip-synced. The
        // HLS-feeder path (live) carries its own exact PES-PTS anchor instead, so no byte skip there.
        val originKnown = audioOriginKnown()
        val catchUp = if (originKnown) AudioSink.CatchUp(seekNanos, { clock.currentTime() }, line.tempo) else null
        val oldAudio = audioHalf
        audio.startSwitch(
            ap, terminated, aStop,
            contentStartNanos = seekNanos, originKnown = originKnown, catchUp = catchUp,
            shouldPromote = { audioSwitchStillCurrent(generation) },
            onPromoted = {
                // Runs on the switch thread the instant the new line goes live: take ownership and
                // retire the old half. The promoted line publishes its own content origin, so the
                // master clock picks the new session up exactly, with no anchoring guess.
                audioHalf = AudioHalf(ap, Thread.currentThread(), aStop)
                oldAudio?.stop?.set(true)
                discardHalvesAsync(null, oldAudio)
                logger.debug("$debugLabel Audio track switched seamlessly at ${seekNanos / 1_000_000} ms.")
                // A stop() that raced the warm-up couldn't know this half yet: honor it retroactively
                if (terminated.get() || !isPlaying) {
                    aStop.set(true)
                    audio.stop()
                    MediaProcess.gracefulDestroy(ap)
                }
                onAudioTrackSwitchSettled()
            },
            onAborted = {
                // The replacement never went live; keep the current track and drop the new process
                aStop.set(true)
                MediaProcess.gracefulDestroy(ap)
                onAudioTrackSwitchSettled()
            },
            onUnexpectedEnd = onAudioFailure, onFault = faultFor(aStop),
            tempo = line.tempo,
        )
    }

    private fun discardHalvesAsync(video: VideoChannel?, audioHalf: AudioHalf?) {
        daemon({
            audioHalf?.let {
                MediaProcess.gracefulDestroy(it.process)
                joinSafely(it.thread)
            }
            video?.let { ch ->
                ch.teardownProcess()
                renderExecutor.execute { ch.pipe.cleanup() }
            }
        }, "MediaPlayer-session-discard").start()
    }

    /** Starts cached replay video alone (no audio, no network) so reappearing display shows frames instantly. */
    fun startReplayVideoOnly(
        snapshot: ByteArray,
        resumeNanos: Long,
        liveEdgeNanos: Long,
    ): Boolean {
        stop()
        if (terminated.get()) return false

        // Both replay and the warming-up live channel pace on this shared clock, clamped to the live
        // edge: replay plays toward it and the live channel's first frame lands exactly there.
        clock.reset(resumeNanos)
        phase.set(Phase.Bridging(liveEdgeNanos.coerceAtLeast(resumeNanos)))

        val (w, h) = targetDims(null)
        val channel = VideoChannel()
        val pipe = channel.nativePipe ?: run { phase.set(Phase.Stopped); return false }
        // Replay snapshots only ever come from the in-process decoder, which has no alpha
        outputAlpha = false
        val vt = pipe.startReplay(
            snapshot = snapshot, w = w, h = h, resumeNanos = resumeNanos, sourceFps = REPLAY_FPS,
            stopFlag = channel.stop, terminated = terminated, getAudioClock = pacingClock,
            onFirstFrame = { clock.markFirstFrame() },
            getBrightness = getBrightness,
            onEos = { _, normalEos ->
                logger.debug("$debugLabel [reappear] replay-only video reached end (normalEos=$normalEos), holding last frame.")
            },
        ) ?: run { phase.set(Phase.Stopped); return false }
        channel.thread = vt
        active = channel
        updateRawFrameSink()
        logger.debug(
            "$debugLabel [reappear] replay-only video started $w x $h resume=${ms(resumeNanos)} ms " +
                    "edge=${ms(liveEdgeNanos)} ms.",
        )
        return true
    }

    /** Attaches live source while replay holds screen: live channel warms up as incoming channel in parallel. */
    fun attachLiveAfterReplay(
        streamSet: ActiveStreams, liveOffsetNanos: Long, lastQuality: Int, hwAccel: HwAccelBackend,
    ): Boolean {
        if (active == null || !isPlaying || terminated.get()) return false
        val ffmpeg = FFmpegBinary.getPath() ?: return false
        liveSession = false
        val alpha = alphaPlan(ffmpeg, streamSet)
        val (w, h) = targetDims(streamSet, lastQuality, alpha != null)

        val channel = VideoChannel(alpha)
        var generation = 0L
        val previous = synchronized(switchLock) {
            generation = incomingGeneration + 1
            incomingGeneration = generation
            incoming.also { incoming = channel }
        }
        previous?.let { discardChannelAsync(it) }
        if (terminated.get()) {
            synchronized(switchLock) { if (incoming === channel && incomingGeneration == generation) incoming = null }
            discardChannelBlocking(channel)
            return false
        }

        return try {
            val firstLiveFrame = CountDownLatch(1)
            channel.launch(
                ffmpeg,
                streamSet,
                w,
                h,
                liveOffsetNanos,
                hwAccel,
                onFirstFrame = {
                    // Live reached the edge: re-anchor the clock there (matching the audio offset), lift the
                    // bridge clamp, then open the live audio gate
                    clock.rebaseTo(liveOffsetNanos)
                    phase.updateAndGet { if (it is Phase.Bridging) Phase.Active else it }
                    firstLiveFrame.countDown()
                    logger.debug(
                        "$debugLabel [reappear] live channel presented first frame; handoff at ${ms(liveOffsetNanos)} ms."
                    )
                },
                onEos = { stderr, normalEos -> handoffEos(channel, generation, stderr, normalEos) },
                parkFlag = parkFlag
            )

            val audioUrl = streamSet.currentAudio.url
            val ap =
                if (audioUrl in SILENT_SOURCES) null
                else MediaProcess.buildAudio(
                    ffmpeg, audioUrl, liveOffsetNanos, AudioSink.SAMPLE_RATE,
                    seekByDecoding = streamSet.currentAudio.seekByDecoding, tempo = tempo,
                )
            // This path builds its process directly rather than through buildAudioProcess, so the
            // previous session's feeder would otherwise linger and be consulted for A / V anchoring.
            audioFeeder = null
            silentSession = ap == null
            audioHalf = if (ap == null) null else {
                // Live audio joins at the edge, gated on the first live frame (as in start())
                val aStop = AtomicBoolean()
                val at = audio.start(
                    ap, terminated, aStop,
                    contentStartNanos = liveOffsetNanos, originKnown = true,
                    startGate = firstLiveFrame, onUnexpectedEnd = onAudioFailure, onFault = faultFor(aStop), tempo = tempo,
                )
                AudioHalf(ap, at, aStop)
            }

            if (terminated.get()) {
                synchronized(switchLock) {
                    if (incoming === channel && incomingGeneration == generation) incoming = null
                }
                discardChannelBlocking(channel)
                return false
            }
            logger.debug("$debugLabel [reappear] live attached $w x $h at ${ms(liveOffsetNanos)} ms, warming up...")
            true
        } catch (e: IOException) {
            logger.error("$debugLabel [reappear] failed to attach live after replay.", e)
            val wasCurrent = synchronized(switchLock) {
                if (incoming === channel && incomingGeneration == generation) {
                    incoming = null; true
                } else false
            }
            discardChannelBlocking(channel)
            if (wasCurrent) onQualitySwitchAborted(false)
            false
        }
    }

    /** Captures live channel's entire encoded-packet cache (rolling window) for later replay. */
    fun captureVideoCacheSnapshot(): ByteArray? {
        val channel = if (bridging) (incoming ?: active) else active
        return channel?.snapshotCache(Long.MIN_VALUE)
    }

    /** The live edge a replay -> live bridge is currently resuming toward, or null when no bridge is in flight. */
    fun activeBridgeEdgeNanos(): Long? = (phase.get() as? Phase.Bridging)?.liveEdgeNanos

    /** True while a replay -> live bridge is in flight. */
    fun isBridging(): Boolean = bridging

    /**
     * Whether this session can be parked warm for out-of-render-distance dormancy: steady
     * in-process-libav playback only, since a dormant pool member should not keep an external
     * `FFmpeg` process and its connection tied up for an unbounded time.
     */
    fun canPark(): Boolean = canHoldWarm() && active?.inProcess == true

    private fun canHoldWarm(): Boolean =
        phase.get().let { it is Phase.Active || it is Phase.Parked } && !terminated.get() && active != null &&
                incoming == null &&
                (audioHalf != null || silentSession)

    fun suspend(allowExternalProcess: Boolean = false, retainBuffered: Boolean = false): Boolean {
        if (!(if (allowExternalProcess) canHoldWarm() else canPark()) || parkFlag.get()) return false
        parkFlag.set(true)
        // Nothing may switch tracks while dormant, so holding idle FFmpeg processes would be pure cost.
        audioWarmPool.invalidateAll()
        audio.pauseForPark()
        if (!retainBuffered) active?.pipe?.trimForPark()
        val frozen = pacingClockNanos().takeIf { it >= 0L } ?: clock.currentTime()
        clock.moveTo(frozen)
        phase.set(Phase.Parked(frozen, System.nanoTime()))
        logger.debug("$debugLabel [park] session parked warm at ${ms(frozen)}ms.")
        return true
    }

    /** Un-parks suspended session: readers resume from frozen position; wall clock shifted past dormant interval. */
    fun resume() {
        if (!parkFlag.get()) return
        (phase.get() as? Phase.Parked)?.let { clock.addPausedDuration(System.nanoTime() - it.sinceNanos) }
        phase.updateAndGet { if (it is Phase.Parked) Phase.Active else it }
        audio.resumeFromPark()
        parkFlag.set(false)
        logger.debug("$debugLabel [park] session un-parked; resuming from frozen position.")
    }

    /** True while the session is parked warm. */
    fun isParked(): Boolean = parkFlag.get()

    /** The frozen playback position while parked (so an evicted park saves where the viewer left, not a
     *  position drifted forward by the dormant wall-clock time), or null when not parked. */
    fun parkedPositionNanos(): Long? =
        (phase.get() as? Phase.Parked)?.frozenNanos?.takeIf { parkFlag.get() && it >= 0 }

    private val masterClock = AudioMasterClock(debugLabel, requestAudioResync = audio::requestResync, rate = { tempo })

    private fun pacingClockNanos(): Long {
        // While a replay -> live bridge is active the wall clock is clamped to the live edge so it never
        // overruns the handoff point (otherwise the live channel's first frame arrives "late" and is
        // dropped instead of presented, and the audio gate never opens).
        val wall = if (clock.isRunning) clock.currentTime().coerceAtMost(bridgeCeilingNanos) else -1L
        return masterClock.nanos(audio.sampleClock(), wall, parkFlag.get(), ::exactAvBiasNanos)
    }

    /** The position playback is actually at, for callers that need to freeze or save it. */
    fun currentPacingNanos(): Long = pacingClockNanos()

    /** Plays the audio (and with it the picture paced to it) at [ratio] x; see [AudioSink.playbackRate]. */
    fun setPlaybackRate(ratio: Double) {
        audio.playbackRate = ratio
    }

    private fun exactAvBiasNanos(): Long? {
        val a0 = audioFeeder?.firstPtsNanos ?: return null
        if (a0 < 0) return null
        val videoBias = active?.nativePipe?.rawPtsBiasNanos ?: return null
        if (videoBias == Long.MIN_VALUE) return null
        return a0 + videoBias
    }

    private fun targetDims(streamSet: ActiveStreams?, lastQuality: Int = 0, alpha: Boolean = false): Pair<Int, Int> {
        val dims = targetDimsUncapped(streamSet, lastQuality)
        return if (alpha) AlphaVideo.capDims(dims.first, dims.second) else dims
    }

    private fun targetDimsUncapped(streamSet: ActiveStreams?, lastQuality: Int): Pair<Int, Int> {
        val (tw, th) = getTextureSize()
        if (tw > 0 && th > 0) return tw to th
        val q = when {
            lastQuality > 0 -> lastQuality
            streamSet != null -> MediaStreamSelector.parseQuality(streamSet.currentVideo)
            else -> 0
        }
        if (q <= 0) return 854 to 480
        return MediaStreamSelector.qualityToDims(q).let { it[0] to it[1] }
    }

    /**
     * Seamless quality switch: launches [streamSet]'s new-quality video as a parallel incoming channel while the
     * current one keeps playing, then swaps once it's caught up.
     */
    fun beginQualitySwitch(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int, hwAccel: HwAccelBackend) {
        if (active == null || !isPlaying || terminated.get()) {
            // Nothing to hand off from: drop the staged texture, but the target quality still takes
            // effect below via a full start on the same (new) stream set — not a real failure.
            onQualitySwitchAborted(true)
            start(streamSet, offsetNanos, lastQuality, hwAccel)
            return
        }
        val ffmpeg = FFmpegBinary.getPath() ?: run { onQualitySwitchAborted(false); return }

        // Supersede any in-flight switch (rapid quality changes)
        val alpha = alphaPlan(ffmpeg, streamSet)
        val channel = VideoChannel(alpha)
        var generation = 0L
        val previous = synchronized(switchLock) {
            generation = incomingGeneration + 1
            incomingGeneration = generation
            incoming.also { incoming = channel }
        }
        previous?.let {
            if (MediaPlayer.DEBUG) logger.debug("$debugLabel Superseding incoming video handoff #${generation - 1}.")
            discardChannelAsync(it)
        }
        if (terminated.get()) {
            synchronized(switchLock) {
                if (incoming === channel && incomingGeneration == generation) incoming = null
            }
            discardChannelBlocking(channel)
            return
        }

        val (w, h) = targetDims(streamSet, lastQuality, alpha != null)
        try {
            // No latch / audio gate: the clock is already running. EOS aborts only this handoff
            if (MediaPlayer.DEBUG) {
                logger.debug(
                    "$debugLabel Starting incoming video handoff #$generation $w x $h " +
                            "at ${ms(offsetNanos)} ms.",
                )
            }
            channel.launch(
                ffmpeg,
                streamSet,
                w,
                h,
                offsetNanos,
                hwAccel,
                onFirstFrame = {
                    clock.markFirstFrame()
                    if (MediaPlayer.DEBUG) logger.debug("$debugLabel Incoming video handoff #$generation presented its first frame.")
                },
                onEos = { stderr, normalEos -> handoffEos(channel, generation, stderr, normalEos) },
                parkFlag = parkFlag,
                // No pre-prime preview: the incoming channel's first decoded frame is stale by the
                // session-open time, and presenting it would promote a rewound picture that then holds
                // until decode catches the clock. Promote on the first *paced* frame instead.
                presentPreview = false,
                // Same reason a late frame must not go out here: promotion happens on the first frame
                // this channel presents, so it has to be one that is actually on the clock.
                tolerateLateness = false,
            )
            val shouldDiscard = synchronized(switchLock) {
                !(!terminated.get() && active != null && incoming === channel && incomingGeneration == generation) && if (incoming === channel && incomingGeneration == generation) {
                    incoming = null
                    true
                } else {
                    false
                }
            }
            if (shouldDiscard) discardChannelBlocking(channel)
        } catch (e: IOException) {
            logger.error("$debugLabel Failed to start incoming video for quality switch.", e)
            val wasCurrent = synchronized(switchLock) {
                if (incoming === channel && incomingGeneration == generation) {
                    incoming = null
                    true
                } else {
                    false
                }
            }
            discardChannelBlocking(channel)
            if (wasCurrent) onQualitySwitchAborted(false)
        }
    }

    /**
     * Promotes the incoming quality-switch channel to live: the new channel becomes the rendered one
     * and the old channel is torn down off-thread. Called from the render thread the moment the
     * incoming channel's first frame has been uploaded to the staged texture, so the swap is seamless.
     */
    fun promoteIncoming(): Boolean {
        val old: VideoChannel?
        val generation: Long
        synchronized(switchLock) {
            val inc = incoming ?: return false
            generation = incomingGeneration
            incoming = null
            old = active
            active = inc
            outputAlpha = inc.alpha != null
        }
        if (MediaPlayer.DEBUG) logger.debug("$debugLabel Promoted incoming video handoff #$generation.")
        updateRawFrameSink() // Re-attach popout / preview to the new live channel
        old?.let { discardChannelAsync(it) }
        return true
    }

    /**
     * End of a handoff channel (quality switch, replay -> live): while still incoming it only aborts the handoff;
     * once promoted it is the session's video, so its end (or failure) is the stream's, like [start]'s channel.
     */
    private fun handoffEos(channel: VideoChannel, generation: Long, stderr: String, normalEos: Boolean) {
        if (synchronized(switchLock) { active === channel }) {
            onStreamEnd(stderr, normalEos)
            return
        }
        abortIncoming(generation, "eos=$normalEos stderr=${MediaUtil.truncate(stderr)}.")
    }

    private fun abortIncoming(generation: Long, reason: String) {
        val inc = synchronized(switchLock) {
            if (incomingGeneration != generation) null else incoming.also { incoming = null }
        } ?: return
        if (MediaPlayer.DEBUG) logger.debug("$debugLabel Aborted incoming video handoff #$generation ($reason).")
        discardChannelAsync(inc)
        onQualitySwitchAborted(false)
    }

    private fun discardChannelAsync(channel: VideoChannel) {
        daemon({
            channel.teardownProcess()
            renderExecutor.execute { channel.pipe.cleanup() }
        }, "MediaPlayer-video-discard").start()
    }

    private fun discardChannelBlocking(channel: VideoChannel) {
        channel.teardownProcess()
        renderExecutor.execute { channel.pipe.cleanup() }
    }

    /**
     * Signals all stop flags, destroys the `FFmpeg` processes, closes the audio line, and joins the
     * reader threads. Tears down any in-flight quality switch too. Safe to call when idle.
     */
    fun stop() {
        phase.set(Phase.Stopped)
        audioWarmPool.invalidateAll()
        audioSwitchGeneration.incrementAndGet()
        audioFeeder = null
        masterClock.reset()
        parkFlag.set(false) // Release any parked readers so they observe the stop flags and exit
        val inc = synchronized(switchLock) {
            incomingGeneration += 1
            incoming.also { incoming = null }
        }
        inc?.let { discardChannelBlocking(it) }

        val a = active
        active = null
        audioHalf?.stop?.set(true)
        a?.let { it.stop.set(true); it.nativePipe?.kill() }
        audioHalf?.let { MediaProcess.gracefulDestroy(it.process) }
        audio.stop()
        a?.let {
            MediaProcess.gracefulDestroy(it.process)
            it.thread?.let { t -> joinSafely(t) }
            it.nativePipe?.release()
            renderExecutor.execute { it.pipe.cleanup() }
        }
        audioHalf?.thread?.let { joinSafely(it) }
        audioHalf = null
    }

    /**
     * Releases any remaining pipe GL resources. Called once when this session manager is permanently
     * discarded (the owning `MediaPlayer` is stopping for good). [stop] normally clears channels first.
     */
    fun cleanup() {
        audioWarmPool.close()
        synchronized(switchLock) { incoming.also { incoming = null } }?.let { discardChannelBlocking(it) }
        active?.let { ch ->
            active = null
            ch.nativePipe?.release()
            renderExecutor.execute { ch.pipe.cleanup() }
        }
    }
}
