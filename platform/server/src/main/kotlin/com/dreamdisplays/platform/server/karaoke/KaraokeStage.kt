package com.dreamdisplays.platform.server.karaoke

import com.dreamdisplays.core.karaoke.KaraokeLatency
import com.dreamdisplays.core.karaoke.ListenerLatency
import com.dreamdisplays.core.karaoke.SingerLatency
import java.util.*

/**
 * What the voice side needs about one karaoke display, rebuilt on the main thread a few times a second
 * ([KaraokeManager.tick]) and read from voice chat's network thread. Plain data: nothing here touches Simple Voice
 * Chat classes, so the plugin runs without it.
 *
 * Singers' voices are re-sent as locational sound carrying at least [distance] blocks: from [x] / [y] / [z] (the
 * display's centre, like a PA) or, with [fromSinger], from wherever the singer is when each frame goes out.
 * [songPlaying]: the display's timeline runs, so singers hear each other at [coSingerVolume] (else normally).
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
    val fromSinger: Boolean = true,
    val songPlaying: Boolean = false,
    val coSingerVolume: Double = 1.0,
    /** Volume of the singers' voices for listeners (`singer_volume`, 0..3, soft-limited above 1). */
    val singerVolume: Double = 1.0,
    val debug: Boolean = false,
)

/**
 * A singer's lead, total trim (display + own + automatic) and ping, in ms, and what their client measured ([latency]).
 * [present]: online and in the display's audio range; otherwise voice chat routes their voice as usual.
 */
data class StageSinger(
    val leadMs: Long,
    val trimMs: Long,
    val pingMs: Long,
    val present: Boolean = true,
    val latency: SingerLatency = SingerLatency(),
)

/**
 * A player in the display's world who is not singing on it, with their ping in ms and what their client measured
 * ([latency], null = nothing). [inRange]: in the display's audio range (with `voice_source = "display"` only those are
 * listed).
 */
data class StageListener(
    val playerId: UUID,
    val pingMs: Long,
    val inRange: Boolean = true,
    val latency: ListenerLatency? = null,
)

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
