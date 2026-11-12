package com.dreamdisplays.platform.client

import com.dreamdisplays.core.display.policy.PushedDefaults
import com.dreamdisplays.core.display.policy.PushedSetting
import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import java.util.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * A push that arrives while the mod is switched off rides in the display's held description, which is handled (and the
 * push applied, once) when it is switched back on.
 */
class PushedWhileOffTest {
    private val id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")

    @Test
    fun `the held description carries the newest push`() {
        val first = PushedDefaults.EMPTY.push(PushedSetting.VOLUME, "0.5", 1_000)
        val second = first.push(PushedSetting.VOLUME, "0.25", 2_000)
        val held = HeldDescriptions()
        held.keep(DisplayInfo(id, url = "a", customData = mapOf(PushedDefaults.KEY to first.encode())))
        // A push made while off comes as a forced description too (a broadcast follower): kept as the plain one
        held.keep(
            DisplayInfo(id, url = "a", forced = true, world = "minecraft:overworld", customData = mapOf(PushedDefaults.KEY to second.encode())),
        )
        val replayed = held.drain().single()
        assertContentEquals(second.encode(), replayed.customData[PushedDefaults.KEY])
        // What the client then applies: the newest push, at its revision
        val pending = PushedDefaults.decode(replayed.customData[PushedDefaults.KEY])!!.pending(mapOf("volume" to 1_000L))
        assertEquals(listOf(PushedSetting.Value.Volume(0.25f)), pending.map { it.value })
    }
}
