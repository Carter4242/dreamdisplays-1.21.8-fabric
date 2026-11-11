package com.dreamdisplays.core.karaoke

import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals

class KaraokeRoutingTest {
    private val bubs = UUID.randomUUID()
    private val jester = UUID.randomUUID()
    private val alt = UUID.randomUUID()

    private fun route(sender: UUID, receiver: UUID, singing: Set<UUID>?, range: Boolean = true, near: Boolean = true, fromSinger: Boolean = true) =
        KaraokeRouting.route(sender, receiver, singing, range, near, fromSinger)

    @Test
    fun singersHearEachOtherNormallyAndListenersGetTheAlignedCopy() {
        val duet = setOf(bubs, jester)
        assertEquals(KaraokeRoute.NORMAL, route(jester, bubs, duet))
        assertEquals(KaraokeRoute.NORMAL, route(bubs, jester, duet))
        assertEquals(KaraokeRoute.NONE, route(jester, bubs, duet, near = false))
        assertEquals(KaraokeRoute.ALIGNED, route(jester, alt, duet))
        assertEquals(KaraokeRoute.NONE, route(jester, jester, duet))
        // A listener's own voice is never touched
        assertEquals(KaraokeRoute.NORMAL, route(alt, jester, duet))
    }

    @Test
    fun voiceSourceDecidesWhetherPlayersNearTheSingerButOutOfRangeHearIt() {
        val solo = setOf(jester)
        assertEquals(KaraokeRoute.ALIGNED, route(jester, alt, solo, range = false, near = true, fromSinger = true))
        assertEquals(KaraokeRoute.NONE, route(jester, alt, solo, range = false, near = true, fromSinger = false))
        assertEquals(KaraokeRoute.NONE, route(jester, alt, solo, range = false, near = false, fromSinger = true))
        assertEquals(KaraokeRoute.ALIGNED, route(jester, alt, solo, range = true, near = false, fromSinger = false))
    }

    @Test
    fun coSingersAreQuietOnlyWhileASongPlays() {
        assertEquals(0.15, KaraokeRouting.coSingerVolume(songPlaying = true, configured = 0.15))
        assertEquals(1.0, KaraokeRouting.coSingerVolume(songPlaying = false, configured = 0.15))
        assertEquals(0.0, KaraokeRouting.coSingerVolume(songPlaying = true, configured = 0.0))
        assertEquals(1.0, KaraokeRouting.coSingerVolume(songPlaying = true, configured = 7.0))
    }

    @Test
    fun addThenRemoveRestoresNormalRouting() {
        val players = listOf(bubs, jester, alt)
        fun all(singing: Set<UUID>?) = players.flatMap { s -> players.filter { it != s }.map { route(s, it, singing) } }
        assertEquals(List(6) { KaraokeRoute.NORMAL }, all(null))
        assertEquals(KaraokeRoute.ALIGNED, route(jester, alt, setOf(jester)))
        // Removed (singer remove, karaoke off, song over, away or logged out): the stage no longer lists them
        assertEquals(List(6) { KaraokeRoute.NORMAL }, all(null))
        assertEquals(List(6) { KaraokeRoute.NORMAL }, all(setOf(UUID.randomUUID())))
    }
}
