package com.dreamdisplays.core.karaoke

import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KaraokeVoiceSendTest {
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val listener = UUID.randomUUID()

    @Test
    fun `duet singers near each other get entity packets carrying the sender's UUID`() {
        val singing = setOf(a, b)
        for ((from, to) in listOf(a to b, b to a)) {
            assertEquals(KaraokeRoute.NORMAL, KaraokeRouting.route(from, to, singing, false, true, true))
            val send = assertIs<KaraokeVoiceSend.Entity>(KaraokeVoiceSend.toCoSinger(from, whispering = false, proximity = 48.0))
            assertEquals(from, send.entity)
            assertEquals(48.0, send.distance)
        }
    }

    @Test
    fun `listeners near the singer get entity sound from them, far ones locational at the singer`() {
        assertEquals(KaraokeRoute.ALIGNED, KaraokeRouting.route(a, listener, setOf(a, b), true, true, true))
        assertEquals(KaraokeVoiceSend.Entity(a, false, 96.0), KaraokeVoiceSend.toListener(a, true, true, true, 96.0))
        assertEquals(KaraokeVoiceSend.Locational(a, true, 96.0), KaraokeVoiceSend.toListener(a, true, true, false, 96.0))
    }

    @Test
    fun `display source and an offline singer play at the display on its own channel`() {
        val display = KaraokeVoiceSend.Locational(KaraokeVoiceSend.displayChannel(a), false, 64.0)
        assertEquals(display, KaraokeVoiceSend.toListener(a, false, true, true, 64.0))
        assertEquals(display, KaraokeVoiceSend.toListener(a, true, false, true, 64.0))
        assertEquals(KaraokeVoiceSend.displayChannel(a), KaraokeVoiceSend.displayChannel(a))
        assert(KaraokeVoiceSend.displayChannel(a) != a)
    }
}
