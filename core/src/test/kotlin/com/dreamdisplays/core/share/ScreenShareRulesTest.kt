package com.dreamdisplays.core.share

import com.dreamdisplays.core.share.ScreenShareCaptureRules.Observation
import com.dreamdisplays.core.share.ScreenShareCaptureRules.StopReason
import com.dreamdisplays.core.share.ScreenShareServerRules.EndReason
import com.dreamdisplays.core.share.ScreenShareServerRules.SharerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenShareRulesTest {
    private val share = "rtmps://live.example.org:1935/ddabcdefghij0123456789"
    private val srt = "srt://relay.example.org:8890?streamid=read:ddabc"
    private val youtube = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    // --- no persistence -----------------------------------------------------------------------------------------

    @Test
    fun `every relay scheme is private`() {
        for (url in listOf(share, srt, "rtmp://h/x", "rtsp://h:8554/x", "  RTMP://h/x  ")) {
            assertTrue(ScreenSharePrivacy.isPrivateStream(url), url)
            assertFalse(ScreenSharePrivacy.mayPersist(url), url)
            assertFalse(ScreenSharePrivacy.mayPreview(url), url)
            assertFalse(ScreenSharePrivacy.mayPreload(url), url)
            assertFalse(ScreenSharePrivacy.maySnapshot(url), url)
        }
    }

    @Test
    fun `ordinary videos and empty urls are not private`() {
        for (url in listOf(youtube, "https://cdn.example.org/v.mp4", "", null)) {
            assertFalse(ScreenSharePrivacy.isPrivateStream(url), url.toString())
            assertTrue(ScreenSharePrivacy.mayPersist(url), url.toString())
            assertTrue(ScreenSharePrivacy.mayPreview(url), url.toString())
            assertTrue(ScreenSharePrivacy.mayPreload(url), url.toString())
            assertTrue(ScreenSharePrivacy.maySnapshot(url), url.toString())
        }
    }

    // --- viewers ------------------------------------------------------------------------------------------------

    @Test
    fun `leaving a share clears the picture at once`() {
        assertTrue(ScreenShareViewerRules.clearOnUrlChange(share, youtube))
        assertTrue(ScreenShareViewerRules.clearOnUrlChange(share, ""))
        assertTrue(ScreenShareViewerRules.clearOnUrlChange(share, srt)) // another share replaces it
        assertFalse(ScreenShareViewerRules.clearOnUrlChange(share, share)) // only the language changed
        assertFalse(ScreenShareViewerRules.clearOnUrlChange(youtube, share)) // starting a share
        assertFalse(ScreenShareViewerRules.clearOnUrlChange(youtube, ""))
        assertFalse(ScreenShareViewerRules.clearOnUrlChange(null, youtube))
    }

    @Test
    fun `a share ending on an empty display stops the player`() {
        assertTrue(ScreenShareViewerRules.stopPlayerOnUrlChange(share, ""))
        assertTrue(ScreenShareViewerRules.stopPlayerOnUrlChange(share, null))
        assertFalse(ScreenShareViewerRules.stopPlayerOnUrlChange(share, youtube)) // the next video's load replaces it
        assertFalse(ScreenShareViewerRules.stopPlayerOnUrlChange(youtube, ""))
    }

    @Test
    fun `a stale share picture is hidden, other videos keep theirs`() {
        assertTrue(ScreenShareViewerRules.hidePicture(share, stale = true))
        assertFalse(ScreenShareViewerRules.hidePicture(share, stale = false))
        assertFalse(ScreenShareViewerRules.hidePicture(youtube, stale = true))
        assertTrue(ScreenShareViewerRules.STALE_PICTURE_NANOS in 1..2_000_000_000L)
    }

    @Test
    fun `no seamless hold of a share's last frame`() {
        assertFalse(ScreenShareViewerRules.maySwapSeamlessly(share))
        assertTrue(ScreenShareViewerRules.maySwapSeamlessly(youtube))
    }

    // --- the sharer's capture -----------------------------------------------------------------------------------

    private fun seen(
        known: Boolean = true, url: String? = share, shareUrl: String? = share, sameDim: Boolean? = true, dist: Double? = 10.0,
    ) = Observation(known, url, shareUrl, sameDim, dist)

    @Test
    fun `capture runs on while everything matches`() {
        assertNull(ScreenShareCaptureRules.check(seen()))
        // Before the display showed the share (its update is still on the way) nothing is judged by URL
        assertNull(ScreenShareCaptureRules.check(seen(url = youtube, shareUrl = null)))
        // Unknown dimension / distance (display held for a distance re-load) does not stop it
        assertNull(ScreenShareCaptureRules.check(seen(url = null, sameDim = null, dist = null)))
    }

    @Test
    fun `capture stops on every lifecycle reason`() {
        assertEquals(StopReason.DISPLAY_REMOVED, ScreenShareCaptureRules.check(seen(known = false)))
        assertEquals(StopReason.OTHER_DIMENSION, ScreenShareCaptureRules.check(seen(sameDim = false)))
        assertEquals(StopReason.OUT_OF_RANGE, ScreenShareCaptureRules.check(seen(dist = ScreenShareCaptureRules.MAX_DISTANCE_BLOCKS + 1)))
        assertEquals(StopReason.DISPLAY_URL_CHANGED, ScreenShareCaptureRules.check(seen(url = youtube)))
        assertEquals(StopReason.DISPLAY_URL_CHANGED, ScreenShareCaptureRules.check(seen(url = "")))
        assertEquals(StopReason.DISPLAY_URL_CHANGED, ScreenShareCaptureRules.check(seen(url = srt)))
    }

    @Test
    fun `capture stops between worlds, where the display looks fine and no badge can show`() {
        // A proxy's configuration phase: the display is still known, dimension and distance are unknown
        val between = seen(url = null, sameDim = null, dist = null).copy(inWorld = false)
        assertEquals(StopReason.SERVER_LEFT, ScreenShareCaptureRules.check(between))
        // The old backend ended it already, and the new one never had it
        assertFalse(ScreenShareCaptureRules.tellServer(StopReason.SERVER_LEFT))
    }

    @Test
    fun `the sharer hears none of their own share, other displays and videos play on`() {
        assertTrue(ScreenShareViewerRules.muteOwnShare(sharingToIt = true, url = share))
        assertFalse(ScreenShareViewerRules.muteOwnShare(sharingToIt = false, url = share))
        // Asked, the display still shows its old video until the share reaches it
        assertFalse(ScreenShareViewerRules.muteOwnShare(sharingToIt = true, url = youtube))
    }

    @Test
    fun `the share url is remembered from the first private stream the display shows`() {
        assertNull(ScreenShareCaptureRules.shareUrlAfter(seen(url = youtube, shareUrl = null)))
        assertEquals(share, ScreenShareCaptureRules.shareUrlAfter(seen(url = share, shareUrl = null)))
        assertEquals(share, ScreenShareCaptureRules.shareUrlAfter(seen(url = youtube, shareUrl = share)))
        // The relay stream the display already showed before the share is not the share
        val before = seen(url = srt, shareUrl = null).copy(urlBeforeShare = srt)
        assertNull(ScreenShareCaptureRules.shareUrlAfter(before))
        assertNull(ScreenShareCaptureRules.check(before))
        assertEquals(share, ScreenShareCaptureRules.shareUrlAfter(before.copy(displayUrl = share)))
    }

    @Test
    fun `the server is told unless it ended the share or the link is gone`() {
        val silent = setOf(StopReason.SERVER_ENDED, StopReason.SERVER_LEFT, StopReason.SHUTDOWN)
        for (reason in StopReason.entries) {
            assertEquals(reason !in silent, ScreenShareCaptureRules.tellServer(reason), reason.name)
        }
    }

    @Test
    fun `watchdog kills any capture that is not the current share's`() {
        assertFalse(ScreenShareCaptureRules.isOrphan(sharing = true, isCurrent = true))
        assertTrue(ScreenShareCaptureRules.isOrphan(sharing = true, isCurrent = false))
        assertTrue(ScreenShareCaptureRules.isOrphan(sharing = false, isCurrent = true))
        assertTrue(ScreenShareCaptureRules.isOrphan(sharing = false, isCurrent = false))
    }

    // --- the server ---------------------------------------------------------------------------------------------

    private fun sharer(
        online: Boolean = true, sameWorld: Boolean = true, inRange: Boolean = true, expected: Boolean = true,
        silentMs: Long = 0L,
    ) = SharerState(online, sameWorld, inRange, expected, lastHeartbeatMs = 100_000L, nowMs = 100_000L + silentMs)

    @Test
    fun `server keeps a healthy share`() {
        assertNull(ScreenShareServerRules.endReason(sharer()))
        assertNull(ScreenShareServerRules.endReason(sharer(silentMs = ScreenShareServerRules.HEARTBEAT_TIMEOUT_MS)))
        assertNull(ScreenShareServerRules.endReason(sharer(expected = false, silentMs = 60_000L)))
    }

    @Test
    fun `server ends a share on each trigger`() {
        assertEquals(EndReason.SHARER_QUIT, ScreenShareServerRules.endReason(sharer(online = false)))
        assertEquals(EndReason.SHARER_CHANGED_WORLD, ScreenShareServerRules.endReason(sharer(sameWorld = false)))
        assertEquals(EndReason.SHARER_OUT_OF_RANGE, ScreenShareServerRules.endReason(sharer(inRange = false)))
        assertEquals(
            EndReason.HEARTBEAT_LOST,
            ScreenShareServerRules.endReason(sharer(silentMs = ScreenShareServerRules.HEARTBEAT_TIMEOUT_MS + 1)),
        )
        assertTrue(ScreenShareServerRules.HEARTBEAT_INTERVAL_MS * 3 <= ScreenShareServerRules.HEARTBEAT_TIMEOUT_MS)
    }

    @Test
    fun `a sharer who may no longer share is stopped, and changed share settings end a running share`() {
        assertEquals(EndReason.NOT_PERMITTED, ScreenShareServerRules.endReason(SharerState(true, true, true, true, 0L, 0L, permitted = false)))
        assertEquals(EndReason.SHARER_QUIT, ScreenShareServerRules.endReason(SharerState(false, true, true, true, 0L, 0L, permitted = false)))
        data class Live(val sound: Boolean, val fps: Int)
        assertFalse(ScreenShareServerRules.endsOnSettingsChange(Live(true, 30), Live(true, 30)))
        assertTrue(ScreenShareServerRules.endsOnSettingsChange(Live(true, 30), Live(false, 30)))
        assertTrue(ScreenShareServerRules.restoresPreviousVideo(EndReason.SETTINGS_CHANGED))
        assertTrue(ScreenShareServerRules.notifiesSharer(EndReason.NOT_PERMITTED))
    }

    @Test
    fun `a server stall between share checks credits the heartbeats it held up`() {
        assertEquals(0L, ScreenShareServerRules.stallCreditMs(0L, 50_000L))
        assertEquals(0L, ScreenShareServerRules.stallCreditMs(10_000L, 11_000L))
        assertEquals(0L, ScreenShareServerRules.stallCreditMs(10_000L, 12_000L))
        assertEquals(12_000L, ScreenShareServerRules.stallCreditMs(10_000L, 23_000L))
        // A share silent through a 12 s stall is not ended at the first check after it
        val credited = 10_000L + 12_000L
        assertNull(ScreenShareServerRules.endReason(SharerState(true, true, true, true, credited, 23_000L)))
    }

    @Test
    fun `only shares started with a nonce are expected to heartbeat`() {
        assertFalse(ScreenShareServerRules.expectsHeartbeat(0L))
        assertTrue(ScreenShareServerRules.expectsHeartbeat(42L))
        assertTrue(ScreenShareServerRules.expectsHeartbeat(-7L))
    }

    @Test
    fun `previous video comes back unless someone replaced it or the display is gone`() {
        for (reason in EndReason.entries) {
            val expected = reason != EndReason.VIDEO_SET_BY_HAND && reason != EndReason.DISPLAY_REMOVED
            assertEquals(expected, ScreenShareServerRules.restoresPreviousVideo(reason), reason.name)
        }
        assertFalse(ScreenShareServerRules.notifiesSharer(EndReason.SHARER_QUIT))
        assertTrue(ScreenShareServerRules.notifiesSharer(EndReason.ADMIN_STOP))
        assertTrue(ScreenShareServerRules.notifiesSharer(EndReason.HEARTBEAT_LOST))
    }

    @Test
    fun `a share is over once its display shows something else`() {
        assertTrue(ScreenShareServerRules.stillShown("rtmps://relay.example:1935/ddabc123", "ddabc123"))
        assertFalse(ScreenShareServerRules.stillShown("https://youtu.be/xyz", "ddabc123"))
        assertFalse(ScreenShareServerRules.stillShown("", "ddabc123"))
    }

    @Test
    fun `a stop or heartbeat only touches the share it names`() {
        assertTrue(ScreenShareServerRules.sameShare(7L, 7L))
        assertFalse(ScreenShareServerRules.sameShare(7L, 6L))
        // Older client (no nonce) or a share started by one: by display alone
        assertTrue(ScreenShareServerRules.sameShare(7L, 0L))
        assertTrue(ScreenShareServerRules.sameShare(0L, 6L))
    }
}
