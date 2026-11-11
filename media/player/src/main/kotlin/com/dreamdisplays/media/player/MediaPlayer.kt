package com.dreamdisplays.media.player

import com.dreamdisplays.api.media.audio.AudioDspStage
import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.model.FramePixelFormat
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.media.player.GpuTextureRef
import com.dreamdisplays.api.media.player.PlaybackEnvironment
import com.dreamdisplays.api.media.player.PlaybackHost
import com.dreamdisplays.api.media.source.LiveStreamUrls
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.api.media.stream.SubtitleTrack
import com.dreamdisplays.api.playback.model.PlaybackSpeed
import com.dreamdisplays.media.player.events.PlayerEvents
import com.dreamdisplays.media.player.managers.AudioFaultBudget
import com.dreamdisplays.media.player.managers.AudioFaultRoute
import com.dreamdisplays.media.player.managers.AudioStallDetector
import com.dreamdisplays.media.player.managers.PlaybackSessionManager
import com.dreamdisplays.media.player.managers.StatsReporter
import com.dreamdisplays.media.player.managers.StreamWatchdog
import com.dreamdisplays.media.player.managers.WarmTrack
import com.dreamdisplays.media.player.pipeline.AudioSink
import com.dreamdisplays.media.player.pipeline.PlaybackClock
import com.dreamdisplays.media.player.policy.FailureStages
import com.dreamdisplays.media.player.policy.LoadFailures
import com.dreamdisplays.media.player.policy.LoadRetrySchedule
import com.dreamdisplays.media.player.policy.RetryPolicy
import com.dreamdisplays.media.player.preparation.MediaPreparationService
import com.dreamdisplays.media.player.preparation.PreparedMedia
import com.dreamdisplays.media.player.process.HwAccelBackend
import com.dreamdisplays.media.player.process.MediaProcess
import com.dreamdisplays.media.player.readahead.ReadAhead
import com.dreamdisplays.media.player.stream.ActiveStreams
import com.dreamdisplays.media.player.stream.MediaStreamSelector
import com.dreamdisplays.media.player.subtitle.SubtitleController
import com.dreamdisplays.media.player.util.MediaUtil
import com.dreamdisplays.media.player.util.MediaUtil.ms
import com.dreamdisplays.media.player.util.daemon
import com.dreamdisplays.media.runtime.diagnostics.HitchCause
import com.dreamdisplays.media.runtime.diagnostics.PlaybackDiagnostics
import com.dreamdisplays.media.runtime.diagnostics.PlaybackFailure
import com.dreamdisplays.media.runtime.diagnostics.ResolveTrail
import com.dreamdisplays.media.runtime.security.MediaHostGuard
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Manages media playback lifecycle: stream selection, `FFmpeg`, playback state, error handling. */
class MediaPlayer(
    private val url: String,
    private val lang: String,
    private val host: PlaybackHost,
    private val env: PlaybackEnvironment,
    @Volatile private var replayBootstrap: ReplayBootstrap? = null,
    private val audioStage: AudioDspStage? = null,
) {
    private val debugLabel = "${host.reportId}/${Integer.toHexString(System.identityHashCode(this))}"
    private val terminated = AtomicBoolean(false)
    private val clock = PlaybackClock()
    private val startedFromReplay = replayBootstrap != null
    private val retryPolicy = RetryPolicy(MAX_FETCH_RETRIES)
    private val loadRetry = LoadRetrySchedule()
    private val source: MediaSource = MediaSource.from(url)

    @Volatile
    private var lifecycle: PlayerState = PlayerState.Idle

    @Volatile
    private var media: ResolvedMedia? = null

    @Volatile
    private var qualitySwitch: QualitySwitch? = null

    @Volatile
    private var audioTrackSwitching = false

    private var pauseRequested = false

    /** A [warmUpPaused] stream is opening and parks on its first frame. Set and cleared on the control thread. */
    @Volatile
    private var warmingUp = false

    /** When the current [warmUpPaused] stream started opening; one that never reaches its first frame goes stale. */
    @Volatile
    private var warmUpStartedNanos = 0L

    /** Parks a [warmUpPaused] session on the pipeline thread the moment its playout begins (see [WarmUpHold]). */
    private val warmUpHold = WarmUpHold()

    /** A [warmUpPaused] call has not reached the control thread yet. */
    @Volatile
    private var warmUpPending = false

    /** Parked by [warmUpPaused] on a decoded start-up cushion; the next [play] continues at once. */
    @Volatile
    private var heldReady = false

    private var restartPending = false
    private var hwAccelDisabled = false
    private var lastRequestedQuality = 0
    private var sessionStartNanos = 0L
    private var sessionStartOffsetNanos = 0L
    private var lastStallNanos = 0L
    private var stallReResolves = 0
    private var audioRestartAttempts = 0
    private var lastAudioFailureNanos = 0L
    private val audioFaults = AudioFaultBudget()

    /** The current session produced its first frame (a failure before that is a failure to open the stream). */
    @Volatile
    private var sessionFirstFrame = false

    /**
     * A live relay stream (`srt://`, `rtmp://`, `rtsp://`): played at the live edge, and reconnected every
     * [RELAY_RECONNECT_MS] for as long as it is off the air, with no error shown (see [reconnectRelay]).
     */
    private val relayStream: Boolean = LiveStreamUrls.isLive(url)

    /** A relay stream that is not delivering pictures right now: not on the air yet, or dropped and reconnecting. */
    @Volatile
    private var waitingForStream = relayStream

    private val relayReconnectPending = AtomicBoolean(false)
    private var lastRelayWaitLogNanos = 0L

    private var preparedBootstrap: PreparedMedia? = replayBootstrap?.prepared

    private val state: PlayerState get() = if (terminated.get()) PlayerState.Stopped else lifecycle

    private val isReady: Boolean get() = state.isReady

    @Volatile
    private var primedStartPositionNanos = -1L

    /** Playback speed the viewer asked for (see [setPlaybackSpeed]); live / unseekable media plays at 1 anyway. */
    @Volatile
    private var requestedSpeed = PlaybackSpeed.NORMAL

    /** Stream ended on a server-timeline display: parked on the last frame until that timeline moves it on. */
    @Volatile
    private var heldAtEnd = false

    private val events = PlayerEvents(
        onError = { e ->
            onControl { lifecycle = PlayerState.Error }
            reportFailure(FailureStages.OPEN_STREAM, e.kind.name, e.message.orEmpty(), final = true, fatal = e.isFatal, cause = e)
            reportError(e)
        },
        onSeek = { host.afterSeek() },
    )

    private val stats = StatsReporter(
        debugLabel = debugLabel,
        pollCounters = {
            StatsReporter.Snapshot(
                samplesIn.getAndSet(0),
                framesToGpu.getAndSet(0),
                framesDropped.getAndSet(0)
            )
        },
        getPositionMs = { getCurrentTime() / 1_000_000L },
        isLive = { liveStream },
    )

    private enum class InitGate { IDLE, RUNNING, RUNNING_QUEUED }

    private val initGate = AtomicReference(InitGate.IDLE)

    private val watchdog = StreamWatchdog(
        debugLabel = debugLabel,
        isSessionActive = { sessionManager.isPlaying && !sessionManager.isParked() && !terminated.get() },
        // A relay stream with a still picture (a still window shared) is alive while its sound plays
        getLastFrameNanos = {
            if (relayStream) RelayPicture.progressNanos(sessionManager.lastFrameNanos.get(), sessionManager.lastPcmOutNanos)
            else sessionManager.lastFrameNanos.get()
        },
        onStall = { handleSessionStall("no frames") },
    )

    private val sessionManager = PlaybackSessionManager(
        debugLabel = debugLabel,
        clock = clock,
        events = events,
        terminated = terminated,
        getTextureSize = { host.textureWidth to host.textureHeight },
        getBrightness = { brightness },
        onStreamEnd = ::handleStreamEnd,
        onQualitySwitchAborted = { appliedAnyway -> handleQualitySwitchAborted(appliedAnyway) },
        onAudioEnd = { stderr, hadPcm, isCurrent -> handleAudioEnd(stderr, hadPcm, isCurrent) },
        onAudioFault = { reason, stderr, isCurrent -> handleAudioFault(reason, stderr, isCurrent) },
        onAudioTrackSwitchSettled = { onControl { audioTrackSwitching = false } },
        renderExecutor = env.renderExecutor,
        uploaderFactory = env.uploaderFactory,
        gpuYuvActive = env.config.gpuYuvActive,
        audioStage = audioStage,
        transparencyAllowed = { env.config.transparentVideoAllowed },
        nativePipeAllowed = { env.config.nativePipeAllowed },
        inProcessAllowed = { env.config.inProcessDecodeAllowed },
    )

    /**
     * True when the live video is decoded with its alpha channel (straight-alpha RGBA frames): the host must then draw
     * it from an RGBA texture with blending. Changes only when a new video channel starts.
     */
    val videoHasAlpha: Boolean get() = sessionManager.outputAlpha

    @Volatile
    private var controlThread: Thread? = null
    private val controlExecutor = Executors.newSingleThreadExecutor { r ->
        daemon(r, "MediaPlayer-ctrl").also { controlThread = it }
    }

    private val initCallbacks = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var initDrained = false

    private val subtitles = SubtitleController(debugLabel) { terminated.get() }

    private var streams: ActiveStreams?
        get() = media?.streams
        set(value) {
            media = if (value == null) null else media?.copy(streams = value)
        }

    private var lastQuality: Int
        get() = media?.quality ?: 0
        set(value) {
            media = media?.copy(quality = value)
        }

    private val liveStream: Boolean get() = media?.live == true
    private val seekable: Boolean get() = media?.seekable == true
    private val durationHintNanos: Long get() = media?.durationNanos ?: 0L

    @Volatile
    private var brightness = 1.0

    private val volume = VolumeController(env.config.defaultDisplayVolume) {
        sessionManager.setVolume(it)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(javaClass)

        val DEBUG: Boolean = System.getProperty("dreamdisplays.debug")?.toBoolean() == true
                || System.getenv("DREAMDISPLAYS_DEBUG").let { it == "1" || it.equals("true", ignoreCase = true) }

        var captureSamples: Boolean = true

        internal val samplesIn = AtomicLong()
        internal val framesToGpu = AtomicLong()
        internal val framesDropped = AtomicLong()

        private const val MAX_FETCH_RETRIES = 3
        private const val WARM_UP_STALE_NANOS = 20_000_000_000L
        private const val AUDIO_HEAL_NANOS = 1_000_000_000L
        private const val CHEAP_SEEK_MS = 250L
        private const val MAX_AUDIO_RESTARTS = 3
        private const val AUDIO_RESTART_BUDGET_RESET_NS = 120_000_000_000L
        private const val HWACCEL_FAIL_WINDOW_NS = 5_000_000_000L
        private const val QUALITY_STATUS_MAX_NS = 30_000_000_000L
        private const val REPEATED_STALL_WINDOW_NS = 90_000_000_000L
        private val STALL_RERESOLVE_BACKOFF_MS = longArrayOf(0L, 2_000L, 5_000L, 15_000L, 30_000L)
        private const val MAX_STALL_RERESOLVES = 6
        private const val AUDIO_EOS_NEAR_END_GUARD_NS = 3_000_000_000L

        /** How long the other audio tracks stay pre-warmed after the viewer last looked at the track list. */
        private const val WARM_TRACKS_AFTER_LIST_NS = 60_000_000_000L

        /** Pause between attempts to (re)connect a live relay stream that is off the air. */
        private const val RELAY_RECONNECT_MS = 1_000L
        private const val RELAY_WAIT_LOG_INTERVAL_NS = 60_000_000_000L

        /** `FFmpeg` errors that no reconnect fixes: the build or the URL itself cannot work. */
        private val RELAY_PERMANENT_ERRORS = [
            "Protocol not found", "not on whitelist", "Option not found", "Unrecognized option",
        ]

        private val INIT_THREAD_COUNTER = AtomicInteger()

        private val INIT_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(4, 8),
        ) { r -> daemon(r, "MediaPlayer-init-${INIT_THREAD_COUNTER.incrementAndGet()}") }

        private val RETRY_SCHEDULER: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r -> daemon(r, "MediaPlayer-retry") }
    }

    /** One-shot native packet-cache bootstrap for display reappearance. */
    class ReplayBootstrap(val snapshot: ByteArray, val positionNanos: Long) {
        var prepared: PreparedMedia? = null
    }

    init {
        this.replayBootstrap?.let { boot -> safeExecute { startReplayBootstrapVideo(boot) } }
        dispatchInitialize()
    }

    /** Resumes playback from the current seek position. No-op if already playing. */
    fun play() = safeExecute {
        if (!isReady || terminated.get()) return@safeExecute
        // A warm-up its pipeline already parked is resumed like any warm pause
        val warmUpHeld = warmUpHold.disarm() && sessionManager.isParked()
        pauseRequested = false
        // Mid warm-up the stream is already opening at the paused position: let it simply play on
        warmingUp = false
        heldReady = false
        // Every resume (plain, held ready, scheduled start, adopted warm) plays at the current volume
        volume.reapply()
        if (isPausedWarm() || warmUpHeld) {
            sessionManager.resume()
            lifecycle = PlayerState.Playing
            startWatchdog()
            return@safeExecute
        }
        if (sessionManager.isPlaying) return@safeExecute
        val ss = streams ?: return@safeExecute
        noteHitch(FailureStages.RESUME, if (liveStream) "re_resolve" else "cold_restart", "resumed without a warm session")
        if (liveStream) {
            logger.debug("$debugLabel Live resume from cold pause; re-resolving playlist URLs.")
            env.cacheInvalidator.invalidate(url)
            forgetResolvedStreamUrls()
            primedStartPositionNanos = 0L
            lifecycle = PlayerState.Restarting
            dispatchInitialize(coalesce = true)
            return@safeExecute
        }
        val offset = if (lifecycle == PlayerState.Ended) 0L else clock.originNanos
        startStreams(ss, offset)
    }

    /** Pauses playback, capturing the current position for later resume. */
    fun pause() = safeExecute {
        val warmUpHeld = warmUpHold.disarm() && sessionManager.isParked()
        pauseRequested = true
        warmingUp = false
        // Already parked warm: pausing again must not throw the open session away
        if (isPausedWarm()) return@safeExecute
        if (warmUpHeld) {
            lifecycle = PlayerState.Paused
            return@safeExecute
        }
        if (!sessionManager.isPlaying) return@safeExecute
        if (!liveStream) {
            watchdog.stop()
            if (sessionManager.suspend(allowExternalProcess = true, retainBuffered = true)) {
                lifecycle = PlayerState.Paused
                return@safeExecute
            }
        }
        val heard = sessionManager.currentPacingNanos()
        clock.reset(
            when {
                liveStream -> 0L
                heard >= 0L -> heard
                else -> clock.currentTime()
            }
        )
        lifecycle = PlayerState.Paused
        stopSession()
    }

    /**
     * `true` when the session can be parked warm.
     *
     * Read from any thread.
     */
    fun canPark(): Boolean = isReady && sessionManager.canPark()

    /**
     * Installs the acoustics stage on a player built without one: a queue item preloaded in the background gets its
     * display's stage only when it is adopted, so it never feeds the display's DSP while the current video plays.
     */
    fun attachAudioStage(stage: AudioDspStage?) = sessionManager.setAudioStage(stage)

    /** Parks the player warm while its display sits out of render distance. */
    fun park() = safeExecute {
        watchdog.stop()
        // A user pause already holds the session parked: it stays exactly that, with no watchdog
        if (isPausedWarm()) return@safeExecute
        if (!sessionManager.suspend()) startWatchdog()
    }

    /** Resumes a [park]ed player from its frozen position; a paused one stays paused until [play]. */
    fun unpark() = safeExecute {
        if (sessionManager.isParked() && !isPausedWarm()) {
            sessionManager.resume()
            startWatchdog()
            applySpeed() // A speed change that arrived while parked
        }
    }

    /** Stops playback permanently; the instance must not be used after this call. */
    fun stop() {
        if (terminated.getAndSet(true)) return
        val submitted = runCatching {
            controlExecutor.submit {
                try {
                    doStop()
                } finally {
                    controlExecutor.shutdown()
                }
            }
        }.isSuccess
        if (!submitted) {
            daemon({ doStop() }, "MediaPlayer-stop").start()
        }
    }

    /** Seeks to an absolute position in nanos. */
    fun seekTo(nanos: Long, fire: Boolean) = safeExecute { doSeek(nanos, fire) }

    /** Seeks [s] seconds relative to the current position. */
    fun seekRelative(s: Double) = safeExecute {
        if (!isReady || !seekable) return@safeExecute
        val max = (getDuration() - 1).coerceAtLeast(0)
        if (max <= 0) return@safeExecute
        doSeek((getCurrentTime() + (s * 1e9).toLong()).coerceIn(0, max), true)
    }

    /**
     * Current playback position in nanos.
     *
     * Falls back to the frozen / seek offset when paused or not started.
     */
    fun getCurrentTime(): Long {
        sessionManager.parkedPositionNanos()?.let { return it }
        if (!isReady || !sessionManager.isPlaying) return clock.originNanos
        return clock.currentTime()
    }

    /**
     * Position to save / resume from.
     *
     * Identical to [getCurrentTime] in normal playback, but while a replay -> live bridge
     * is active it prefers the parked or bridge-edge position instead.
     */
    fun getResumePositionNanos(): Long =
        sessionManager.parkedPositionNanos() ?: sessionManager.activeBridgeEdgeNanos() ?: getCurrentTime()

    /**
     * Position actually being heard and shown (the audio master clock), for measuring sync drift. Unlike
     * [getCurrentTime], which follows the wall clock, this includes any drift of the sound card's clock.
     */
    fun getSyncPositionNanos(): Long {
        sessionManager.parkedPositionNanos()?.let { return it }
        if (!isReady || !sessionManager.isPlaying) return clock.originNanos
        val heard = sessionManager.currentPacingNanos()
        return if (heard >= 0L) heard else clock.currentTime()
    }

    /**
     * Sets the sync playback rate (content time per real time, e.g. 1.005). Applied to the audio, which the
     * picture follows; has no effect on a session without sound.
     */
    fun setSyncPlaybackRate(ratio: Double) = sessionManager.setPlaybackRate(ratio)

    /** True when paused with the session kept open, so [play] resumes instantly from the frozen position. */
    fun resumesWarm(): Boolean = isPausedWarm()

    /**
     * Holds a paused, seekable player ready to play at once: opens the stream at [positionNanos], lets it decode its
     * start-up cushion, and parks it on the first frame, so the next [play] continues without opening anything (a
     * plain pause parks before the first frame, or not at all). Playout parks as soon as it would begin, so at most a
     * few milliseconds of sound are released; callers mute the player while [isWarmingUp].
     *
     * Does nothing unless paused, seekable and not live.
     */
    fun warmUpPaused(positionNanos: Long) {
        warmUpPending = true
        safeExecute {
            try {
                startWarmUp(positionNanos.coerceAtLeast(0L))
            } finally {
                warmUpPending = false
            }
        }
    }

    /** True while a [warmUpPaused] stream is opening (or the call is still queued), until it goes stale. */
    fun isWarmingUp(): Boolean = warmUpPending || (warmingUp && !warmUpStale())

    private fun warmUpStale(): Boolean = System.nanoTime() - warmUpStartedNanos > WARM_UP_STALE_NANOS

    /**
     * True when a seek now lands within a frame or two: the stream decodes in-process (in-place seeks) and the last
     * seek on it, if any, was measured cheap (in place, under [CHEAP_SEEK_MS]). After a reopen or any slow seek, seeks
     * are expensive and sync uses its normal threshold, rate correction and cooldown.
     */
    fun seeksCheaply(): Boolean {
        if (!isReady || liveStream || !seekable || !sessionManager.seeksInPlace) return false
        val last = sessionManager.lastSeekMs
        return last < 0L || (sessionManager.lastSeekInPlace && last <= CHEAP_SEEK_MS)
    }

    /** True while parked by [warmUpPaused] on its start-up cushion: [play] continues at once. */
    fun isHeldReady(): Boolean = heldReady && isPausedWarm()

    /**
     * Plays at [speed] x (clamped to [PlaybackSpeed.MIN]..[PlaybackSpeed.MAX]) with the pitch kept: the sound is
     * time-stretched in FFmpeg and the picture follows the audio clock. A playing session restarts in place at the
     * position being heard; a paused or not-yet-started one simply starts at the new speed. Live streams stay at 1.
     * Independent of [setSyncPlaybackRate], the small sync correction applied on top.
     */
    fun setPlaybackSpeed(speed: Double) {
        val s = PlaybackSpeed.clamp(speed)
        if (s == requestedSpeed) return
        requestedSpeed = s
        safeExecute(::applySpeed)
    }

    /** The speed the current session plays at (1 for live media). */
    fun getPlaybackSpeed(): Double = sessionManager.tempo

    /** Stream duration in nanos, or 0 for live streams. */
    fun getDuration(): Long = if (liveStream) 0L else durationHintNanos

    /** Primes the first live start offset before initialization opens the decoder. */
    fun primeStartPosition(nanos: Long) {
        if (nanos >= 0L && !sessionManager.isPlaying) primedStartPositionNanos = nanos
    }

    /** `true` when this player resumed from a cached replay bootstrap (already positioned at the saved time). */
    fun isResumingFromReplay(): Boolean = startedFromReplay

    /**
     * Runs [callback] immediately if already initialized, otherwise queues it for when
     * initialization completes.
     *
     * The callback is called on the init thread.
     */
    fun whenInitialized(callback: () -> Unit) {
        if (initDrained || isReady) {
            callback(); return
        }
        initCallbacks.add(callback)
        if ((initDrained || isReady) && initCallbacks.remove(callback)) callback()
    }

    /**
     * Livestreams start playing immediately and may not support seeking.
     *
     * @return `true` if the stream is a live stream.
     */
    fun isLive(): Boolean = liveStream || relayStream

    /** True while a live relay stream is off the air (not started yet, or reconnecting): the UI says it is waiting. */
    fun isWaitingForStream(): Boolean = relayStream && waitingForStream && !terminated.get()

    /**
     * True when a live relay stream's picture must not be shown (see [RelayPicture.stale]): the stream ended, is off
     * the air or reconnecting, has shown nothing yet, or is paused with a frame older than [maxAgeNanos]. A still
     * picture on an open connection stays. Always false for any other stream.
     */
    fun isRelayPictureStale(maxAgeNanos: Long): Boolean {
        if (!relayStream) return false
        val state = lifecycle
        val connected = !terminated.get() && !waitingForStream && (state == PlayerState.Playing || state == PlayerState.Paused)
        return RelayPicture.stale(
            connected = connected,
            paused = pauseRequested || state == PlayerState.Paused,
            lastFrameNanos = sessionManager.lastFrameNanos.get(),
            nowNanos = System.nanoTime(),
            maxAgeNanos = maxAgeNanos,
        )
    }

    /** @return `true` if the stream supports seeking. */
    fun canSeek(): Boolean = isReady && seekable

    /** True while a server-timeline display holds its last frame after its stream ended ([PlaybackHost.followsServerTimeline]). */
    fun isHeldAtEnd(): Boolean = heldAtEnd && lifecycle == PlayerState.Ended

    /** @return `true` once active playback is advancing (first frame arrived, not paused / parked). */
    fun isClockRunning(): Boolean =
        sessionManager.isPlaying && !sessionManager.isParked() && clock.isRunning

    /**
     * Connects or disconnects the popout window sink.
     *
     * Pass `null` to detach.
     */
    fun setPopoutSink(sink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?) {
        sessionManager.popoutFrameSink = sink
    }

    /**
     * Connects or disconnects the display menu preview sink.
     *
     * Pass `null` to detach.
     */
    fun setPreviewSink(sink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?) {
        sessionManager.previewFrameSink = sink
    }

    /** Interval between the current video's frames in ns, 0 when unknown. Cheap; called on every draw. */
    fun frameIntervalNanos(): Long {
        val fps = media?.streams?.currentVideo?.fps ?: return 0L
        return if (fps > 1.0 && fps < 1_000.0) (1_000_000_000.0 / fps).toLong() else 0L
    }

    /** The video pipeline and hardware decoder in use (`native-gpuyuv/d3d11va`), for hitch reports. */
    fun pipelineLabel(): String = "${sessionManager.pipelineLabel()}/${currentHwAccel().name.lowercase()}"

    /** `true` once the first decoded frame is ready for GPU upload. */
    fun textureFilled(): Boolean = sessionManager.textureFilled()

    /** Discards any ready raw frame so the renderer will not show stale content after a timeline jump. */
    fun clearFrame() = sessionManager.clearFrame()

    /**
     * Uploads latest decoded frame to texture.
     *
     * Render thread only.
     */
    fun updateFrame(texture: GpuTextureRef, w: Int, h: Int): Boolean =
        sessionManager.updateFrame(texture, w, h)

    /**
     * Uploads the latest planar I420 frame into the three plane textures.
     *
     * Render thread only.
     *
     * @return `true` if uploaded.
     */
    fun updateFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, w: Int, h: Int): Boolean =
        sessionManager.updateFramePlanar(y, u, v, w, h)

    /** `true` while a parallel quality switch is warming up the new resolution. */
    fun hasIncomingVideo(): Boolean = sessionManager.hasIncoming()

    /**
     * Uploads the latest incoming (quality-switch) frame to [texture].
     *
     * Render thread only.
     *
     * @return `true` if uploaded.
     */
    fun updateIncomingFrame(texture: GpuTextureRef, w: Int, h: Int): Boolean =
        sessionManager.updateIncomingFrame(texture, w, h)

    /**
     * Uploads the latest incoming planar I420 frame into the staged plane textures.
     *
     * @return `true` if uploaded.
     */
    fun updateIncomingFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, w: Int, h: Int): Boolean =
        sessionManager.updateIncomingFramePlanar(y, u, v, w, h)

    /**
     * Promotes the warmed-up quality-switch channel to live.
     *
     * @return `false` if it was already aborted.
     */
    fun promoteIncomingVideo(): Boolean {
        val promoted = sessionManager.promoteIncoming()
        if (promoted) onControl { qualitySwitch = null }
        return promoted
    }

    private fun handleQualitySwitchAborted(appliedAnyway: Boolean) {
        host.cancelQualityHandoff()
        onControl {
            val rollback = qualitySwitch?.rollback
            qualitySwitch = null
            if (rollback == null || appliedAnyway) return@onControl
            media = media?.copy(streams = rollback.previousStreams, quality = rollback.previousQuality)
            host.videoContentAspect = rollback.previousStreams.currentVideo.contentAspect()
            if (lastRequestedQuality == rollback.target) lastRequestedQuality = rollback.previousQuality
        }
    }

    /**
     * Sets the user-controlled volume (0.0–2.0).
     *
     * Distance attenuation is applied on top.
     */
    fun setVolume(volume: Float) = this.volume.setUserVolume(volume)

    /** The display audio's output latency now, or null while no audio line plays (karaoke timing). */
    fun audioOutputLatency(): com.dreamdisplays.media.player.pipeline.AudioOutputLatency? =
        sessionManager.audioOutputLatency()

    /** Hands every chunk of display audio written to [tap] (karaoke, on a singer's client only); null removes it. */
    fun setPcmTap(tap: com.dreamdisplays.media.player.pipeline.PlayedPcmTap?) = sessionManager.setPcmTap(tap)

    /** Seeds volume up-front (before audio starts) to avoid loud burst on reappear. */
    fun primeVolume(
        userVolume: Float, distance: Double, maxRadius: Double,
        fullVolumeRadius: Double = 0.0, squared: Boolean = true,
    ) {
        volume.setUserVolume(userVolume)
        volume.updateAttenuation(distance, maxRadius, fullVolumeRadius, squared)
    }

    /** Sets the brightness multiplier applied to each frame before GPU upload (0.0–2.0). */
    fun setBrightness(brightness: Float) {
        this.brightness = brightness.toDouble().coerceIn(0.0, 2.0)
    }

    /** @return the list of available video quality levels (in pixels) for the current stream. */
    fun getAvailableQualities(): List<Int> {
        val cap = if (env.config.isPremium) 2160 else 1080
        return streams?.availableVideo.orEmpty().asSequence()
            .mapNotNull { it.height }
            .filter { it <= cap }
            .distinct().sorted().toList()
    }

    /** Switches to [quality]. */
    fun setQuality(quality: VideoQuality, userInitiated: Boolean = false) =
        safeExecute { changeQuality(quality, userInitiated) }

    /**
     * @return selectable audio tracks (deduped by language).
     *
     * Asking for the list (the display menu does, every frame it is open) keeps the other tracks pre-warmed for
     * [WARM_TRACKS_AFTER_LIST_NS], so a pick from it switches at once; nobody pays for that warming otherwise.
     */
    fun getAvailableAudioTracks(): List<MediaStream> = audioTracks().also { if (it.size > 1) noteTrackListShown() }

    private fun audioTracks(): List<MediaStream> {
        val audio = streams?.availableAudio?.filter { !it.type.hasVideo } ?: return []
        return audio
            .groupBy { it.audioTrackLang ?: it.audioTrackName }
            .values
            .map { group -> group.maxByOrNull { it.bitrate ?: 0 } ?: group.first() }
    }

    /** URL of currently-playing audio track for UI highlighting (`null` before stream resolves). */
    fun getCurrentAudioTrack(): String? {
        val current = streams?.currentAudio ?: return null
        val key = current.audioTrackLang ?: current.audioTrackName
        return audioTracks()
            .firstOrNull { (it.audioTrackLang ?: it.audioTrackName) == key }?.url
            ?: current.url
    }

    /** Switches the active audio track to the one identified by [trackUrl]. */
    fun setAudioTrack(trackUrl: String) = safeExecute { changeAudioTrack(trackUrl) }

    /** `true` while a [setAudioTrack] switch is in flight; the actual audio can lag the UI selection by a few seconds. */
    fun isSwitchingAudioTrack(): Boolean = audioTrackSwitching

    /** Selectable subtitle tracks for the current video (empty when the source exposed none). */
    fun getAvailableSubtitleTracks(): List<SubtitleTrack> = subtitles.tracks

    /** Language of the currently selected subtitle track, or `null` when subtitles are off. */
    fun getCurrentSubtitleLang(): String? = subtitles.currentLang

    /** `true` while subtitles are enabled for this viewer, regardless of whether a cue is showing right now. */
    fun isSubtitlesEnabled(): Boolean = subtitles.enabled

    /**
     * Selects the subtitle track by [lang] and enables display, or disables subtitles when [lang] is
     * `null` or blank.
     *
     * Per-viewer only, never touches the decode pipeline.
     */
    fun setSubtitleTrack(lang: String?) = subtitles.select(lang)

    /** Current subtitle line for [getCurrentTime], or `null` when subtitles are off or no cue is active. */
    fun getCurrentSubtitleText(): String? = subtitles.textAt(getCurrentTime())

    /**
     * True while a [setQuality] change is still being applied: the new resolution decodes in a second
     * channel and only replaces the picture once its first frame lands, so the switch outlives the click.
     */
    fun isApplyingQuality(): Boolean {
        val switch = qualitySwitch ?: return false
        return switch.announced && System.nanoTime() - switch.startedNanos <= QUALITY_STATUS_MAX_NS
    }

    /**
     * Reopens the current stream without changing URL / quality;
     * used when render backend requirements change.
     */
    fun restartVideoPipeline() = safeExecute {
        val ss = streams ?: return@safeExecute
        if (isPausedWarm()) freezePausedWarmSession()
        val pos = if (liveStream) 0L else getCurrentTime()
        env.renderExecutor.execute {
            host.reloadTexture()
            safeExecute {
                if (sessionManager.isPlaying && !sessionManager.isParked()) startStreams(ss, pos)
                else clock.moveTo(pos)
            }
        }
    }

    /** Captures the active native LAV packet-ring snapshot (whole window), if the live channel has one. */
    fun captureReplaySnapshot(): ByteArray? = sessionManager.captureVideoCacheSnapshot()

    /** Captures resolved streams for fast reappear (`null` for live streams or before init). */
    fun capturePreparedMedia(): PreparedMedia? {
        if (liveStream) return null
        val ss = streams ?: return null
        return PreparedMedia(ss, liveStream, seekable, durationHintNanos)
    }

    /** Raw stream URL for scrub-preview extraction (`null` for live or unresolved). */
    fun capturedStreamRawUrl(): String? = capturePreparedMedia()?.streamSet?.currentVideo?.url

    /** Whether captured stream uses decode-forward seek path. */
    fun capturedStreamSeeksByDecoding(): Boolean =
        capturePreparedMedia()?.streamSet?.currentVideo?.seekByDecoding == true

    /** Updates distance-based volume attenuation (call every tick from game thread). */
    fun tick(distance: Double, maxRadius: Double, fullVolumeRadius: Double = 0.0, squared: Boolean = true) {
        if (!isReady) return
        volume.updateAttenuation(distance, maxRadius, fullVolumeRadius, squared)
        checkAudioHealth()
    }

    private val audioStall = AudioStallDetector()
    private var sinkMutedSinceNanos = 0L

    /**
     * Self-heal for a session whose picture plays on in silence: a sink gain stuck at 0 while the volume is up is
     * re-applied, and an audio half that stopped putting sound on the line while video keeps decoding is restarted.
     */
    private fun checkAudioHealth() {
        val now = System.nanoTime()
        val audible = volume.effectiveVolume > 0.0
        val playing = audible && lifecycle == PlayerState.Playing && !terminated.get()
        if (playing && sessionManager.sinkVolume <= 0.0) {
            if (sinkMutedSinceNanos == 0L) sinkMutedSinceNanos = now
            else if (now - sinkMutedSinceNanos > AUDIO_HEAL_NANOS) {
                logger.warn("$debugLabel Audio gain stuck at 0 while playing; restoring it. ${audioStateLine()}")
                volume.reapply()
                sinkMutedSinceNanos = 0L
            }
        } else {
            sinkMutedSinceNanos = 0L
        }
        val expecting = playing && sessionManager.audioExpected()
        if (audioStall.check(now, expecting, sessionManager.lastPcmOutNanos, sessionManager.lastFrameNanos.get())) {
            safeExecute(::healSilentAudio)
        }
    }

    private fun audioStateLine(): String =
        "[lifecycle=${lifecycle::class.simpleName} pauseRequested=$pauseRequested heldReady=$heldReady " +
                "warmingUp=$warmingUp warmUpPending=$warmUpPending volume($volume) " +
                "lastFrame=${(System.nanoTime() - sessionManager.lastFrameNanos.get()) / 1_000_000} ms ago " +
                "${sessionManager.describeAudio()}]"

    private fun healSilentAudio() {
        val ss = streams ?: return
        if (terminated.get() || lifecycle != PlayerState.Playing || !sessionManager.audioExpected()) return
        val at = if (liveStream) 0L else getCurrentTime()
        logger.warn(
            "$debugLabel Video plays but no sound reached the line for over a second; restarting audio at " +
                    "${at / 1_000_000} ms. ${audioStateLine()}"
        )
        volume.reapply()
        if (!sessionManager.restartAudio(ss, at)) logger.warn("$debugLabel Audio restart was not possible right now.")
    }

    private fun onControl(action: () -> Unit) {
        if (Thread.currentThread() === controlThread) action() else safeExecute(action)
    }

    private fun dispatchInitialize(coalesce: Boolean = false) {
        if (terminated.get()) return
        if (!initGate.compareAndSet(InitGate.IDLE, InitGate.RUNNING)) {
            if (coalesce) initGate.compareAndSet(InitGate.RUNNING, InitGate.RUNNING_QUEUED)
            return
        }
        INIT_EXECUTOR.submit {
            try {
                if (!terminated.get()) initialize()
            } finally {
                if (initGate.getAndSet(InitGate.IDLE) == InitGate.RUNNING_QUEUED) dispatchInitialize()
            }
        }
    }

    private fun initialize() {
        onControl { lifecycle = PlayerState.Preparing }
        runCatching {
            val prepared = preparedBootstrap?.also { preparedBootstrap = null }
                ?: MediaPreparationService.prepare(
                    url, lang, host.quality, env, ReadAhead.Target(host.uuid, host.readAheadSeconds),
                    liveLatencyMs = host.liveLatencyMs,
                )

            if (terminated.get()) return

            // A re-resolve (retry, stall, live quality change) keeps the dub and subtitles this viewer picked; the
            // resolver itself only knows the language the player was created with
            val previous = media
            val streamSet = MediaStreamSelector.keepAudioTrack(prepared.streamSet, previous?.streams?.currentAudio?.audioIdentity)
            val keepSubtitles = if (previous != null && subtitles.enabled) subtitles.currentLang else null
            val resolved = ResolvedMedia(
                streams = streamSet,
                live = prepared.isLive,
                seekable = prepared.isSeekable,
                durationNanos = prepared.durationNanos,
                quality = MediaStreamSelector.parseQuality(prepared.streamSet.currentVideo),
            )
            media = resolved
            host.videoContentAspect = prepared.streamSet.currentVideo.contentAspect()
            subtitles.reset(prepared.availableSubtitles)
            keepSubtitles?.let(subtitles::select)

            if (DEBUG) {
                logger.debug(
                    "{} video={} audio={}",
                    debugLabel,
                    prepared.streamSet.currentVideo,
                    prepared.streamSet.currentAudio
                )
                logger.debug("$debugLabel live=$liveStream seekable=$seekable dur=$durationHintNanos")
                stats.start()
            }

            val primed = primedStartPositionNanos.takeIf { it >= 0L } ?: 0L
            val initialOffset = if (liveStream) 0L else replayBootstrap?.positionNanos ?: primed

            safeExecute { if (!terminated.get()) startStreams(streamSet, initialOffset) }
        }.onSuccess {
            loadRetry.reset()
            // A load that worked after an automatic retry clears whatever the earlier attempt showed
            if (host.mediaError != null && !terminated.get()) host.mediaError = null
            drainInitCallbacks(run = true)
        }.onFailure { e ->
            if (terminated.get()) {
                drainInitCallbacks(run = false)
                return
            }
            // A passing failure (timeout, dropped connection, 429 / 5xx) is retried quietly: the
            // display keeps showing "loading" instead of an error, and the init callbacks stay queued
            // so the retry that works starts playback exactly like a first load would.
            val failedTry = loadRetry.retries
            if (scheduleLoadRetry(e)) {
                reportLoadFailure(e, failedTry, final = false)
                return
            }
            logger.error("$debugLabel Initialization failed: ${e.message}.")
            onControl { lifecycle = PlayerState.Error }
            reportLoadFailure(e, failedTry, final = true)
            reportError(finalLoadError(e))
            drainInitCallbacks(run = false)
        }
    }

    private fun scheduleLoadRetry(e: Throwable): Boolean {
        if (!LoadFailures.isTransient(e, source)) return false
        val delayMs = loadRetry.next(LoadFailures.maxAttempts(source)) ?: return false
        logger.warn(
            "$debugLabel Load failed (${MediaUtil.truncate(e.message ?: e.javaClass.simpleName)}); " +
                    "retrying (${loadRetry.retries}/${LoadFailures.maxAttempts(source) - 1}) in $delayMs ms."
        )
        // Whatever the failed attempt cached (a negative verdict, a half-walked redirect) must not
        // answer the retry.
        env.cacheInvalidator.invalidate(url)
        MediaHostGuard.invalidate(url)
        forgetResolvedStreamUrls()
        onControl { if (!terminated.get()) lifecycle = PlayerState.Restarting }
        RETRY_SCHEDULER.schedule({ dispatchInitialize() }, delayMs, TimeUnit.MILLISECONDS)
        return true
    }

    /**
     * The error shown once automatic retries are used up. A network failure stays a non-fatal
     * [DreamMediaException.Network], which tells the display it may try again on its own later.
     */
    private fun finalLoadError(e: Throwable): DreamMediaException {
        if (e is DreamMediaException) return e
        val message = e.message ?: "initialization failed"
        return if (LoadFailures.causedByNetwork(e)) DreamMediaException.Network(message, e)
        else DreamMediaException.Unknown(message, e)
    }

    private fun reportLoadFailure(e: Throwable, failedTry: Int, final: Boolean) {
        val error = finalLoadError(e)
        reportFailure(
            FailureStages.RESOLVE, error.kind.name, e.message ?: e.javaClass.simpleName,
            stderr = ResolveTrail.stderr(url), retry = failedTry, maxRetries = LoadFailures.maxAttempts(source) - 1,
            final = final, fatal = error.isFatal, cause = e,
        )
    }

    /**
     * Hands a failure of this display to [PlaybackDiagnostics] (the client reports it to servers that take error
     * reports), with the resolver steps tried for this link. A replaced player reports nothing.
     */
    private fun reportFailure(
        stage: String,
        kind: String,
        message: String,
        stderr: String = "",
        retry: Int = 0,
        maxRetries: Int = 0,
        final: Boolean,
        fatal: Boolean = false,
        cause: Throwable? = null,
    ) {
        if (!PlaybackDiagnostics.enabled || terminated.get()) return
        PlaybackDiagnostics.emit(
            PlaybackFailure(
                displayId = host.reportId,
                url = url,
                kind = kind,
                message = message,
                stage = stage,
                httpStatus = FailureStages.httpStatus(stderr, message, FailureStages.messages(cause)),
                steps = ResolveTrail.steps(url),
                stderr = stderr,
                retry = retry,
                maxRetries = maxRetries,
                final = final,
                fatal = fatal,
                positionMs = runCatching { clock.currentTime() / 1_000_000L }.getOrDefault(0L).coerceAtLeast(0L),
            )
        )
    }

    /** Notes why the picture is about to stop, for the client's hitch report of the freeze that follows. */
    private fun noteHitch(stage: String, action: String, message: String, stderr: String = "", frozenBeforeMs: Long = 0L) {
        if (!PlaybackDiagnostics.collectingHitches) return
        PlaybackDiagnostics.noteHitchCause(host.reportId, HitchCause(stage, action, message, stderr, frozenBeforeMs))
    }

    /** Surfaces [e] on the display, unless this player was already replaced (a stale error must not paint the new one). */
    private fun reportError(e: DreamMediaException) {
        if (!terminated.get()) host.mediaError = e
    }

    private fun startStreams(streamSet: ActiveStreams, offsetNanos: Long) {
        if (terminated.get()) return
        heldAtEnd = false
        sessionManager.setTempo(effectiveSpeed())
        if (pauseRequested) {
            logger.debug("$debugLabel Start skipped: playback was paused while the media was being prepared.")
            lifecycle = PlayerState.Paused
            return
        }
        if (lifecycle == PlayerState.Ended) lifecycle = PlayerState.Paused
        val bootstrap = replayBootstrap?.also { replayBootstrap = null }
        val replayActive = sessionManager.isBridging()
        logger.debug(
            "$debugLabel [reappear] startStreams offset=${ms(offsetNanos)}ms " +
                    "replayActive=$replayActive bootstrap=${bootstrap != null} live=$liveStream",
        )
        if (replayActive && bootstrap != null && !liveStream) {
            if (attachLiveToReplay(streamSet, bootstrap.positionNanos)) return
            logger.debug("$debugLabel [reappear] attachLiveToReplay failed; falling back to cold start.")
        }
        host.cancelQualityHandoff()
        sessionStartNanos = System.nanoTime()
        sessionStartOffsetNanos = offsetNanos
        sessionFirstFrame = false
        audioRestartAttempts = 0
        lastAudioFailureNanos = 0L
        audioFaults.reset()
        if (warmingUp) warmUpHold.arm(offsetNanos) else warmUpHold.disarm()
        sessionManager.start(
            streamSet,
            offsetNanos,
            lastQuality,
            currentHwAccel(),
            live = liveStream,
            onFirstFrame = ::onSessionFirstFrame,
        )
        if (sessionManager.isPlaying) {
            lifecycle = PlayerState.Playing
            startWatchdog()
            refreshWarmAudioTracks()
            qualitySwitch = qualitySwitch?.silenced()
        }
    }

    private fun startWatchdog() = watchdog.start(if (relayStream) StreamWatchdog.Thresholds.LIVE_EDGE else null)

    /**
     * A live relay stream stopped delivering (never came up, dropped, or stalled): reconnect in [RELAY_RECONNECT_MS],
     * for as long as it takes, showing "waiting for stream" instead of an error. A relay with no publisher refuses
     * the connection at once, so each attempt costs a round trip.
     */
    private fun reconnectRelay(reason: String): Unit = onControl {
        if (terminated.get() || pauseRequested || lifecycle == PlayerState.Error) return@onControl
        if (!relayReconnectPending.compareAndSet(false, true)) return@onControl
        stopSession()
        waitingForStream = true
        lifecycle = PlayerState.Restarting
        val now = System.nanoTime()
        if (lastRelayWaitLogNanos == 0L || now - lastRelayWaitLogNanos >= RELAY_WAIT_LOG_INTERVAL_NS) {
            lastRelayWaitLogNanos = now
            logger.info("$debugLabel Live stream not delivering (${MediaUtil.truncate(reason)}); reconnecting every $RELAY_RECONNECT_MS ms.")
        }
        RETRY_SCHEDULER.schedule({
            safeExecute {
                relayReconnectPending.set(false)
                if (terminated.get() || pauseRequested) return@safeExecute
                val ss = streams
                if (ss != null) startStreams(ss, 0L) else dispatchInitialize()
            }
        }, RELAY_RECONNECT_MS, TimeUnit.MILLISECONDS)
    }

    /** True when [stderr] says this relay stream can never open here, so reconnecting would only loop. */
    private fun relayFailedForGood(stderr: String): Boolean = RELAY_PERMANENT_ERRORS.any { stderr.contains(it, ignoreCase = true) }

    /** Called on the pipeline thread when a session's playout begins. */
    private fun onSessionFirstFrame() {
        sessionFirstFrame = true
        if (waitingForStream) {
            waitingForStream = false
            if (relayStream) logger.info("$debugLabel Live stream is on the air.")
        }
        retryPolicy.reset()
        if (warmingUp) {
            warmUpHold.onPlayoutStart { at ->
                sessionManager.suspend(allowExternalProcess = true, retainBuffered = true, atNanos = at)
            }
            safeExecute(::finishWarmUp)
        }
    }

    private fun startWarmUp(positionNanos: Long) {
        if (!isReady || terminated.get() || liveStream || !seekable) return
        if (warmingUp) {
            if (!warmUpStale()) return
            // Never reached its first frame: drop it and open again
            logger.debug("$debugLabel Warm-up never reached its first frame; reopening.")
            warmingUp = false
            stopSession()
            lifecycle = PlayerState.Paused
        }
        if (lifecycle != PlayerState.Paused) return
        val ss = streams ?: return
        heldReady = false
        // A session parked before its first frame (or none at all) would open and buffer on resume: do that now
        if (sessionManager.isPlaying) stopSession()
        clock.reset(positionNanos)
        warmingUp = true
        warmUpStartedNanos = System.nanoTime()
        pauseRequested = false
        startStreams(ss, positionNanos)
        if (!sessionManager.isPlaying) {
            warmingUp = false
            pauseRequested = true
            lifecycle = PlayerState.Paused
        }
    }

    /** Parks a [warmUpPaused] session the moment its playout begins: decoded cushion, connection and audio all open. */
    private fun finishWarmUp() {
        if (!warmingUp || terminated.get()) return
        // Normally already parked by the pipeline, exactly where it opened; parking here instead lands as late as this
        // call was scheduled, times the speed
        val heldAtStart = warmUpHold.disarm() && sessionManager.isParked()
        warmingUp = false
        pauseRequested = true
        watchdog.stop()
        if (heldAtStart ||
            sessionManager.isPlaying && sessionManager.suspend(allowExternalProcess = true, retainBuffered = true)
        ) {
            lifecycle = PlayerState.Paused
            heldReady = true
            logger.debug("$debugLabel Held ready at ${ms(clock.originNanos)}ms.")
            return
        }
        // Could not hold the session open: fall back to an ordinary cold pause where it got to
        val heard = sessionManager.currentPacingNanos()
        clock.reset(if (heard >= 0L) heard else clock.currentTime())
        lifecycle = PlayerState.Paused
        stopSession()
    }

    private fun startReplayBootstrapVideo(boot: ReplayBootstrap) {
        if (terminated.get()) return
        val resume = boot.positionNanos.coerceAtLeast(0L)
        if (sessionManager.startReplayVideoOnly(boot.snapshot, resume, boot.positionNanos)) {
            lifecycle = PlayerState.Playing
            logger.debug("$debugLabel Replay bootstrap shown instantly, resuming at ${ms(resume)}ms.")
        }
    }

    private fun attachLiveToReplay(streamSet: ActiveStreams, liveOffsetNanos: Long): Boolean {
        env.renderExecutor.execute { host.beginQualityHandoff() }
        sessionStartNanos = System.nanoTime()
        if (!sessionManager.attachLiveAfterReplay(streamSet, liveOffsetNanos, lastQuality, currentHwAccel())) {
            env.renderExecutor.execute { host.cancelQualityHandoff() }
            return false
        }
        lifecycle = PlayerState.Playing
        startWatchdog()
        logger.debug("$debugLabel Attached live after replay at ${ms(liveOffsetNanos)}ms.")
        return true
    }

    private fun beginQualitySwitch(
        streamSet: ActiveStreams,
        offsetNanos: Long,
        hwAccelOverride: HwAccelBackend? = null,
    ) {
        if (terminated.get()) return
        sessionManager.beginQualitySwitch(streamSet, offsetNanos, lastQuality, hwAccelOverride ?: currentHwAccel())
    }

    private fun currentHwAccel(): HwAccelBackend =
        if (env.config.useHwAccel && !hwAccelDisabled) HwAccelBackend.detectDefault() else HwAccelBackend.NONE

    private fun stopSession() {
        watchdog.stop()
        sessionManager.stop()
    }

    private fun handleStreamEnd(stderr: String, normalEos: Boolean): Unit = onControl {
        if (terminated.get()) return@onControl
        if (!hwAccelDisabled && !normalEos && !clock.isRunning
            && System.nanoTime() - sessionStartNanos < HWACCEL_FAIL_WINDOW_NS
            && HwAccelBackend.looksLikeHwAccelFailure(stderr)
        ) {
            val backend = currentHwAccel().name.lowercase()
            hwAccelDisabled = true
            logger.warn(
                "$debugLabel Hardware decode failed for this stream. Falling back to software. Stderr: ${
                    MediaUtil.truncate(
                        stderr
                    )
                }."
            )
            reportFailure(
                FailureStages.DECODE, "DECODE", "Hardware decoding ($backend) failed; retrying in software",
                stderr = stderr, final = false,
            )
            noteHitch(FailureStages.DECODE, "software_fallback", "hardware decoding ($backend) failed", stderr)
            val ss = streams
            val offset = if (liveStream) 0L else sessionStartOffsetNanos
            if (ss != null) startStreams(ss, offset)
            return@onControl
        }

        if (relayStream && !relayFailedForGood(stderr)) {
            reconnectRelay(if (normalEos) "stream ended" else stderr.ifBlank { "connection lost" })
            return@onControl
        }

        val decision = retryPolicy.evaluate(stderr, normalEos, liveStream)
        if (decision != null) {
            // A live stream reaching its end is routine (its playlist rolled over), not a failure worth reporting
            if (!normalEos) {
                val stage = FailureStages.streamStage(stderr, sessionFirstFrame)
                val what = when {
                    decision.refused -> "Stream refused (403)"
                    RetryPolicy.isNotFound(stderr) -> "Stream not found (404)"
                    else -> "Transient stream error"
                }
                reportFailure(
                    stage, "NETWORK", what, stderr = stderr, retry = retryPolicy.retries,
                    maxRetries = MAX_FETCH_RETRIES, final = false,
                )
                noteHitch(stage, "retry", what, stderr)
            }
            scheduleRetry(decision)
            return@onControl
        }

        if (normalEos && !liveStream) {
            val onEnd = EndOfStreamPolicy.onEnd(host.followsServerTimeline, host.shouldLoopOnEnd)
            if (onEnd == EndOfStreamPolicy.Action.LOOP) {
                restartFromBeginning()
            } else {
                val heard = sessionManager.currentPacingNanos()
                val endNanos = if (heard >= 0L) heard else clock.currentTime()
                stopSession()
                lifecycle = PlayerState.Ended
                // A server timeline decides what follows; until then the last frame stays up
                heldAtEnd = onEnd == EndOfStreamPolicy.Action.HOLD
                // ...and the position with it: a stopped session reports the clock's origin, which would otherwise
                // still be where this session opened (0, or the last seek), not the frame on screen
                if (heldAtEnd) clock.reset(endNanos)
                if (heldAtEnd) logger.debug("$debugLabel Stream ended ahead of the server timeline; holding the last frame.")
                host.onPlaybackEnded(durationHintNanos)
            }
            return@onControl
        }

        // Retries used up on a refused stream or a connection problem: still a passing failure, which the
        // display may retry on its own later. Anything else is a verdict on the stream itself.
        val error = RetryPolicy.exhaustedError(stderr, normalEos, source)
        if (stderr.isNotEmpty()) {
            val detail = MediaUtil.truncate(stderr)
            if (error.isFatal) logger.error("$debugLabel Unrecoverable: $detail.")
            else logger.warn("$debugLabel Giving up on this stream for now (${error.message}): $detail.")
        }
        // Nothing is decoding any more: stop the session, or the watchdog would restart it behind the error,
        // leaving a player that plays while reporting an error (and so is never seekable or resynced).
        reportFailure(
            FailureStages.streamStage(stderr, sessionFirstFrame), error.kind.name, error.message.orEmpty(),
            stderr = stderr, retry = retryPolicy.retries, maxRetries = MAX_FETCH_RETRIES, final = true, fatal = error.isFatal,
        )
        stopSession()
        lifecycle = PlayerState.Error
        reportError(error)
    }

    private fun restartFromBeginning() {
        if (restartPending) return
        restartPending = true
        safeExecute {
            try {
                val ss = streams
                if (ss != null && !terminated.get() && !host.isPaused) {
                    if (lifecycle == PlayerState.Ended) lifecycle = PlayerState.Paused
                    if (!sessionManager.beginSeek(ss, 0, lastQuality, currentHwAccel())) {
                        clock.reset(0)
                        startStreams(ss, 0)
                    }
                    events.onSeek()
                }
            } finally {
                restartPending = false
            }
        }
    }

    private fun scheduleRetry(decision: RetryPolicy.Decision) {
        val delayMs = retryPolicy.nextDelay()
        val what = when {
            decision.refused -> "Stream refused (403); cache invalidated"
            decision.invalidateCache -> "Cache invalidated"
            else -> "Transient error"
        }
        logger.warn("$debugLabel $what. Retry ${retryPolicy.retries}/$MAX_FETCH_RETRIES in $delayMs ms.")
        if (decision.invalidateCache) {
            // A refusal is reported as such, so a repeated one is resolved from another source
            if (decision.refused) env.cacheInvalidator.streamRefused(url) else env.cacheInvalidator.invalidate(url)
            forgetResolvedStreamUrls()
        }
        // The retry re-initializes, which opens at the primed position: make that where the picture stopped, not where
        // this player first started (a CDN hiccup twenty minutes in must not rewind a Local video to the beginning)
        if (!liveStream) {
            val heard = sessionManager.currentPacingNanos()
            primedStartPositionNanos = (if (heard >= 0L) heard else clock.currentTime()).coerceAtLeast(0L)
        }
        lifecycle = PlayerState.Restarting
        RETRY_SCHEDULER.schedule({ dispatchInitialize() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun handleAudioFailure(stderr: String): Unit = onControl {
        if (MediaProcess.indicatesNoAudioStream(stderr)) {
            val silentUrl = streams?.currentAudio?.url
            if (silentUrl == null || sessionManager.markSourceSilent(silentUrl)) {
                logger.info("$debugLabel This source carries no audio track; playing it silently.")
            }
            return@onControl
        }
        if (relayStream) {
            // The relay dropped: the video half sees it too and reconnects both. Only sound lost on its own, while
            // pictures keep coming, is restarted here.
            val framesFlowing = System.nanoTime() - sessionManager.lastFrameNanos.get() < 1_000_000_000L
            if (sessionManager.isPlaying && framesFlowing) {
                RETRY_SCHEDULER.schedule({ safeExecute(::restartAudioOrStall) }, 500L, TimeUnit.MILLISECONDS)
            }
            return@onControl
        }
        if (!liveStream && durationHintNanos > 0L && durationHintNanos - clock.currentTime() <= AUDIO_EOS_NEAR_END_GUARD_NS) {
            logger.debug("$debugLabel Audio pipe ended near VOD end (pos=${clock.currentTime()}, dur=$durationHintNanos); deferring to video EOS.")
            return@onControl
        }
        if (liveStream && sessionManager.audioFeederUnreadable()) {
            // An encrypted live playlist: reopen the sound through FFmpeg's own HLS reader, which has the key
            restartAudioOrStall()
            return@onControl
        }
        if (liveStream && sessionManager.audioSourceGone()) {
            handleSessionStall("live audio source stopped serving")
            return@onControl
        }
        if (liveStream && sessionManager.audioSpliced()) {
            restartAudioOrStall()
            return@onControl
        }
        val now = System.nanoTime()
        if (lastAudioFailureNanos != 0L && now - lastAudioFailureNanos > AUDIO_RESTART_BUDGET_RESET_NS) {
            audioRestartAttempts = 0
        }
        lastAudioFailureNanos = now
        val attempt = ++audioRestartAttempts
        reportFailure(
            FailureStages.AUDIO, "NETWORK", "Audio ended", stderr = stderr, retry = attempt,
            maxRetries = if (liveStream) MAX_AUDIO_RESTARTS else 0, final = false,
        )
        if (liveStream && sessionManager.isPlaying && attempt <= MAX_AUDIO_RESTARTS) {
            logger.warn(
                "$debugLabel Live audio ended (${MediaUtil.truncate(stderr)}); " +
                        "restarting audio only ($attempt/$MAX_AUDIO_RESTARTS), video keeps playing."
            )
            RETRY_SCHEDULER.schedule({ safeExecute(::restartAudioOrStall) }, attempt * 1_000L, TimeUnit.MILLISECONDS)
            return@onControl
        }
        handleSessionStall("audio ended: ${MediaUtil.truncate(stderr)}.")
    }

    /**
     * An audio half's PCM ended with a clean exit. Live media takes the usual audio-failure path. On a VOD, sound that
     * stops before the picture is either a track shorter than the video or a cut connection: only the sound is reopened
     * at the playhead, and a reopen that ends cleanly without decoding anything ([hadPcm] false, nothing on stderr)
     * means the track is over there, so the picture plays on silently. Neither ever restarts the picture, which used to
     * turn every such end (and every natural end of a file whose length is unknown) into a stall loop.
     */
    private fun handleAudioEnd(stderr: String, hadPcm: Boolean, isCurrent: () -> Boolean): Unit = onControl {
        val live = liveStream || relayStream
        if (!live && (terminated.get() || !isCurrent())) return@onControl
        val action = AudioEndPolicy.onEnd(
            live = live,
            noAudioStream = MediaProcess.indicatesNoAudioStream(stderr),
            hadPcm = hadPcm,
            stderrBlank = stderr.isBlank(),
            nearEnd = durationHintNanos > 0L && durationHintNanos - clock.currentTime() <= AUDIO_EOS_NEAR_END_GUARD_NS,
            restartAllowed = { audioFaults.allowRestart(System.nanoTime()) },
        )
        when (action) {
            AudioEndPolicy.Action.FAILURE_PATH -> handleAudioFailure(stderr)
            AudioEndPolicy.Action.FINISHED -> {
                sessionManager.markAudioFinished()
                logger.debug("$debugLabel Audio track has no more sound at ${getCurrentTime() / 1_000_000} ms; the picture plays on.")
            }
            AudioEndPolicy.Action.DEFER_TO_VIDEO ->
                logger.debug("$debugLabel Audio pipe ended near VOD end (pos=${clock.currentTime()}, dur=$durationHintNanos); deferring to video EOS.")
            AudioEndPolicy.Action.REOPEN_AUDIO -> {
                val ss = streams ?: return@onControl
                val at = getCurrentTime()
                logger.debug("$debugLabel Audio ended before the picture at ${at / 1_000_000} ms; reopening the sound there.")
                if (!sessionManager.restartAudio(ss, at)) handleAudioFailure(stderr)
            }
        }
    }

    /**
     * A live audio half broke (the sink already logged why): restart it in place at the playhead right away, so the
     * picture never plays on in silence. Live streams, sources without audio, the last seconds of a VOD and a fault
     * that keeps recurring take the usual audio-failure path instead.
     */
    private fun handleAudioFault(reason: String, stderr: String, isCurrent: () -> Boolean): Unit = onControl {
        if (terminated.get() || !isCurrent()) return@onControl
        val ss = streams ?: return@onControl
        val nearEnd = durationHintNanos > 0L && durationHintNanos - clock.currentTime() <= AUDIO_EOS_NEAR_END_GUARD_NS
        val route = AudioFaultRoute.of(
            deviceChanged = reason.startsWith(AudioSink.DEVICE_CHANGED_REASON), live = liveStream, nearEnd = nearEnd,
            noAudioStream = MediaProcess.indicatesNoAudioStream(stderr),
            budgetAllows = { audioFaults.allowRestart(System.nanoTime()) },
        )
        when (route) {
            AudioFaultRoute.Route.FAILURE_PATH -> {
                handleAudioFailure(stderr)
                return@onControl
            }
            AudioFaultRoute.Route.RESTART_LIVE -> {
                restartAudioOrStall()
                return@onControl
            }
            AudioFaultRoute.Route.RESTART_IN_PLACE -> {}
        }
        val at = getCurrentTime()
        volume.reapply()
        if (!sessionManager.restartAudio(ss, at)) {
            logger.debug("$debugLabel Audio restart after a fault ($reason) not possible now; the self-heal retries.")
        }
    }

    private fun restartAudioOrStall() {
        val ss = streams
        if (terminated.get() || ss == null) return
        if (!sessionManager.restartAudio(ss, 0L)) {
            handleSessionStall("audio restart not possible in current session state")
        }
    }

    private fun handleSessionStall(reason: String): Unit = onControl {
        // An errored player waits for the display's retry; restarting it here would play behind the error
        if (terminated.get() || lifecycle == PlayerState.Error) return@onControl
        if (relayStream) {
            reconnectRelay(reason)
            return@onControl
        }
        val ss = streams ?: return@onControl
        val now = System.nanoTime()
        val repeated = lastStallNanos != 0L && now - lastStallNanos < REPEATED_STALL_WINDOW_NS
        lastStallNanos = now
        if (!repeated) stallReResolves = 0
        if (repeated || liveStream) {
            val kind = if (liveStream) "Live stall" else "Repeated stall"
            val attempt = ++stallReResolves
            if (attempt > MAX_STALL_RERESOLVES) {
                logger.error("$debugLabel $kind ($reason); giving up after $MAX_STALL_RERESOLVES re-resolves.")
                reportFailure(
                    FailureStages.STALL, "NETWORK", "Stream keeps stalling: $reason", stderr = reason,
                    retry = MAX_STALL_RERESOLVES, maxRetries = MAX_STALL_RERESOLVES, final = true,
                )
                lifecycle = PlayerState.Error
                // Almost always the connection rather than the file: non-fatal, so the display may
                // try again later on its own.
                reportError(DreamMediaException.Network("Stream keeps stalling"))
                return@onControl
            }
            val delayMs = STALL_RERESOLVE_BACKOFF_MS[(attempt - 1).coerceAtMost(STALL_RERESOLVE_BACKOFF_MS.lastIndex)]
            reportFailure(
                FailureStages.STALL, "NETWORK", "$kind: $reason", stderr = reason, retry = attempt,
                maxRetries = MAX_STALL_RERESOLVES, final = false,
            )
            noteHitch(FailureStages.STALL, "re_resolve", "$kind: $reason", reason, frozenSinceLastFrameMs())
            logger.warn(
                "$debugLabel $kind ($reason); invalidating cached URLs and re-resolving " +
                        "($attempt/$MAX_STALL_RERESOLVES, in $delayMs ms)."
            )
            env.cacheInvalidator.invalidate(url)
            forgetResolvedStreamUrls()
            primedStartPositionNanos = if (liveStream) 0L else clock.currentTime()
            lifecycle = PlayerState.Restarting
            if (delayMs == 0L) dispatchInitialize()
            else RETRY_SCHEDULER.schedule({ dispatchInitialize() }, delayMs, TimeUnit.MILLISECONDS)
        } else {
            logger.warn("$debugLabel Stream stalled ($reason); restarting.")
            noteHitch(FailureStages.STALL, "restart", "stream stalled: $reason", reason, frozenSinceLastFrameMs())
            val pos = if (liveStream) 0L else clock.currentTime()
            if (sessionManager.beginSeek(ss, pos, lastQuality, currentHwAccel())) {
                startWatchdog()
            } else {
                startStreams(ss, pos)
            }
        }
    }

    /** How long the picture has not changed, in ms (0 when unknown). */
    private fun frozenSinceLastFrameMs(): Long {
        val last = sessionManager.lastFrameNanos.get()
        return if (last <= 0L) 0L else ((System.nanoTime() - last) / 1_000_000L).coerceIn(0L, 600_000L)
    }

    private fun forgetResolvedStreamUrls() {
        val ss = streams ?: return
        MediaHostGuard.invalidate(ss.currentVideo.url)
        MediaHostGuard.invalidate(ss.currentAudio.url)
    }

    private fun doStop() {
        heldAtEnd = false
        warmUpHold.disarm()
        warmingUp = false
        heldReady = false
        lifecycle = PlayerState.Stopped
        sessionManager.clearFrame()
        stats.stop()
        stopSession()
        sessionManager.cleanup()
        streams = null
        // The read-ahead buffer only serves a playing display; the next player re-fills it on demand.
        ReadAhead.release(host.uuid)
    }

    private fun doSeek(nanos: Long, fire: Boolean) {
        if (!isReady || !seekable) return
        val fromHeldEnd = heldAtEnd && lifecycle == PlayerState.Ended
        heldAtEnd = false
        if (lifecycle == PlayerState.Ended) lifecycle = PlayerState.Paused
        if (isPausedWarm()) freezePausedWarmSession()
        heldReady = false
        clock.reset(nanos)
        // A retry or re-resolve still pending opens at the primed position: a seek made meanwhile moves it there
        if (lifecycle == PlayerState.Restarting && !liveStream) primedStartPositionNanos = nanos
        val ss = streams ?: return
        if (warmingUp) {
            // Still opening a paused stream to hold it ready: open it at the new position instead
            warmUpStartedNanos = System.nanoTime()
            startStreams(ss, nanos)
        } else if (sessionManager.isPlaying && !sessionManager.isParked()) {
            if (sessionManager.beginSeek(ss, nanos, lastQuality, currentHwAccel())) {
                noteHitch(FailureStages.SEEK, "in_place_seek", "seek to ${nanos / 1_000_000} ms")
            } else {
                logger.warn("$debugLabel Seek to ${nanos / 1_000_000} ms fell back to a full stream restart.")
                noteHitch(FailureStages.SEEK, "restart", "seek to ${nanos / 1_000_000} ms fell back to a stream restart")
                startStreams(ss, nanos)
            }
        } else if (fromHeldEnd && !host.isPaused) {
            // Held on the last frame and the server timeline moved off the end (looped, or seeked back): play on
            startStreams(ss, nanos)
        }
        if (fire) events.onSeek()
    }

    private fun changeQuality(desired: VideoQuality, userInitiated: Boolean) {
        val ss = streams ?: return
        val target = desired.targetHeight ?: return
        if (target == lastQuality || target == lastRequestedQuality) {
            if (DEBUG) logger.debug(
                "$debugLabel Quality switch no-op target=$target last=$lastQuality requested=$lastRequestedQuality."
            )
            return
        }
        lastRequestedQuality = target
        if (liveStream) {
            logger.debug("$debugLabel Live quality switch to ${target}p; re-resolving and restarting.")
            if (userInitiated) qualitySwitch = QualitySwitch(System.nanoTime(), announced = true, rollback = null)
            primedStartPositionNanos = 0L
            lifecycle = PlayerState.Restarting
            dispatchInitialize(coalesce = true)
            return
        }
        val newSs = MediaStreamSelector.switchQuality(ss, target) ?: return
        // Only now that there is a stream to switch to: a no-op request must not drop a warm pause
        if (isPausedWarm()) freezePausedWarmSession()
        heldReady = false
        val previousQuality = lastQuality
        media = media?.copy(streams = newSs, quality = MediaStreamSelector.parseQuality(newSs.currentVideo))
        host.videoContentAspect = newSs.currentVideo.contentAspect()
        env.renderExecutor.execute {
            if (sessionManager.isPlaying && !sessionManager.isParked()) {
                // Parallel quality switch: stage the new-resolution texture, but the live video keeps
                // decoding and rendering. The new resolution warms up in a second channel; fitTexture
                // promotes both (channel + texture) on its first frame, so the picture never freezes.
                // A genuine handoff failure rolls the metadata above back (see handleQualitySwitchAborted).
                host.beginQualityHandoff()
                safeExecute {
                    // Announced only here: a real handoff outlives the click by seconds, whereas the direct
                    // swap below finishes inside this very block, so flagging it would only ever flicker.
                    qualitySwitch = QualitySwitch(
                        System.nanoTime(), announced = userInitiated,
                        rollback = QualityRollback(ss, previousQuality, target),
                    )
                    beginQualitySwitch(newSs, getCurrentTime())
                }
            } else {
                // Nothing decoding (so, just paused): no frames would arrive to drive a handoff, so swap
                // directly — there's no async attempt in flight to roll back if this fails later.
                host.reloadTexture()
                safeExecute {
                    // A direct swap is done the moment it returns: there is no handoff to wait out.
                    qualitySwitch = null
                    clock.moveTo(getCurrentTime())
                }
            }
        }
    }

    private fun changeAudioTrack(trackUrl: String) {
        val ss = streams ?: return
        if (trackUrl == ss.currentAudio.url) return
        val newSs = MediaStreamSelector.switchAudioTrack(ss, trackUrl) ?: return
        val previousUrl = ss.currentAudio.url
        streams = newSs
        if (WarmPausePolicy.audioTrackChangeReopens(isPausedWarm())) {
            // Neither switch runs on a parked session, and resuming it would play the old track on: drop it, so
            // the resume opens the new track at the paused position
            freezePausedWarmSession()
            refreshWarmAudioTracks()
            return
        }
        audioTrackSwitching = true
        val seamless = sessionManager.beginAudioTrackSwitch(newSs) { onControl { revertAudioTrack(trackUrl, previousUrl) } }
        refreshWarmAudioTracks()
        if (seamless) return
        env.renderExecutor.execute {
            safeExecute {
                sessionManager.restartAudio(newSs, getCurrentTime())
                audioTrackSwitching = false
            }
        }
    }

    /**
     * A switch to [failedUrl] never went live and [previousUrl] still plays: the selection goes back to it, so the
     * menu shows what is heard and picking [failedUrl] again really retries.
     */
    private fun revertAudioTrack(failedUrl: String, previousUrl: String) {
        val current = streams ?: return
        if (current.currentAudio.url != failedUrl) return
        streams = MediaStreamSelector.switchAudioTrack(current, previousUrl) ?: return
        logger.warn("$debugLabel Audio-track switch did not go live; the previous track stays selected.")
        refreshWarmAudioTracks()
    }

    /** Until when ([System.nanoTime]) the other audio tracks are kept warm; see [getAvailableAudioTracks]. */
    @Volatile
    private var warmTracksUntilNanos = System.nanoTime()

    private fun noteTrackListShown() {
        val now = System.nanoTime()
        val wasOff = now - warmTracksUntilNanos >= 0L
        warmTracksUntilNanos = now + WARM_TRACKS_AFTER_LIST_NS
        if (wasOff) {
            safeExecute(::refreshWarmAudioTracks)
            scheduleWarmTracksExpiry(WARM_TRACKS_AFTER_LIST_NS)
        }
    }

    private fun scheduleWarmTracksExpiry(delayNanos: Long) {
        if (terminated.get()) return
        RETRY_SCHEDULER.schedule({
            safeExecute {
                val left = warmTracksUntilNanos - System.nanoTime()
                if (left > 0L) scheduleWarmTracksExpiry(left) else refreshWarmAudioTracks()
            }
        }, delayNanos, TimeUnit.NANOSECONDS)
    }

    private fun refreshWarmAudioTracks() {
        val ss = streams
        // Each warm track is an FFmpeg process re-spawned (and re-downloading) every few seconds, so only while the
        // viewer might pick one
        if (ss == null || liveStream || System.nanoTime() - warmTracksUntilNanos >= 0L) {
            sessionManager.setWarmAudioTracks([])
            return
        }
        val playing = ss.currentAudio.url
        sessionManager.setWarmAudioTracks(
            audioTracks()
                .filter { it.url != playing }
                .map { WarmTrack(it.url, it.seekByDecoding) },
        )
    }

    private fun isPausedWarm(): Boolean =
        lifecycle == PlayerState.Paused && sessionManager.isParked()

    /** The speed sessions should run at: the requested one, except for media that cannot be retimed. */
    private fun effectiveSpeed(): Double =
        if (liveStream || (media != null && !seekable)) PlaybackSpeed.NORMAL else requestedSpeed

    /**
     * Moves the session to [requestedSpeed]. Audio processes are time-stretched at spawn, so a playing session is
     * re-seeked in place to the position being heard; anything not playing just records it for its next start.
     */
    private fun applySpeed() {
        if (terminated.get()) return
        val target = effectiveSpeed()
        if (target == sessionManager.tempo) return
        // A warm pause keeps the old-speed processes and their buffered PCM; resume must start fresh instead
        if (isPausedWarm()) freezePausedWarmSession()
        // A parked (out of range) session is retimed when it is un-parked
        if (sessionManager.isParked()) return
        val playing = isReady && sessionManager.isPlaying
        val position = if (playing) getSyncPositionNanos() else clock.currentTime()
        sessionManager.setTempo(target)
        if (!playing || streams == null) {
            clock.moveTo(position)
            return
        }
        logger.debug("$debugLabel Speed ${target}x: restarting in place at ${ms(position)} ms.")
        doSeek(position.coerceAtLeast(0L), fire = false)
    }

    private fun freezePausedWarmSession() {
        heldReady = false
        val pos = sessionManager.parkedPositionNanos() ?: getCurrentTime()
        stopSession()
        clock.reset(pos)
        lifecycle = PlayerState.Paused
    }

    private fun drainInitCallbacks(run: Boolean) {
        if (run) initDrained = true
        // Remove one at a time by identity so a callback added mid-drain is never wiped without running
        while (true) {
            val cb = initCallbacks.firstOrNull() ?: break
            if (initCallbacks.remove(cb) && run) cb()
        }
    }

    private fun safeExecute(action: () -> Unit) {
        if (!terminated.get() && !controlExecutor.isShutdown)
            runCatching { controlExecutor.submit(action) }
    }
}
