package com.dreamdisplays.core.karaoke

import java.util.*

/** How a karaoke singer's mic frame reaches one other player. */
enum class KaraokeRoute {
    /** As if karaoke were off: undelayed, from the sender. */
    NORMAL,

    /** The re-timed copy that lands on the beat. */
    ALIGNED,

    /** Not at all. */
    NONE,
}

/**
 * Who hears a singer how. A singer's raw voice is taken out of voice chat only while they sing in the display's range;
 * the other singers then still hear them the normal way, and everyone else in range gets the aligned copy.
 */
object KaraokeRouting {
    /**
     * [singing]: the singers of the stage [sender] sings on, or null when they don't (or are away from the display):
     * then their voice is routed by voice chat itself ([KaraokeRoute.NORMAL] for everyone).
     * [inDisplayRange]: [receiver] is in the display's audio range. [nearSender]: [receiver] is in the same world and
     * within voice chat's proximity distance of [sender]. [fromSinger]: the aligned copy comes from the singer
     * (`voice_source = "singer"`), so players near them hear it as well.
     */
    fun route(
        sender: UUID, receiver: UUID, singing: Set<UUID>?,
        inDisplayRange: Boolean, nearSender: Boolean, fromSinger: Boolean,
    ): KaraokeRoute = when {
        singing == null || sender !in singing -> KaraokeRoute.NORMAL
        receiver == sender -> KaraokeRoute.NONE
        receiver in singing -> if (nearSender) KaraokeRoute.NORMAL else KaraokeRoute.NONE
        inDisplayRange -> KaraokeRoute.ALIGNED
        fromSinger && nearSender -> KaraokeRoute.ALIGNED
        else -> KaraokeRoute.NONE
    }

    /**
     * How loud singers hear each other: [configured] (`co_singer_volume`, 0..1) while a song plays, so the others'
     * late voices don't throw them off; full volume between songs or while paused.
     */
    fun coSingerVolume(songPlaying: Boolean, configured: Double): Double =
        if (songPlaying) configured.coerceIn(0.0, 1.0) else 1.0
}
