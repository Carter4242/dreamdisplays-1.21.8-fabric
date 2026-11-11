package com.dreamdisplays.core.sync

import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import java.util.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmptyDisplayRepeatsTest {
    private val player = UUID(0, 1)
    private val other = UUID(0, 2)
    private val empty = DisplayInfo(id = UUID(1, 1), url = "", width = 3, height = 2)

    @Test
    fun `an older client hears a new empty display once, then not on every refresh`() {
        val repeats = EmptyDisplayRepeats()
        assertTrue(repeats.shouldSend(player, empty, 11, 0))
        assertFalse(repeats.shouldSend(player, empty, 11, 1_000))
        assertFalse(repeats.shouldSend(player, empty, 11, 29_999))
        // Now and then again, in case its client lost the display
        assertTrue(repeats.shouldSend(player, empty, 11, 30_000))
        assertFalse(repeats.shouldSend(player, empty, 11, 31_000))
        // An unknown version counts as older
        assertTrue(repeats.shouldSend(other, empty, 0, 1_000))
        assertFalse(repeats.shouldSend(other, empty, 0, 2_000))
    }

    @Test
    fun `a changed empty display is described at once`() {
        val repeats = EmptyDisplayRepeats()
        assertTrue(repeats.shouldSend(player, empty, 11, 0))
        assertTrue(repeats.shouldSend(player, empty.copy(isLocked = false), 11, 1_000))
        assertTrue(repeats.shouldSend(player, empty.copy(isLocked = false, customData = mapOf("p" to byteArrayOf(1))), 11, 2_000))
        // The same custom data in a new array is no change
        assertFalse(repeats.shouldSend(player, empty.copy(isLocked = false, customData = mapOf("p" to byteArrayOf(1))), 11, 3_000))
    }

    @Test
    fun `a display with a video, or a current client, always gets it`() {
        val repeats = EmptyDisplayRepeats()
        val playing = empty.copy(url = "https://youtu.be/x")
        assertTrue(repeats.shouldSend(player, playing, 11, 0))
        assertTrue(repeats.shouldSend(player, playing, 11, 1))
        assertTrue(repeats.shouldSend(player, empty, 12, 0))
        assertTrue(repeats.shouldSend(player, empty, 12, 1))
    }

    @Test
    fun `cleared again after a video, dropped, or rejoined, it is described at once`() {
        val repeats = EmptyDisplayRepeats()
        assertTrue(repeats.shouldSend(player, empty, 11, 0))
        assertTrue(repeats.shouldSend(player, empty.copy(url = "https://youtu.be/x"), 11, 1_000))
        assertTrue(repeats.shouldSend(player, empty, 11, 2_000))
        repeats.forget(player, empty.id)
        assertTrue(repeats.shouldSend(player, empty, 11, 3_000))
        repeats.forgetPlayer(player)
        assertTrue(repeats.shouldSend(player, empty, 11, 4_000))
    }
}
