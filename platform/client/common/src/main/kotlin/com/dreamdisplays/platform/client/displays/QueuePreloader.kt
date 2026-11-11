package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.media.player.PlaybackHost
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.core.protocol.common.packets.QueueItemWire
import com.dreamdisplays.core.protocol.common.packets.QueuePreloadState
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.platform.client.player.platform.DisplayPlaybackHost
import com.dreamdisplays.platform.client.player.platform.DreamPlaybackEnvironment
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loads a synced display's next queue entry in the background shortly before the current video ends, so the switch
 * the server makes at the end starts at once instead of resolving and buffering first.
 *
 * The preloaded [MediaPlayer] is muted, never drawn (nothing uploads its frames) and parked warm on its first frame.
 * When the server switches the display to that URL, [take] hands the player to [DisplayMediaController], which adopts
 * it instead of building a new one. The server hears READY through a `QueuePreload` packet and shortens its start
 * hold once every nearby preloading client is ready.
 *
 * Bounded: one preload per display, [MAX_ACTIVE] per client, dropped as soon as the queue head changes.
 */
internal class QueuePreloader(private val screen: DisplayScreen) {
    private enum class Phase { LOADING, WARMING, READY, FAILED }

    private class Preload(val item: QueueItemWire, val lang: String, val player: MediaPlayer, val host: PreloadHost) {
        var phase = Phase.LOADING
        val startedNanos = System.nanoTime()
        var reported: QueuePreloadState? = null
        var orphanedSinceNanos = 0L

        /** Still holds a slot of [ACTIVE] (a failed preload gives its slot back at once). */
        var holdsSlot = true

        fun release() {
            if (holdsSlot) ACTIVE.decrementAndGet()
            holdsSlot = false
        }
    }

    private var preload: Preload? = null

    /** Called every client tick from [DisplayScreen.tick]. */
    fun tick() {
        val current = preload
        val head = DisplayQueues.next(screen.uuid)
        if (current != null) {
            if (head?.itemId != current.item.itemId) {
                // The queue moved on without this client adopting it (removed, reordered, someone else's skip):
                // give an in-flight video change a moment to arrive, then let it go.
                if (current.orphanedSinceNanos == 0L) current.orphanedSinceNanos = System.nanoTime()
                else if (System.nanoTime() - current.orphanedSinceNanos > ORPHAN_GRACE_NANOS) cancel()
                return
            }
            current.orphanedSinceNanos = 0L
            if (!wanted(head, lookAhead = false)) {
                cancel()
                return
            }
            step(current)
            return
        }
        if (head != null && wanted(head, lookAhead = true) && ACTIVE.get() < MAX_ACTIVE) start(head)
    }

    /**
     * The preloaded player for [url], detached and ready to become the display's player, or null. Any other preload
     * is dropped: the display is switching to something else.
     */
    fun take(url: String, lang: String): MediaPlayer? {
        val current = preload ?: return null
        preload = null
        current.release()
        if (current.item.url != url || current.phase == Phase.FAILED || current.host.mediaError != null) {
            current.player.stop()
            return null
        }
        current.host.adopt()
        logger.info(
            "Display {}: starting the preloaded queue video ({}, {} ms after preloading began).",
            shortId(), current.phase.name.lowercase(), (System.nanoTime() - current.startedNanos) / 1_000_000L,
        )
        return current.player
    }

    /** Drops any preload (display unloaded, mode change). */
    fun cancel() {
        val current = preload ?: return
        preload = null
        current.release()
        current.player.stop()
        if (current.reported == QueuePreloadState.READY) {
            DisplayQueues.reportPreload(screen.uuid, current.item.itemId, QueuePreloadState.DROPPED)
        }
    }

    /** Whether [head] should be (or stay) preloaded now; [lookAhead] = starting a new one, which waits for the end. */
    private fun wanted(head: QueueItemWire, lookAhead: Boolean): Boolean {
        if (!DisplayQueues.supported) return false
        if (screen.effectiveMode != PlaybackMode.SYNCED && screen.effectiveMode != PlaybackMode.BROADCAST) return false
        if (screen.isDormant || screen.virtual || screen.errored) return false
        if (head.url.isEmpty() || head.url == screen.videoUrl) return false
        if (!lookAhead) return true
        if (!screen.videoStarted || screen.isLive) return false
        val durationNanos = screen.mediaPlayerDurationNanos
        if (durationNanos <= 0L) return false
        val remainingNanos = durationNanos - screen.currentTimeNanos
        return remainingNanos in 0..LEAD_NANOS
    }

    private fun start(item: QueueItemWire) {
        val lang = item.lang
        val host = PreloadHost(screen)
        val player = runCatching {
            MediaPlayer(item.url, lang, host, DreamPlaybackEnvironment, null, null)
        }.onFailure { logger.warn("Display {}: could not preload the next queue video.", shortId(), it) }
            .getOrNull() ?: return
        // Silent from the very first sample; the display sets its real volume when it adopts the player
        val distance = Minecraft.getInstance().player?.blockPosition()?.let(screen::getDistanceToScreen) ?: 0.0
        player.primeVolume(0f, distance, 1.0, 0.0, true)
        player.setVolume(0f)
        preload = Preload(item, lang, player, host)
        ACTIVE.incrementAndGet()
        logger.info("Display {}: preloading the next queue video.", shortId())
    }

    private fun step(p: Preload) {
        if (p.host.mediaError != null && p.phase != Phase.FAILED) return fail(p, "load error")
        when (p.phase) {
            Phase.LOADING -> {
                if (p.player.hasDecodedFrame() && p.player.isClockRunning()) {
                    // First frame is in: park it warm right here, a few frames in
                    p.player.pause()
                    p.phase = Phase.WARMING
                } else if (System.nanoTime() - p.startedNanos > LOAD_TIMEOUT_NANOS) {
                    fail(p, "timed out")
                }
            }

            Phase.WARMING -> {
                p.phase = Phase.READY
                report(p, QueuePreloadState.READY)
            }

            Phase.READY, Phase.FAILED -> {}
        }
    }

    private fun fail(p: Preload, why: String) {
        logger.info("Display {}: preloading the next queue video failed ({}); it will load normally.", shortId(), why)
        p.phase = Phase.FAILED
        p.player.stop()
        p.release()
        report(p, QueuePreloadState.FAILED)
    }

    private fun report(p: Preload, state: QueuePreloadState) {
        if (p.reported == state) return
        p.reported = state
        DisplayQueues.reportPreload(screen.uuid, p.item.itemId, state)
    }

    private fun shortId(): String = screen.uuid.toString().take(8)

    companion object {
        private val logger = LoggerFactory.getLogger(QueuePreloader::class.java)

        /** Start preloading this long before the current video ends. */
        private const val LEAD_NANOS = 25_000_000_000L

        /** Give up on a preload that has not produced a frame in this long. */
        private const val LOAD_TIMEOUT_NANOS = 30_000_000_000L
        private const val ORPHAN_GRACE_NANOS = 1_500_000_000L

        /** Preloaded players alive at once on this client, across all displays. */
        private const val MAX_ACTIVE = 2
        private val ACTIVE = AtomicInteger()
    }
}

/**
 * [PlaybackHost] of a preloaded queue video: invisible and inert towards its display until [adopt], then exactly the
 * display's own host. Gets its own id so its read-ahead buffer never collides with the display's current player.
 */
internal class PreloadHost(private val screen: DisplayScreen) : PlaybackHost {
    private val display = DisplayPlaybackHost(screen)

    @Volatile
    private var adopted = false

    @Volatile
    private var localAspect = 0.0

    @Volatile
    private var localError: DreamMediaException? = null

    override val uuid: UUID = UUID.randomUUID()

    override val quality: VideoQuality get() = display.quality

    override val textureWidth: Int get() = display.textureWidth

    override val textureHeight: Int get() = display.textureHeight

    override val isPaused: Boolean get() = if (adopted) display.isPaused else false

    override val effectiveMode: PlaybackMode get() = display.effectiveMode

    override val shouldLoopOnEnd: Boolean get() = if (adopted) display.shouldLoopOnEnd else true

    override var videoContentAspect: Double
        get() = if (adopted) display.videoContentAspect else localAspect
        set(value) {
            if (adopted) display.videoContentAspect = value else localAspect = value
        }

    override var mediaError: DreamMediaException?
        get() = if (adopted) display.mediaError else localError
        set(value) {
            if (adopted) display.mediaError = value else localError = value
        }

    override val readAheadSeconds: Int? get() = display.readAheadSeconds

    /** Hands the player over: from now on it is the display's player in every respect. */
    fun adopt() {
        adopted = true
        if (localAspect > 0.0) screen.videoContentAspect = localAspect
    }

    override fun afterSeek() {
        if (adopted) display.afterSeek()
    }

    override fun beginQualityHandoff() {
        if (adopted) display.beginQualityHandoff()
    }

    override fun cancelQualityHandoff() {
        if (adopted) display.cancelQualityHandoff()
    }

    override fun reloadTexture() {
        if (adopted) display.reloadTexture()
    }

    override fun onPlaybackEnded(positionNanos: Long) {
        if (adopted) display.onPlaybackEnded(positionNanos)
    }
}
