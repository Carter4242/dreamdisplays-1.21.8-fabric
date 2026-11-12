package com.dreamdisplays.platform.client

import com.dreamdisplays.core.protocol.common.packets.*
import java.nio.file.Files
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MasterSwitchRulesTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val other = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test
    fun `while off only bookkeeping is applied, descriptions are kept and everything that would show is dropped`() {
        val apply = listOf(
            ServerHello(), SetDisplaysEnabled(true), ServerPolicy(), DisplayDelete(id), ClearCache(), ScreenShareState(id),
        )
        apply.forEach { assertEquals(OffHandling.APPLY, MasterSwitchRules.offHandling(it), it::class.simpleName) }
        assertEquals(OffHandling.HOLD, MasterSwitchRules.offHandling(DisplayInfo(id)))
        assertEquals(OffHandling.TIMELINE, MasterSwitchRules.offHandling(DisplaySync(id)))
        // A forced fullscreen, a preparation, a queue, a remote pause, a report request and clock pings would all make
        // this client load, play or answer for a display: none of them may switch the mod back on
        val dropped = listOf(
            FullscreenState(displayId = id, active = true, forced = true), FullscreenPrepare(), QueueState(id),
            RemotePlaybackToggle(id), ReportRequest(), KaraokeClockPing(),
        )
        dropped.forEach { assertEquals(OffHandling.DROP, MasterSwitchRules.offHandling(it), it::class.simpleName) }
    }

    @Test
    fun `the server hears displays are on only when the mod and its own switch both are`() {
        assertTrue(MasterSwitchRules.displaysOnForServer(modOn = true, displaysEnabled = true))
        assertFalse(MasterSwitchRules.displaysOnForServer(modOn = false, displaysEnabled = true))
        assertFalse(MasterSwitchRules.displaysOnForServer(modOn = true, displaysEnabled = false))
        assertFalse(MasterSwitchRules.displaysOnForServer(modOn = false, displaysEnabled = false))
    }

    @Test
    fun `a display on from the server while the mod is off is answered off`() {
        assertEquals(false, MasterSwitchRules.answerToServerSet(modOn = false, enabled = true))
        assertNull(MasterSwitchRules.answerToServerSet(modOn = false, enabled = false))
        assertNull(MasterSwitchRules.answerToServerSet(modOn = true, enabled = true))
        assertNull(MasterSwitchRules.answerToServerSet(modOn = true, enabled = false))
    }

    @Test
    fun `only the latest description of each display is kept`() {
        val held = HeldDescriptions()
        repeat(1_000) { held.keep(DisplayInfo(id, url = "v$it", mediaGen = it.toLong())) }
        held.keep(DisplayInfo(other, url = "x"))
        assertEquals(2, held.size)
        assertEquals("v999", held[id]?.url)
        val drained = held.drain().associateBy { it.id }
        assertEquals(setOf(id, other), drained.keys)
        assertEquals(0, held.size)
    }

    @Test
    fun `broadcast-only descriptions are not kept, a forced one in a known world is kept as a plain one`() {
        val held = HeldDescriptions()
        held.keep(DisplayInfo(id, url = "a", world = "minecraft:overworld"))
        held.keep(DisplayInfo(id, url = "b", virtual = true))
        assertEquals("a", held[id]?.url)
        held.keep(DisplayInfo(id, url = "c", forced = true))
        assertEquals("a", held[id]?.url, "a forced description without a world may come from another one")
        held.keep(DisplayInfo(id, url = "d", forced = true, world = "minecraft:overworld"))
        assertEquals("d", held[id]?.url)
        assertFalse(held[id]!!.forced, "kept as a plain, distance-bound description")
        held.keep(DisplayInfo(other, url = "v", virtual = true))
        assertNull(held[other])
    }

    @Test
    fun `seeding from the last descriptions never replaces a newer kept one, and deletes are forgotten`() {
        val held = HeldDescriptions()
        held.keep(DisplayInfo(id, url = "new"))
        held.keepIfAbsent(DisplayInfo(id, url = "old"))
        held.keepIfAbsent(DisplayInfo(other, url = "o"))
        assertEquals("new", held[id]?.url)
        assertEquals("o", held[other]?.url)
        held.forget(other)
        assertNull(held[other])
        held.clear()
        assertEquals(0, held.size)
    }

    @Test
    fun `a karaoke lead while off leaves that stage once`() {
        assertTrue(MasterSwitchRules.leavesStage(DisplaySync(id, karaokeLeadMs = 400), emptySet()))
        assertFalse(MasterSwitchRules.leavesStage(DisplaySync(id, karaokeLeadMs = 400), setOf(id)))
        assertFalse(MasterSwitchRules.leavesStage(DisplaySync(id, karaokeLeadMs = 0), emptySet()))
    }

    @Test
    fun `switching back on loads the nearest displays first`() {
        val far = DisplayInfo(id, x = 200, y = 64, z = 0)
        val near = DisplayInfo(other, x = 4, y = 64, z = 0)
        assertEquals(listOf(other, id), MasterSwitchRules.replayOrder(listOf(far, near), 0.0, 64.0, 0.0).map { it.id })
    }

    @Test
    fun `the switch survives the config file and defaults to on`() {
        val dir = Files.createTempDirectory("dd-config").toFile()
        try {
            assertTrue(Config(dir).modEnabled)
            Config(dir).apply { modEnabled = false }.save()
            assertFalse(Config(dir).modEnabled)
        } finally {
            dir.deleteRecursively()
        }
    }
}
