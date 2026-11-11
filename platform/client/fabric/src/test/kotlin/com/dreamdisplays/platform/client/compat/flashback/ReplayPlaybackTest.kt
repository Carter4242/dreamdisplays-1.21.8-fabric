package com.dreamdisplays.platform.client.compat.flashback

import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.core.protocol.common.packets.DisplayDelete
import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import com.dreamdisplays.core.protocol.common.packets.DisplaySync
import com.dreamdisplays.core.protocol.common.packets.DreamPacket
import com.dreamdisplays.platform.client.replay.ReplayCard
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayPlaybackTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val info = DisplayInfo(id = id, url = "https://example.org/a.mp4", mode = PlaybackMode.SYNCED.wire, mediaGen = 7)
    private fun sample(pos: Long, anchor: Long = 0, paused: Boolean = false, rate: Double = 1.0, gen: Long = 7) =
        ReplaySample(gen, pos, anchor, rate, paused, loop = false, durationMs = 600_000)

    // ------------------------------------------------------------------ model

    @Test
    fun `the model answers for any replay time from what held then`() {
        val m = ReplayModel()
        m.apply(0, ReplayFrame(true, listOf(ReplayEntry(id, info, sample = sample(1_000)))))
        m.apply(100, ReplayFrame(false, listOf(ReplayEntry(id, sample = sample(30_000, paused = true)))))
        assertEquals(1_000 + 2_500, m.at(2_500)[id]!!.sample!!.positionAt(2_500)) // anchored at tick 0
        assertEquals(30_000, m.at(6_000)[id]!!.sample!!.positionAt(6_000))
        // Back before the change (a seek back): the earlier timeline again
        assertEquals(1_000 + 4_000, m.at(4_000)[id]!!.sample!!.positionAt(4_000))
    }

    @Test
    fun `an action handled again after a seek back changes nothing`() {
        val m = ReplayModel()
        val frame = ReplayFrame(true, listOf(ReplayEntry(id, info, sample = sample(1_000, anchor = -40))))
        m.apply(20, frame)
        val first = m.at(5_000)
        m.apply(20, frame)
        assertEquals(first, m.at(5_000))
        assertEquals(1_000 + 5_000 - 960, first[id]!!.sample!!.positionAt(5_000))
    }

    @Test
    fun `a full frame removes what it does not list, an update only what it says`() {
        val m = ReplayModel()
        val other = UUID.randomUUID()
        m.apply(0, ReplayFrame(true, listOf(ReplayEntry(id, info), ReplayEntry(other, info.copy(id = other)))))
        m.apply(10, ReplayFrame(true, listOf(ReplayEntry(id, info))))
        assertEquals(setOf(id), m.at(600).keys)
        assertEquals(setOf(id, other), m.at(400).keys)
        m.apply(20, ReplayFrame(false, listOf(ReplayEntry(id, removed = true))))
        assertTrue(m.at(1_000).isEmpty())
        assertTrue(m.at(-50).isEmpty())
    }

    @Test
    fun `a timeline for another video is not used`() {
        val m = ReplayModel()
        m.apply(0, ReplayFrame(true, listOf(ReplayEntry(id, info, sample = sample(1_000)))))
        m.apply(10, ReplayFrame(false, listOf(ReplayEntry(id, info.copy(url = "https://example.org/b.mp4", mediaGen = 8)))))
        assertNull(m.at(600)[id]!!.sample)
        m.apply(12, ReplayFrame(false, listOf(ReplayEntry(id, sample = sample(0, gen = 8)))))
        assertEquals(8, m.at(700)[id]!!.sample!!.mediaGen)
    }

    // ------------------------------------------------------------------ driver

    private class Rig {
        val sent = ArrayList<DreamPacket>()
        val driver = ReplayDriver { sent += it }
        var now = 100_000L
        var has = true

        fun step(clock: ReplayDriver.Clock, views: Map<UUID, ReplayView>, owl: Double = 0.0): List<DreamPacket> {
            sent.clear()
            driver.tick(now, clock, views, { has }, owl)
            return sent.toList()
        }

        fun syncs(clock: ReplayDriver.Clock, views: Map<UUID, ReplayView>) = step(clock, views).filterIsInstance<DisplaySync>()
    }

    private fun views(s: ReplaySample?, card: ReplayCard? = null) = mapOf(id to ReplayView(info, card, s))
    private fun playing(ms: Long, speed: Double = 1.0) = ReplayDriver.Clock(ms, held = false, speed = speed)
    private fun held(ms: Long) = ReplayDriver.Clock(ms, held = true, speed = 1.0)

    @Test
    fun `a display is described, then gets its timeline at the replay position`() {
        val rig = Rig()
        val out = rig.step(playing(2_000), views(sample(1_000)), owl = 30.0)
        assertTrue(out[0] is DisplayInfo)
        val sync = out[1] as DisplaySync
        assertFalse(sync.isPaused)
        assertEquals(3_000 - 30, sync.currentTimeMs) // the follower adds its latency back
        assertEquals(7, sync.mediaGen)
        assertEquals(0, (out[0] as DisplayInfo).scheduledStartEpochMillis)
    }

    @Test
    fun `nothing is sent while the client is on track, a keepalive every second`() {
        val rig = Rig()
        val v = views(sample(0))
        rig.step(playing(0), v)
        rig.now += 500
        assertTrue(rig.syncs(playing(500), v).isEmpty())
        rig.now += 600
        assertEquals(1_100, rig.syncs(playing(1_100), v).single().currentTimeMs)
    }

    @Test
    fun `replay pause and seeking give a paused timeline on the exact replay position`() {
        val rig = Rig()
        val v = views(sample(0))
        rig.step(playing(0), v)
        rig.now += 300
        val paused = rig.syncs(held(300), v).single()
        assertTrue(paused.isPaused)
        assertEquals(300, paused.currentTimeMs)
        // Scrubbing while paused: each move is sent, at most five times a second
        rig.now += 100
        assertTrue(rig.syncs(held(9_000), v).isEmpty())
        rig.now += 150
        assertEquals(9_000, rig.syncs(held(9_000), v).single().currentTimeMs)
        rig.now += 250
        assertEquals(2_000, rig.syncs(held(2_000), v).single().currentTimeMs)
        rig.now += 250
        assertTrue(rig.syncs(held(2_000), v).isEmpty())
        rig.now += 250
        assertFalse(rig.syncs(playing(2_000), v).single().isPaused)
    }

    @Test
    fun `a jump while playing is sent at once, small wobble is not`() {
        val rig = Rig()
        val v = views(sample(0))
        rig.step(playing(0), v)
        rig.now += 300
        assertTrue(rig.syncs(playing(400), v).isEmpty())
        rig.now += 200
        assertEquals(20_000, rig.syncs(playing(20_000), v).single().currentTimeMs)
    }

    @Test
    fun `replay speed scales the rate, on top of the recorded one`() {
        val rig = Rig()
        assertEquals(1.0, rig.syncs(playing(0, speed = 0.5), views(sample(0, rate = 2.0))).single().rate)
        rig.now += 300
        assertEquals(4.0, rig.syncs(playing(150, speed = 2.0), views(sample(0, rate = 2.0))).single().rate)
        rig.now += 300
        assertEquals(10.0, rig.syncs(playing(300, speed = 8.0), views(sample(0, rate = 2.0))).single().rate)
    }

    @Test
    fun `a recorded pause stays paused at any replay speed`() {
        val rig = Rig()
        val sync = rig.syncs(playing(5_000, speed = 4.0), views(sample(1_234, paused = true))).single()
        assertTrue(sync.isPaused)
        assertEquals(1_234, sync.currentTimeMs)
    }

    @Test
    fun `a display that leaves the replay is deleted, one the client lost is described again`() {
        val rig = Rig()
        rig.step(playing(0), views(sample(0)))
        rig.has = false
        rig.now += 500
        assertTrue(rig.step(playing(500), views(sample(0))).none { it is DisplayInfo })
        rig.now += 2_000
        val again = rig.step(playing(2_500), views(sample(0)))
        assertTrue(again[0] is DisplayInfo)
        assertTrue(again[1] is DisplaySync)
        assertEquals(listOf<DreamPacket>(DisplayDelete(id)), rig.step(playing(3_000), emptyMap()))
    }

    @Test
    fun `a sync request is answered at once, with the description when the client has none`() {
        val rig = Rig()
        rig.step(playing(0), views(sample(0)))
        rig.now += 50
        rig.driver.requestSync(id, knownMediaGen = 7)
        assertTrue(rig.step(playing(50), views(sample(0))).single() is DisplaySync)
        rig.now += 50
        rig.driver.requestSync(id, knownMediaGen = 0)
        val out = rig.step(playing(100), views(sample(0)))
        assertTrue(out[0] is DisplayInfo && out[1] is DisplaySync)
    }

    @Test
    fun `a card display gets no video and no timeline`() {
        val rig = Rig()
        val out = rig.step(playing(0), views(sample(0), card = ReplayCard.SHARE))
        assertEquals("", (out.single() as DisplayInfo).url)
        assertEquals(ReplayCard.SHARE, rig.driver.card(id))
        rig.driver.reset()
        assertNull(rig.driver.card(id))
    }

    @Test
    fun `timeline stamps only grow`() {
        val rig = Rig()
        val v = views(sample(0))
        val a = rig.syncs(held(0), v).single().serverTimeMs
        rig.driver.requestSync(id, knownMediaGen = 7) // answered in the same millisecond: must not look stale
        val b = rig.syncs(held(0), v).single().serverTimeMs
        assertTrue(b > a)
    }

    @Test
    fun `the replay hello offers playback only`() {
        val hello = Rig().driver.hello()
        assertEquals(setOf("modes", "broadcast", "playback_speed"), hello.allowedFeatures.toSet())
        assertFalse(hello.isAdmin)
    }
}
