package com.dreamdisplays.core.karaoke

import java.util.*

/** How one copy of a karaoke singer's mic frame is sent to one receiver through Simple Voice Chat. */
sealed interface KaraokeVoiceSend {
    val distance: Double

    /**
     * Entity sound from the singer: [entity] is always their player UUID (voice chat refuses an entity packet without
     * one) and is also the channel, so clients play it from the singer and show them speaking.
     */
    data class Entity(val entity: UUID, val whispering: Boolean, override val distance: Double) : KaraokeVoiceSend

    /** Locational sound on [channel], from the singer's position ([atSinger]) or else the display's centre. */
    data class Locational(val channel: UUID, val atSinger: Boolean, override val distance: Double) : KaraokeVoiceSend

    companion object {
        /** A co-singer near [singerId] ([KaraokeRoute.NORMAL]): at once, from the singer, like normal voice chat. */
        fun toCoSinger(singerId: UUID, whispering: Boolean, proximity: Double): Entity =
            Entity(singerId, whispering, proximity)

        /**
         * A listener's aligned copy ([KaraokeRoute.ALIGNED]). With [fromSinger] (`voice_source = singer`) and the
         * singer online it comes from them: entity sound when [nearSinger] (within voice chat's distance, so the
         * listener's client has the singer loaded), locational at the singer otherwise. Else from the display.
         */
        fun toListener(
            singerId: UUID, fromSinger: Boolean, singerOnline: Boolean, nearSinger: Boolean, distance: Double,
        ): KaraokeVoiceSend = when {
            fromSinger && singerOnline && nearSinger -> Entity(singerId, false, distance)
            fromSinger && singerOnline -> Locational(singerId, true, distance)
            else -> Locational(displayChannel(singerId), false, distance)
        }

        /** The channel of a singer's voice played at the display, apart from their own. */
        fun displayChannel(singerId: UUID): UUID =
            UUID.nameUUIDFromBytes("dreamdisplays:karaoke:$singerId".toByteArray())
    }
}
