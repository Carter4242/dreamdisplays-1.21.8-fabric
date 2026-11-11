package com.dreamdisplays.platform.server.karaoke

import com.dreamdisplays.core.karaoke.KaraokeLatency
import java.util.*

/**
 * What the voice side needs about one karaoke display, rebuilt on the main thread a few times a second
 * ([KaraokeManager.tick]) and read from voice chat's network thread. Plain data: nothing here touches Simple Voice
 * Chat classes, so the plugin runs without it.
 *
 * Singers' voices are re-sent as locational sound from [x] / [y] / [z] (the display's centre) carrying [distance]
 * blocks, so in range they mix into the display's own sound like a PA and fade the same way.
 */
data class KaraokeStage(
    val displayId: UUID,
    val displayLabel: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val distance: Double,
    val latency: KaraokeLatency,
    val singers: Map<UUID, StageSinger>,
    val listeners: List<StageListener>,
)

/** A singer's lead, total trim (display + own) and ping, in ms. */
data class StageSinger(val leadMs: Long, val trimMs: Long, val pingMs: Long)

/** A player in the display's audio range who is not singing on it, with their ping in ms. */
data class StageListener(val playerId: UUID, val pingMs: Long)

/** Voice statistics of one singer since they started singing, for `/display karaoke ... status`. */
data class SingerVoiceStats(
    val frames: Long,
    val bursts: Int,
    val reanchors: Int,
    val sent: Long,
    val late: Long,
    /** Listeners who got a late frame in the last [KaraokeVoice.LATE_WINDOW_MS]. */
    val lateListeners: Int,
    val worstLateMs: Long,
)

/**
 * The Simple Voice Chat side of karaoke. Implemented by `SvcKaraokeVoice`, which is only loaded (by name) when voice
 * chat is installed.
 */
interface KaraokeVoice {
    /** Hooks into the running (or starting) voice chat; false when that failed. */
    fun start(): Boolean

    /** Unhooks from voice chat and stops sending. */
    fun stop()

    /** True when [playerId] is connected to voice chat. */
    fun isConnected(playerId: UUID): Boolean

    /** [singerId]'s voice statistics, or null when none of their voice came through yet. */
    fun stats(singerId: UUID): SingerVoiceStats?

    /** Drops [singerId]'s voice state (no longer singing). */
    fun forget(singerId: UUID)

    companion object {
        const val LATE_WINDOW_MS = 15_000L
    }
}
