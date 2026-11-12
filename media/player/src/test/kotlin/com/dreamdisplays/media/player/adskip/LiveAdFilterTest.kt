package com.dreamdisplays.media.player.adskip

import com.dreamdisplays.media.player.adskip.TwitchSim.Companion.mediaSequence
import com.dreamdisplays.media.player.adskip.TwitchSim.Companion.uris
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveAdFilterTest {
    private val mainUrl = "https://video-weaver.example.hls.ttvnw.net/v1/playlist/main.m3u8"
    private var now = 1_000_000_000_000L

    private fun filter(sim: TwitchSim, backups: BackupChooser? = null) =
        LiveAdFilter(mainUrl, "test", { url -> if (url == mainUrl) sim.playlist() else error("unexpected $url") }, backups) { now }

    /** Advances the clock one segment, appends it, polls; returns what is served. */
    private fun LiveAdFilter.step(sim: TwitchSim, append: TwitchSim.() -> Unit): String? {
        now += 2_000_000_000L
        sim.append()
        refresh()
        return playlist()
    }

    @Test
    fun `a stream without ads is served as it is, numbered on`() {
        val sim = TwitchSim().live(6)
        val f = filter(sim)
        f.refresh()
        assertEquals((9000L..9005L).map { "https://0123456789ab.j.cloudfront.hls.ttvnw.net/v1/segment/live-$it.ts" }, uris(f.playlist()))
        val first = mediaSequence(f.playlist())!!
        val next = f.step(sim) { live() }
        assertEquals(first + 1, mediaSequence(next))
        assertEquals("https://0123456789ab.j.cloudfront.hls.ttvnw.net/v1/segment/live-9006.ts", uris(next).last())
        assertFalse(f.holding)
        assertEquals(0, f.breaks)
    }

    @Test
    fun `a mid-roll is cut out and the stream rejoins three segments behind its edge`() {
        val sim = TwitchSim().live(6)
        val f = filter(sim)
        f.refresh()
        val served = ArrayList<String?>()
        // 30 s ad (15 segments) replacing the stream
        served += f.step(sim) { startAd(30.0) }
        assertTrue(f.holding)
        val held = served.last()
        assertEquals(uris(held).last(), "https://0123456789ab.j.cloudfront.hls.ttvnw.net/v1/segment/live-9005.ts")
        repeat(14) {
            served += f.step(sim) { ad() }
            assertTrue(f.holding)
            assertEquals(held, served.last(), "the held playlist never grows during the ad")
        }
        // The stream is back, at its live edge; two segments are not enough to join yet
        served += f.step(sim) { lose(15).live() }
        assertTrue(f.holding)
        served += f.step(sim) { live() }
        assertTrue(f.holding)
        assertEquals(held, served.last())
        served += f.step(sim) { live() }
        assertFalse(f.holding, "three segments of the stream after the ad release it")
        val back = served.last()
        assertEquals((9021L..9023L).map { "https://0123456789ab.j.cloudfront.hls.ttvnw.net/v1/segment/live-$it.ts" }, uris(back))
        // Numbered straight on from the last segment before the ad: no jump for the decoders
        assertEquals(mediaSequence(held)!! + uris(held).size, mediaSequence(back))
        assertTrue(back!!.lines().none { it == "#EXT-X-DISCONTINUITY" }, "a join starts on the cut: no leading discontinuity")
        // No ad segment was ever served
        assertTrue(served.flatMap { uris(it) }.none { "/ad-" in it })
        assertEquals(1, f.breaks)
        // The rejoin is pinned to the cut until the new session has read it, however late it opens...
        val pinned = f.step(sim) { live() }
        assertEquals(uris(back), uris(pinned))
        // ...then it carries on normally
        now += 3_000_000_000L
        val later = f.step(sim) { live() }
        assertEquals("https://0123456789ab.j.cloudfront.hls.ttvnw.net/v1/segment/live-9025.ts", uris(later).last())
        assertEquals(uris(back).first(), uris(later).first(), "the window still starts at the cut")
    }

    @Test
    fun `the countdown runs to the ad's end plus the segments a join needs`() {
        val sim = TwitchSim().live(6)
        val f = filter(sim)
        f.refresh()
        f.step(sim) { startAd(30.0) }
        // One 2 s ad segment in: 28 s of ad past the edge, then three segments of the stream
        assertEquals(now + 34_000_000_000L, f.resumeAtNanos)
        f.step(sim) { ad(4) }
        assertEquals(now + 26_000_000_000L, f.resumeAtNanos)
        f.step(sim) { ad(10).lose(15).live() }
        // Ad over, one segment of the stream in: two more to go
        assertEquals(now + 4_000_000_000L, f.resumeAtNanos)
        // 33.2 s rounds up, plus a second for the stream to open again
        assertEquals(35, AdBreakTiming.secondsLeft(now + 33_200_000_000L, now))
        assertEquals(1, AdBreakTiming.secondsLeft(now - 5_000_000_000L, now), "late: never below 1 while the card shows")
        assertEquals(0, AdBreakTiming.secondsLeft(null, now), "unknown length")
    }

    @Test
    fun `a pre-roll holds the stream from the first read`() {
        val sim = TwitchSim().startAd(30.0, count = 3, rollType = "PREROLL")
        val f = filter(sim)
        f.refresh()
        assertTrue(f.ready)
        assertTrue(f.holding)
        assertTrue(uris(f.playlist()).isEmpty())
        f.step(sim) { ad(12).lose(15).live(2) }
        assertTrue(f.holding)
        f.step(sim) { live() }
        assertFalse(f.holding)
        assertEquals(3, uris(f.playlist()).size)
    }

    @Test
    fun `an ad pod holds until the stream follows the last ad`() {
        val sim = TwitchSim().live(6)
        val f = filter(sim)
        f.refresh()
        f.step(sim) { startAd(4.0, count = 2) }
        f.step(sim) { lose(2).live(2) }
        assertTrue(f.holding)
        f.step(sim) { startAd(4.0, count = 2) }
        f.step(sim) { lose(2).live(2) }
        assertTrue(f.holding)
        f.step(sim) { live() }
        assertFalse(f.holding)
        // Only the stream after the second ad: what came between the two ads is never played
        assertEquals(listOf(9012L, 9013L, 9014L).map { "https://0123456789ab.j.cloudfront.hls.ttvnw.net/v1/segment/live-$it.ts" }, uris(f.playlist()))
    }

    @Test
    fun `a playlist that keeps failing is given up and no longer served`() {
        var fail = false
        val sim = TwitchSim().live(6)
        val f = LiveAdFilter(mainUrl, "test", { if (fail) throw IOException("403") else sim.playlist() }, null) { now }
        f.refresh()
        assertNotNull(f.playlist())
        fail = true
        repeat(4) { f.refresh() }
        assertFalse(f.gone)
        f.refresh()
        assertTrue(f.gone)
        assertNull(f.playlist())
    }

    @Test
    fun `the real pre-roll fixture holds and the clean one plays`() {
        val ad = resource("twitch-preroll.m3u8")
        val clean = resource("twitch-clean.m3u8")
        val held = LiveAdFilter(mainUrl, "test", { ad }, null) { now }.also { it.refresh() }
        assertTrue(held.holding)
        assertTrue(uris(held.playlist()).isEmpty())
        // 30.235 s of ad from 17:45:24.567; the edge is 17:45:56.802 (one stream segment in, two to go)
        assertEquals(now + 4_000_000_000L, held.resumeAtNanos)
        val plain = LiveAdFilter(mainUrl, "test", { clean }, null) { now }.also { it.refresh() }
        assertFalse(plain.holding)
        assertEquals(15, uris(plain.playlist()).size)
    }

    // --- Backups ---

    private class FakeSource(val urls: Map<BackupVariant, String?>) : BackupPlaylistSource {
        val asked = ArrayList<BackupVariant>()
        override fun mediaPlaylistUrl(variant: BackupVariant, rendition: LiveRendition): String? {
            asked += variant
            return urls[variant] ?: throw IOException("refused")
        }
    }

    private val embed = BackupVariant("embed", "web")
    private val android = BackupVariant("site", "android")
    private val rendition = LiveRendition("somechannel", 720, 60.0, "720p60")

    @Test
    fun `an ad-free backup plays through the ad with nothing held and nothing repeated`() {
        // The same stream as two player variants: the main one gets an ad, the backup does not
        val main = TwitchSim().live(6)
        val backup = TwitchSim(host = "https://0123456789ab.j.cloudfront.hls.ttvnw.net").live(6)
        val backupUrl = "https://video-weaver.example.hls.ttvnw.net/v1/playlist/embed.m3u8"
        val source = FakeSource(mapOf(embed to backupUrl))
        val memory = BackupMemory(listOf(embed, android)) { now }
        val fetch = { url: String -> if (url == mainUrl) main.playlist() else backup.playlist() }
        val chooser = BackupChooser(rendition, source, fetch, memory, "test")
        val f = LiveAdFilter(mainUrl, "test", fetch, chooser) { now }
        f.refresh()
        val served = ArrayList<String?>()
        fun step(m: TwitchSim.() -> Unit, b: TwitchSim.() -> Unit) {
            now += 2_000_000_000L
            main.m(); backup.b()
            f.refresh()
            served += f.playlist()
        }
        step({ startAd(10.0) }, { live() })
        assertFalse(f.holding)
        assertEquals("embed/web", f.sourceName)
        repeat(4) { step({ ad() }, { live() }) }
        assertFalse(f.holding)
        step({ lose(5).live() }, { live() })
        assertEquals(LiveAdFilter.MAIN, f.sourceName, "the stream's own playlist again once its ad is over")
        repeat(2) { step({ live() }, { live() }) }
        assertFalse(f.holding)
        assertEquals(0, f.breaks)
        val all = served.flatMap { uris(it) }
        assertTrue(all.none { "/ad-" in it })
        // Every segment of the stream, in order, none twice: 9006..9013
        assertEquals((9006L..9013L).map { "https://0123456789ab.j.cloudfront.hls.ttvnw.net/v1/segment/live-$it.ts" }, all.distinct().filter { it.substringAfterLast('-').removeSuffix(".ts").toLong() >= 9006 })
        val seqs = served.map { mediaSequence(it)!! }
        assertEquals(seqs.sorted(), seqs, "numbering only grows")
        assertEquals(listOf(embed), source.asked, "signed once, then reused")
    }

    @Test
    fun `a backup that shows an ad or fails falls through to the filter, quietly and boundedly`() {
        val main = TwitchSim().live(6)
        val withAd = TwitchSim().live(5).startAd(30.0)
        val adUrl = "https://video-weaver.example.hls.ttvnw.net/v1/playlist/embed.m3u8"
        val source = FakeSource(mapOf(embed to adUrl, android to null))
        val memory = BackupMemory(listOf(embed, android)) { now }
        val fetch = { url: String -> if (url == mainUrl) main.playlist() else withAd.playlist() }
        val f = LiveAdFilter(mainUrl, "test", fetch, BackupChooser(rendition, source, fetch, memory, "test")) { now }
        f.refresh()
        f.step(main) { startAd(30.0) }
        assertTrue(f.holding, "no backup: the ad is filtered")
        assertEquals(listOf(embed, android), source.asked)
        // Nothing more is signed during this ad, nor by another display meanwhile
        repeat(5) { f.step(main) { ad() } }
        assertEquals(2, source.asked.size)
        assertFalse(memory.mayTry())
        val other = BackupChooser(rendition, source, fetch, memory, "other")
        assertNull(other.adFree { true })
        assertEquals(2, source.asked.size)
        // After the round's back-off, the variants that failed still wait out their own
        now += BackupMemory.ROUND_BACKOFF_MS * 1_000_000L + 1
        assertTrue(memory.mayTry())
        assertTrue(memory.order().isEmpty())
        now += BackupMemory.VARIANT_BACKOFF_MS * 1_000_000L
        assertEquals(listOf(embed, android), memory.order())
    }

    @Test
    fun `the variant that worked is tried first, and back-offs double up to their cap`() {
        val memory = BackupMemory(listOf(embed, android)) { now }
        memory.worked(android)
        assertEquals(listOf(android, embed), memory.order())
        memory.failed(android)
        assertEquals(listOf(embed), memory.order())
        assertEquals(5 * 60_000_000_000L, BackupMemory.backoffNanos(BackupMemory.VARIANT_BACKOFF_MS, 1, BackupMemory.VARIANT_BACKOFF_MAX_MS))
        assertEquals(10 * 60_000_000_000L, BackupMemory.backoffNanos(BackupMemory.VARIANT_BACKOFF_MS, 2, BackupMemory.VARIANT_BACKOFF_MAX_MS))
        assertEquals(60 * 60_000_000_000L, BackupMemory.backoffNanos(BackupMemory.VARIANT_BACKOFF_MS, 9, BackupMemory.VARIANT_BACKOFF_MAX_MS))
    }

    @Test
    fun `a backup covering the real pre-roll joins the stream without a hold`() {
        val ad = resource("twitch-preroll.m3u8")
        val clean = resource("twitch-clean.m3u8")
        val backupUrl = "https://video-weaver.example.hls.ttvnw.net/v1/playlist/embed.m3u8"
        val fetch = { url: String -> if (url == mainUrl) ad else clean }
        val memory = BackupMemory(listOf(embed)) { now }
        val f = LiveAdFilter(mainUrl, "test", fetch, BackupChooser(rendition, FakeSource(mapOf(embed to backupUrl)), fetch, memory, "t")) { now }
        f.refresh()
        assertFalse(f.holding)
        assertEquals(15, uris(f.playlist()).size)
        // The ad playlist's own stream segment (live sequence 9408) follows the backup's last one (9407)
        f.refresh()
        assertFalse(f.holding)
        assertEquals(0, f.breaks)
        assertEquals(LiveAdFilter.MAIN, f.sourceName)
        assertEquals("https://0123456789ab.j.cloudfront.hls.ttvnw.net/v1/segment/REDACTED_SEGMENT_0017?dna=REDACTED", uris(f.playlist()).last())
    }

    @Test
    fun `the session drains, then the card shows`() {
        val hold = 0L
        assertFalse(AdBreakTiming.drained(hold, 2_000_000_000L, 1_900_000_000L))
        assertTrue(AdBreakTiming.drained(hold, 2_000_000_000L, 1_200_000_000L))
        assertTrue(AdBreakTiming.drained(hold, 2_000_000_000L, 0L), "never showed a frame")
        assertTrue(AdBreakTiming.drained(hold, 10_500_000_000L, 10_400_000_000L), "played on too long")
    }

    private fun resource(name: String): String =
        javaClass.getResource("/adskip/$name")!!.readText()
}
