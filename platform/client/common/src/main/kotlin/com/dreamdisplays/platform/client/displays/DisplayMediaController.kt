package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.core.services.keys.MediaServices
import com.dreamdisplays.core.services.keys.AudioAcousticsServices
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.core.protocol.common.packets.DisplaySync
import com.dreamdisplays.core.protocol.common.packets.ReportDuration
import com.dreamdisplays.core.share.ScreenShareViewerRules
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.player.platform.DisplayPlaybackHost
import com.dreamdisplays.platform.client.player.platform.DreamPlaybackEnvironment
import com.dreamdisplays.platform.client.storage.WatchedVideoStore
import kotlinx.atomicfu.atomic
import net.minecraft.client.Minecraft
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Owns the media / player lifecycle for a single [DisplayScreen]: swapping in a fresh [MediaPlayer] on
 * URL change, generation-guarding async init callbacks against stale players, applying the screen's
 * saved state on start, and teardown.
 */
internal class DisplayMediaController(private val screen: DisplayScreen) {
    private val generation = atomic(0L)

    /** The active media player, or null between videos and after [shutdown]. */
    @Volatile
    var player: MediaPlayer? = null; private set

    /** True once [start] has applied the screen's initial state to the current player. */
    var videoStarted: Boolean = false; private set

    /** Current player generation; bumped on every swap so stale async callbacks can be detected. */
    val generationNow: Long get() = generation.value

    /**
     * Stops any current player, creates a fresh [MediaPlayer] for [videoUrl], wires the texture and
     * popout sinks, and defers [start] until the player reports initialized. When
     * [preservePausedState] is true the screen's current paused state is reapplied after start.
     */
    fun load(videoUrl: String, lang: String, preservePausedState: Boolean) {
        if (videoUrl == "") {
            // Nothing to play (the server cleared the display): stop whatever plays and remember it is empty, so the
            // next description of the same empty display is no change at all
            if (emptyLoadUnloads(screen.videoUrl, player != null || pendingSwitch != null)) unload(lang)
            return
        }
        pendingSwitch?.let {
            if (it.url == videoUrl && it.lang == lang) return // The same switch again
        }
        // Read before a replaced switch lets go of it: the video still on screen
        val shownGen = ServerMediaGens.beforeLatest(screen.uuid)
        cancelPendingSwitch()

        // A queue entry this client preloaded in the background becomes the player as is: already resolved,
        // buffered and parked warm on its first frame
        val taken = screen.queuePreloader.takeDetached(videoUrl, lang)
        // Never a seamless hand-off away from a screen share: its last frame must not stay up until the next video's
        // first one
        if (taken != null && screen.followsServerTimeline && player != null && videoStarted && !screen.isPaused &&
            ScreenShareViewerRules.maySwapSeamlessly(screen.videoUrl)
        ) {
            // The server switches a moment before the current video ends and schedules the next one at its end
            // instant: this video plays on (or holds its last frame) until then, see onTimelineWhileSwitching
            pendingSwitch = PendingSwitch(videoUrl, lang, preservePausedState, taken).also {
                arm(it, SWITCH_FALLBACK_MS.toDouble()) { completePendingSwitch(it, seamless = false) }
            }
            // A seek or pause until then acts on the video still playing: keep talking about that one
            ServerMediaGens.hold(screen.uuid, shownGen)
            return
        }
        taken?.adopt()
        swapIn(videoUrl, lang, preservePausedState, taken?.player, seamless = false)
    }

    /**
     * The display has nothing to play any more (a screen share ended on a display that had no video before, or the
     * server cleared its link): the current player stops and nothing replaces it.
     */
    fun unload(lang: String) {
        cancelPendingSwitch()
        generation.incrementAndGet()
        val oldPlayer = player
        // Its popouts and menu preview hold the last picture: nothing of the video stays up
        screen.dropPopouts(oldPlayer)
        player = null
        videoStarted = false
        screen.mediaError = null
        screen.timelineFollower.reset()
        oldPlayer?.setPopoutSink(null)
        oldPlayer?.setPreviewSink(null)
        oldPlayer?.stop()
        screen.onVideoCleared(lang)
        DisplayRegistry.recordScreen(screen)
        Minecraft.getInstance().execute { screen.reloadTextureIfRegistered() }
    }

    /** A queue switch waiting for its scheduled start: the current player keeps playing until then. */
    private class PendingSwitch(
        val url: String, val lang: String, val preservePausedState: Boolean, val taken: QueuePreloader.Taken,
    ) {
        var timer: ScheduledFuture<*>? = null

        /** Only the timer armed last may act: cancelling one cannot recall it once handed to the client thread. */
        val latest = LatestTimer()
    }

    /** (Re-)arms [p]'s timer to run [action] on the client thread in [delayMs], superseding any earlier one. */
    private fun arm(p: PendingSwitch, delayMs: Double, action: () -> Unit) {
        p.timer?.cancel(false)
        val id = p.latest.arm()
        p.timer = schedule(delayMs) { if (p.latest.isLatest(id)) action() }
    }

    private var pendingSwitch: PendingSwitch? = null

    /** True while a preloaded queue entry waits for its scheduled start. */
    val isSwitchPending: Boolean get() = pendingSwitch != null

    /**
     * The new video's timeline arrived while its switch is pending. A start scheduled ahead ([DisplaySync.startInMs])
     * is kept for that instant: the current video stays up to it, then the preloaded player takes over on its first
     * frame, with no hold, placeholder or fade. Anything else switches now. Returns true when [packet] was taken
     * (it is applied at the switch); false when the caller applies it as usual.
     */
    fun onTimelineWhileSwitching(packet: DisplaySync): Boolean {
        val p = pendingSwitch ?: return false
        val waitMs = if (packet.isPaused || packet.startInMs <= 0) 0.0
        else packet.startInMs - TimelineFollower.oneWayLatencyMs()
        if (waitMs < MIN_SCHEDULE_MS) {
            completePendingSwitch(p, seamless = true)
            return false
        }
        val receivedNanos = System.nanoTime()
        arm(p, waitMs) {
            if (completePendingSwitch(p, seamless = true)) {
                // Replayed with what is left of the wait: negative when a stall made this late, so the follower
                // starts the new video where it has got to by now (0 would mean "no scheduled start")
                val elapsedMs = (System.nanoTime() - receivedNanos) / 1_000_000.0
                screen.updateData(packet.copy(startInMs = replayedStartInMs(packet.startInMs, elapsedMs)))
            }
        }
        return true
    }

    private fun schedule(delayMs: Double, action: () -> Unit): ScheduledFuture<*> =
        TimelineFollower.START_SCHEDULER.schedule({
            Minecraft.getInstance().execute(action)
        }, (delayMs * 1_000.0).toLong(), TimeUnit.MICROSECONDS)

    private fun completePendingSwitch(p: PendingSwitch, seamless: Boolean): Boolean {
        if (pendingSwitch !== p) return false
        pendingSwitch = null
        ServerMediaGens.release(screen.uuid)
        p.timer?.cancel(false)
        p.taken.adopt()
        swapIn(p.url, p.lang, p.preservePausedState, p.taken.player, seamless)
        return true
    }

    private fun cancelPendingSwitch() {
        val p = pendingSwitch ?: return
        pendingSwitch = null
        ServerMediaGens.release(screen.uuid)
        p.timer?.cancel(false)
        p.taken.player.stop()
    }

    /**
     * Replaces the current player with [preloaded] (adopted already) or a fresh one. [seamless]: a preloaded queue
     * entry starting on schedule; the texture keeps the old video's last frame until the new one's first frame
     * replaces it, with no placeholder or fade in between.
     */
    private fun swapIn(
        videoUrl: String, lang: String, preservePausedState: Boolean, preloaded: MediaPlayer?, seamless: Boolean,
    ) {
        if (preloaded == null) {
            DreamServices.registry.getOrNull(MediaServices.RESOLVER_REGISTRY)?.prefetch(MediaSource.from(videoUrl))
        }

        val expected = generation.incrementAndGet()
        playingSampledAtNanos = 0L
        val oldPlayer = player
        player = null
        videoStarted = false
        screen.mediaError = null
        screen.timelineFollower.reset()
        // The old player's decoder runs on for a moment after stop(): it must not feed the popouts and menu preview
        // the new player is about to feed (two producers on one frame handoff)
        oldPlayer?.setPopoutSink(null)
        oldPlayer?.setPreviewSink(null)
        oldPlayer?.stop()

        screen.onVideoSwapped(videoUrl, lang, awaitTimeline = !seamless)
        DisplayRegistry.recordScreen(screen)
        // The adopted player stays parked until the server timeline starts it, so its warm session survives
        // the start hold instead of playing ahead and being snapped back with a stream restart.
        if (preloaded != null) screen.paused = true
        val shouldBePaused = preservePausedState && screen.paused
        val audioStage = DreamServices.registry.getOrNull(AudioAcousticsServices.ACOUSTICS)?.registerSource(screen.uuid)
        val newPlayer = preloaded?.also { it.attachAudioStage(audioStage) } ?: MediaPlayer(
            videoUrl, lang, DisplayPlaybackHost(screen), DreamPlaybackEnvironment,
            screen.takeReplayBootstrap(videoUrl), audioStage,
        )
        player = newPlayer
        // Before the stream opens, so the first session already plays at the display's speed
        screen.syncPlaybackSpeed()
        if (preloaded != null) screen.timelineFollower.onWarmAdopted()
        screen.timelineFollower.onPlayerCreated()
        // Set the effective volume (incl. distance) now, before the bridge prelude (which starts at
        // construction) becomes audible — otherwise its first moment plays at the un-attenuated level.
        screen.primeNewPlayerVolume(newPlayer)
        if (!seamless) screen.prepareTextureDimensions()

        screen.attachPopout(newPlayer)

        whenInitialized(expected) {
            start()
            if (shouldBePaused) {
                screen.paused = true
                player?.pause()
            }
            reportDuration()
        }

        if (!seamless) Minecraft.getInstance().execute { screen.reloadTextureIfRegistered() }
    }

    /** Applies volume, brightness, and paused state to the player, then seeks to the saved position. */
    fun start() {
        val mp = player ?: return
        videoStarted = true
        screen.onMediaStarted()
        (screen.videoUrl?.let(MediaSource::from) as? MediaSource.YouTube)?.let { WatchedVideoStore.markWatched(it.videoId) }
        screen.applyEffectiveVolume()
        mp.setBrightness(screen.brightness)
        // The distance / size steps may have moved while the stream resolved; a no-op when they didn't.
        screen.reloadQuality()
        if (screen.paused) mp.pause() else {
            mp.play()
            screen.paused = false
        }
        // A replay-bootstrap player already resumes at the saved position; restoreSavedTime()'s
        // corrective seek would cold-restart the session and destroy the seamless replay -> live bridge.
        if (!mp.isResumingFromReplay()) screen.restoreSavedTime()
        DisplayRegistry.recordScreen(screen)
    }

    /** What was last reported: media generation, URL and length (ms); nothing is sent twice unless asked. */
    private var lastDurationReport: Triple<Long, String, Long>? = null

    /** Length measured at a normal end of the stream, for the URL it was measured on (0 = none). */
    private var observedEnd: Pair<String, Long>? = null

    /** Content position last seen while the clock ran (ns), and when (monotonic ns); see [onStreamEnded]. */
    private var playingPositionNanos = 0L
    private var playingSampledAtNanos = 0L

    /**
     * Reports what this client knows of the video's length: the length it measured at the end of the stream once it
     * has one (it beats a header that was wrong or rounded), else its metadata length. Sent in every mode (the server
     * keeps a Local display's length for when it is shared again), and only when the media generation, URL or length
     * differ from the last report; [force]: the server asked, so it is sent even then.
     */
    internal fun reportDuration(force: Boolean = false) {
        val url = screen.videoUrl.orEmpty()
        if (url.isEmpty()) return
        val metadataMs = screen.mediaPlayerDurationNanos / 1_000_000L // 0 for live streams and unreadable headers
        val observedMs = observedEnd?.takeIf { it.first == url }?.second ?: 0L
        val ms = if (observedMs > 0L) observedMs else metadataMs
        if (ms <= 0L) return
        sendDuration(url, ms, observedEnd = observedMs > 0L, force = force)
    }

    private fun sendDuration(url: String, ms: Long, observedEnd: Boolean, force: Boolean) {
        val gen = ServerMediaGens.onScreen(screen.uuid)
        val key = Triple(gen, url, ms)
        if (!force && key == lastDurationReport) return
        lastDurationReport = key
        Initializer.sendPacket(ReportDuration(screen.uuid, ms, url, gen, observedEnd))
    }

    /** Called every tick: remembers where a running clock is, so a normal end of stream knows where it ended. */
    internal fun noteProgress() {
        if (!videoStarted || !screen.isClockRunning()) return
        playingPositionNanos = screen.syncPositionNanos
        playingSampledAtNanos = System.nanoTime()
    }

    /**
     * The stream ended normally (on the player's thread, at its end). When its header gave no length, one that is
     * off, or one rounded to whole seconds, report where it actually ended: the server's clock then ends, loops and
     * moves the queue on at the real end (instead of never, or up to a second late). Measured from the last running
     * position plus the time played since, so a game stall over the end does not lose it.
     */
    internal fun onStreamEnded() {
        val url = screen.videoUrl.orEmpty()
        if (url.isEmpty() || playingSampledAtNanos == 0L) return
        val ageMs = (System.nanoTime() - playingSampledAtNanos) / 1_000_000L
        val metadataMs = screen.mediaPlayerDurationNanos / 1_000_000L
        val ms = observedEndToReport(playingPositionNanos / 1_000_000L, ageMs, metadataMs, screen.playbackSpeed) ?: return
        // Every loop ends again at about the same place: one report is enough
        val known = observedEnd?.takeIf { it.first == url }?.second
        if (known != null && kotlin.math.abs(known - ms) <= OBSERVED_END_RESEND_MS) return
        observedEnd = url to ms
        sendDuration(url, ms, observedEnd = true, force = false)
    }

    private companion object {
        /** Switch anyway when the new video's timeline never comes. */
        const val SWITCH_FALLBACK_MS = 2_000L

        /** A scheduled start closer than this switches at once. */
        const val MIN_SCHEDULE_MS = 2.0
    }

    /** Runs [action] once the current player is initialized; guards against stale generations. */
    fun whenInitialized(action: () -> Unit) = whenInitialized(generation.value, action)

    private fun whenInitialized(expectedGeneration: Long, action: () -> Unit) {
        val mp = player ?: return
        mp.whenInitialized {
            if (expectedGeneration != generation.value) return@whenInitialized
            if (mp !== player) return@whenInitialized
            if (screen.errored) return@whenInitialized
            action()
        }
    }

    /** Detaches the current player and invalidates pending callbacks; returns it for final teardown. */
    fun shutdown(): MediaPlayer? {
        cancelPendingSwitch()
        generation.incrementAndGet()
        videoStarted = false
        val current = player
        player = null
        return current
    }
}

/** A measured end this far from the header's length (ms) is reported: the header was wrong, not just rounded down. */
internal const val OBSERVED_END_TOLERANCE_MS = 1_500L

/**
 * A position sampled longer ago than this (ms) before the end of stream says too little about where it ended to
 * correct a header length. With no header length at all, any estimate beats none: up to [OBSERVED_END_MAX_GUESS_AGE_MS].
 */
internal const val OBSERVED_END_MAX_SAMPLE_AGE_MS = 500L
internal const val OBSERVED_END_MAX_GUESS_AGE_MS = 15_000L

/** A later end of stream this close to the one reported (ms) is not reported again. */
internal const val OBSERVED_END_RESEND_MS = 250L

/**
 * The length to report after a normal end of stream, or null for none: the content position last seen with the clock
 * running ([positionMs], sampled [sampleAgeMs] before the end) plus what played since at [rate]. Reported when the
 * header gave no length ([metadataMs] 0), one rounded to whole seconds (so up to a second short; the server schedules
 * such lengths a second long to be safe), or one off by more than [OBSERVED_END_TOLERANCE_MS].
 */
internal fun observedEndToReport(positionMs: Long, sampleAgeMs: Long, metadataMs: Long, rate: Double = 1.0): Long? {
    val maxAgeMs = if (metadataMs > 0L) OBSERVED_END_MAX_SAMPLE_AGE_MS else OBSERVED_END_MAX_GUESS_AGE_MS
    if (positionMs <= 0L || sampleAgeMs !in 0..maxAgeMs) return null
    val endMs = positionMs + (sampleAgeMs * rate.coerceIn(0.0, 4.0)).roundToLong()
    val wholeSeconds = metadataMs % 1_000L == 0L
    if (metadataMs > 0L && !wholeSeconds && kotlin.math.abs(endMs - metadataMs) <= OBSERVED_END_TOLERANCE_MS) return null
    return endMs
}

/**
 * The startInMs a switch packet scheduled [startInMs] ahead is replayed with [elapsedMs] after it arrived: what is left
 * of the wait, negative once late (never 0, which means no scheduled start at all).
 */
internal fun replayedStartInMs(startInMs: Int, elapsedMs: Double): Int =
    (startInMs - elapsedMs).roundToInt().let { if (it == 0) -1 else it }

/** Ids for a timer that gets re-armed: only the latest arming may act. */
internal class LatestTimer {
    private var current = 0L

    /** A new arming; every earlier one is superseded. */
    fun arm(): Long = ++current

    fun isLatest(id: Long): Boolean = id == current
}

/**
 * Whether loading an empty link unloads: something still plays ([hasPlayer]) or the display does not yet know it is
 * empty ([shownUrl] is not ""). False for an empty display described again.
 */
internal fun emptyLoadUnloads(shownUrl: String?, hasPlayer: Boolean): Boolean = hasPlayer || shownUrl != ""
