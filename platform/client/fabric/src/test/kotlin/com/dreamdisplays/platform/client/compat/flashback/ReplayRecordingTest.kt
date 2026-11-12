package com.dreamdisplays.platform.client.compat.flashback

import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.core.protocol.common.packets.DisplayDelete
import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import com.dreamdisplays.core.protocol.common.packets.DisplaySync
import com.dreamdisplays.core.protocol.common.packets.SetVideo
import com.dreamdisplays.platform.client.replay.ReplayCard
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayRecordingTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val video = DisplayInfo(id = id, url = "https://example.org/a.mp4", mode = PlaybackMode.SYNCED.wire, mediaGen = 7, x = 10, z = 10)

    @Test
    fun `a frame survives encoding`() {
        val frame = ReplayFrame(
            true,
            listOf(
                ReplayEntry(id, info = video.copy(customData = mapOf("k" to byteArrayOf(1, 2))), sample = ReplaySample(7, 1_000, -40, 1.5, false, true, 20_000)),
                ReplayEntry(UUID.randomUUID(), removed = true),
                ReplayEntry(UUID.randomUUID(), info = video.copy(url = ""), card = ReplayCard.SHARE),
            ),
        )
        val back = ReplayFormat.decode(ReplayFormat.encode(frame))!!
        assertEquals(frame.full, back.full)
        assertEquals(frame.entries.map { it.id }, back.entries.map { it.id })
        assertEquals(frame.entries[0].sample, back.entries[0].sample)
        assertEquals(video.url, back.entries[0].info!!.url)
        assertTrue(back.entries[0].info!!.customData["k"]!!.contentEquals(byteArrayOf(1, 2)))
        assertTrue(back.entries[1].removed)
        assertEquals(ReplayCard.SHARE, back.entries[2].card)
    }

    @Test
    fun `a newer format is not read`() {
        val bytes = ReplayFormat.encode(ReplayFrame(false, emptyList()))
        bytes[0] = (ReplayFormat.VERSION + 1).toByte()
        assertNull(ReplayFormat.decode(bytes))
    }

    @Test
    fun `positions follow the timeline, hold before a scheduled start, wrap a loop and stop at the end`() {
        val playing = ReplaySample(1, 5_000, anchorMs = 1_000, rate = 2.0, paused = false, loop = false, durationMs = 60_000)
        assertEquals(5_000, playing.positionAt(500)) // before the anchor (a start scheduled ahead): held
        assertEquals(5_000, playing.positionAt(1_000))
        assertEquals(7_000, playing.positionAt(2_000))
        assertEquals(60_000, playing.positionAt(100_000))
        assertEquals(5_000, playing.copy(paused = true).positionAt(9_000))
        val looping = playing.copy(rate = 1.0, loop = true, durationMs = 10_000)
        assertEquals(1_000, looping.positionAt(7_000))
    }

    @Test
    fun `a screen share is recorded without its address, as a card`() {
        val cache = ReplayRecordCache()
        val share = video.copy(url = "rtmps://relay.example.org:1935/live/abc?user=dd&pass=secret")
        val entry = cache.onPacket(share, nowMs = 0, 0, oneWayLatencyMs = 0.0)!!.entries.single()
        assertEquals(ReplayCard.SHARE, entry.card)
        assertEquals("", entry.info!!.url)
        val full = cache.fullFrame(0) { true }.entries.single()
        assertEquals("", full.info!!.url)
    }

    @Test
    fun `a local display with a video is a card, one without nothing special`() {
        val local = video.copy(mode = PlaybackMode.LOCAL.wire)
        assertEquals(ReplayCard.LOCAL, ReplayRecordCache.cardOf(local))
        assertNull(ReplayRecordCache.cardOf(local.copy(url = "")))
        assertNull(ReplayRecordCache.cardOf(video))
    }

    @Test
    fun `a timeline applies from its arrival less the latency the follower adds, written relative to the frame`() {
        val cache = ReplayRecordCache()
        cache.onPacket(video, 0, 0, 0.0)
        val sync = DisplaySync(id = id, currentTimeMs = 3_000, rate = 1.0, mediaGen = 7)
        val update = cache.onPacket(sync, nowMs = 10_000, 0, oneWayLatencyMs = 40.0)!!.entries.single()
        assertNull(update.info)
        assertEquals(-40, update.sample!!.anchorMs)
        // Written 5 s later, the same timeline is anchored 5 s further back
        assertEquals(-5_040, cache.fullFrame(15_000) { true }.entries.single().sample!!.anchorMs)
        // A start scheduled 800 ms ahead applies from then
        val scheduled = cache.onPacket(sync.copy(startInMs = 800), nowMs = 20_000, 0, oneWayLatencyMs = 40.0)!!
        assertEquals(760, scheduled.entries.single().sample!!.anchorMs)
    }

    @Test
    fun `an update written later keeps its timeline where it was`() {
        val cache = ReplayRecordCache()
        val frame = cache.onPacket(DisplaySync(id = id, currentTimeMs = 3_000, mediaGen = 7), nowMs = 0, nowEpochMs = 0, oneWayLatencyMs = 0.0)!!
        assertEquals(-50, frame.writtenLater(50).entries.single().sample!!.anchorMs)
        assertEquals(frame, frame.writtenLater(0))
    }

    @Test
    fun `stamped timelines apply from their server stamp, a late packet does not move them`() {
        val cache = ReplayRecordCache()
        // Arrived 30 ms after its stamp: the fastest seen, so it applies on arrival
        val a = cache.onPacket(DisplaySync(id = id, currentTimeMs = 1_000, serverTimeMs = 500_000), 10_000, 500_030, 400.0)!!
        assertEquals(0, a.entries.single().sample!!.anchorMs)
        // Arrived 250 ms after its stamp, held up on the way: it applied 220 ms before it arrived
        val b = cache.onPacket(DisplaySync(id = id, currentTimeMs = 2_000, serverTimeMs = 501_000), 11_250, 501_250, 400.0)!!
        assertEquals(-220, b.entries.single().sample!!.anchorMs)
        // A faster one lowers the bar
        val c = cache.onPacket(DisplaySync(id = id, currentTimeMs = 3_000, serverTimeMs = 502_000), 12_010, 502_010, 400.0)!!
        assertEquals(0, c.entries.single().sample!!.anchorMs)
    }

    @Test
    fun `deletions are recorded once and other packets not at all`() {
        val cache = ReplayRecordCache()
        cache.onPacket(video, 0, 0, 0.0)
        assertTrue(cache.onPacket(DisplayDelete(id), 0, 0, 0.0)!!.entries.single().removed)
        assertNull(cache.onPacket(DisplayDelete(id), 0, 0, 0.0))
        assertNull(cache.onPacket(SetVideo(id, "x"), 0, 0, 0.0))
        assertTrue(cache.fullFrame(0) { true }.entries.isEmpty())
    }

    @Test
    fun `virtual displays and far ones are left out`() {
        val cache = ReplayRecordCache()
        assertNull(cache.onPacket(video.copy(virtual = true), 0, 0, 0.0))
        cache.onPacket(video, 0, 0, 0.0)
        assertTrue(cache.fullFrame(0) { false }.entries.isEmpty())
    }
}
