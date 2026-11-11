package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.core.services.keys.MediaServices
import com.dreamdisplays.core.services.keys.AudioAcousticsServices
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.playback.model.PlaybackMode
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
        if (videoUrl == "") return
        pendingSwitch?.let {
            if (it.url == videoUrl && it.lang == lang) return // The same switch again
            cancelPendingSwitch()
        }

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
                it.timer = schedule(SWITCH_FALLBACK_MS.toDouble()) { completePendingSwitch(it, seamless = false) }
            }
            return
        }
        taken?.adopt()
        swapIn(videoUrl, lang, preservePausedState, taken?.player, seamless = false)
    }

    /**
     * The display has nothing to play any more after a private stream (a screen share ended on a display that had no
     * video before): the current player stops and nothing replaces it. The caller has already dropped the picture.
     */
    fun unload(lang: String) {
        cancelPendingSwitch()
        generation.incrementAndGet()
        val oldPlayer = player
        player = null
        videoStarted = false
        screen.mediaError = null
        screen.timelineFollower.reset()
        oldPlayer?.setPopoutSink(null)
        oldPlayer?.setPreviewSink(null)
        oldPlayer?.stop()
        screen.onVideoCleared(lang)
        DisplayRegistry.recordScreen(screen)
        Minecraft.getInstance().execute { screen.reloadTexture() }
    }

    /** A queue switch waiting for its scheduled start: the current player keeps playing until then. */
    private class PendingSwitch(
        val url: String, val lang: String, val preservePausedState: Boolean, val taken: QueuePreloader.Taken,
    ) {
        var timer: ScheduledFuture<*>? = null
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
        p.timer?.cancel(false)
        val receivedNanos = System.nanoTime()
        p.timer = schedule(waitMs) {
            if (completePendingSwitch(p, seamless = true)) {
                val elapsedMs = (System.nanoTime() - receivedNanos) / 1_000_000.0
                screen.updateData(packet.copy(startInMs = (packet.startInMs - elapsedMs).roundToInt().coerceAtLeast(1)))
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
        p.timer?.cancel(false)
        p.taken.adopt()
        swapIn(p.url, p.lang, p.preservePausedState, p.taken.player, seamless)
        return true
    }

    private fun cancelPendingSwitch() {
        val p = pendingSwitch ?: return
        pendingSwitch = null
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
        val oldPlayer = player
        player = null
        videoStarted = false
        screen.mediaError = null
        screen.timelineFollower.reset()
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
            reportDurationIfNeeded()
        }

        if (!seamless) Minecraft.getInstance().execute { screen.reloadTexture() }
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

    private fun reportDurationIfNeeded() {
        val mode = screen.effectiveMode
        if (mode != PlaybackMode.SYNCED && mode != PlaybackMode.BROADCAST) return
        val durationNanos = screen.mediaPlayerDurationNanos
        if (durationNanos <= 0L) return // 0 for live streams and any not-yet-resolved case.
        Initializer.sendPacket(
            ReportDuration(
                screen.uuid, durationNanos / 1_000_000L, screen.videoUrl.orEmpty(), ServerMediaGens.of(screen.uuid),
            ),
        )
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
