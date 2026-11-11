package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.api.media.source.LiveStreamUrls
import com.dreamdisplays.core.protocol.common.packets.ScreenShareState
import com.dreamdisplays.api.playback.model.PlaybackSpeed
import com.dreamdisplays.core.queue.QueueItem
import com.dreamdisplays.core.share.ScreenShareServerRules
import com.dreamdisplays.core.sync.SavedPlayback
import com.dreamdisplays.core.share.ScreenShareServerRules.EndReason
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.ActionThrottle
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.playback.PlaybackContexts
import com.dreamdisplays.platform.server.playback.QueueManager
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.utils.PlatformUtil
import com.dreamdisplays.platform.server.utils.WorldGuardRegions
import com.dreamdisplays.shared.playback.PlaybackPermissions
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Built-in screen sharing: a player with the share permission points a display at a live relay stream that their own
 * client publishes (`[live]` relay URLs in the config). The display shows the relay's play URL while the share runs
 * and gets its previous video back when it ends: on request, when the sharer leaves, changes world, walks out of
 * range or stops heartbeating (see [ScreenShareServerRules]), or when an op runs `/display share stop`. Someone setting
 * another video by hand ends it too (that video stays). The share URL is never saved, so a restart mid-share also comes
 * back to the previous video.
 */
@PaperOnly
@NullMarked
object ScreenShareActions {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()
    private val throttle = ActionThrottle()
    private const val REQUEST_COOLDOWN_MS = 1_000L

    private class Share(
        val displayId: UUID,
        val sharerId: UUID,
        val streamId: String,
        val previousUrl: String,
        val previousLang: String,
        /** Where the previous video stood when the share started (null: no shared clock, e.g. Local). */
        val previousPlayback: SavedPlayback?,
        /** The queue entry the previous video was, if any. */
        val previousEntry: QueueItem?,
    ) {
        /** When the sharer's last heartbeat (or the start) arrived, epoch ms. */
        @Volatile
        var lastHeartbeatMs: Long = System.currentTimeMillis()
    }

    /** Running shares by display. */
    private val byDisplay = ConcurrentHashMap<UUID, Share>()

    /** True when [player] may share at all: a relay is configured and they hold the share permission. */
    fun mayShare(player: Player): Boolean =
        PaperServer.config.settings.live.shareEnabled && player.hasPermission(PaperServer.config.permissions.share)

    /** Handles a client's start / stop request for display [displayId]. */
    fun onRequest(player: Player, displayId: UUID, start: Boolean) {
        if (!start) {
            val share = byDisplay[displayId]
            if (share != null && (share.sharerId == player.uniqueId || isAdmin(player))) end(share, EndReason.REQUESTED)
            else reply(player, displayId, active = false)
            return
        }
        if (!throttle.tryAcquire(player.uniqueId, REQUEST_COOLDOWN_MS)) return
        val refusal = refusalFor(player, displayId)
        if (refusal != null) return reply(player, displayId, active = false, error = refusal)
        val display = DisplayManager.getDisplayData(displayId) as PaperDisplayData

        // Only this player is ever sent the publish URL (it carries the relay's publish credentials); everyone sees the
        // play URL on the display
        // One share per player: starting on another display ends the old one first
        byDisplay.values.filter { it.sharerId == player.uniqueId }.forEach { end(it, EndReason.REPLACED) }
        byDisplay[displayId]?.let { end(it, EndReason.REPLACED) }

        val live = PaperServer.config.settings.live
        val streamId = newStreamId()
        val share = Share(
            displayId, player.uniqueId, streamId, display.url, display.lang,
            SavedPlayback.of(TimelineManager.timelineOf(display.id), System.currentTimeMillis()),
            QueueManager.playingEntry(display),
        )
        byDisplay[displayId] = share
        DisplayActions.applyVideo(display, LiveStreamUrls.fromTemplate(live.relay_play_url, streamId), "", player, persist = false)
        logger.info("{} started sharing their screen to display {}.", player.name, display.id)
        reply(player, displayId, active = true, publishUrl = LiveStreamUrls.fromTemplate(live.relay_publish_url, streamId))
    }

    /** Why [player] may not share to [displayId] right now (a message for them), or null when they may. */
    private fun refusalFor(player: Player, displayId: UUID): String? {
        if (!PaperServer.config.settings.live.shareEnabled) return "Screen sharing is not set up on this server."
        if (!player.hasPermission(PaperServer.config.permissions.share)) return "You may not share your screen here."
        val display = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return "That display is gone."
        val ctx = PlaybackContexts.of(display, player.uniqueId, isAdmin(player)) {
            WorldGuardRegions.isRegionMember(player, display.pos1)
        }
        if (!PlaybackPermissions.canSetVideo(ctx)) return "You may not change this display's video."
        if (!DisplayManager.isPlayerInRange(player, display)) return "Get closer to the display to share to it."
        val running = byDisplay[displayId]
        if (running != null && running.sharerId != player.uniqueId && !isAdmin(player)) {
            return "Someone is already sharing to this display."
        }
        return null
    }

    /** Someone set a video by hand: a share on that display is over, and its previous video is not restored. */
    fun onVideoSetByHand(displayId: UUID) {
        val share = byDisplay[displayId] ?: return
        end(share, EndReason.VIDEO_SET_BY_HAND, error = "Your screen share was replaced.")
    }

    /** The sharer left: their shares end and the displays get their previous videos back. */
    fun onPlayerQuit(playerId: UUID) {
        byDisplay.values.filter { it.sharerId == playerId }.forEach { end(it, EndReason.SHARER_QUIT) }
    }

    /** The sharer changed world: their shares end (a display lives in one world). */
    fun onPlayerChangedWorld(playerId: UUID) {
        byDisplay.values.filter { it.sharerId == playerId }.forEach { end(it, EndReason.SHARER_CHANGED_WORLD) }
    }

    /** The display was deleted: forget its share (the sharer's client stops encoding). */
    fun onDisplayRemoved(displayId: UUID) {
        val share = byDisplay[displayId] ?: return
        end(share, EndReason.DISPLAY_REMOVED)
    }

    /**
     * The sharing client is still capturing to [displayId]. A heartbeat for a share this server does not know (ended
     * here, or never started) is answered with "inactive", so a client that missed the end stops capturing.
     */
    fun onHeartbeat(player: Player, displayId: UUID) {
        val share = byDisplay[displayId]
        if (share != null && share.sharerId == player.uniqueId) {
            share.lastHeartbeatMs = System.currentTimeMillis()
            return
        }
        reply(player, displayId, active = false)
    }

    /**
     * `/display share stop`: ends the share on [displayId] at once (previous video restored, sharer's capture stopped).
     * Returns the sharer's name, or null when nothing is shared there.
     */
    fun adminStop(displayId: UUID): String? {
        val share = byDisplay[displayId] ?: return null
        val name = Bukkit.getPlayer(share.sharerId)?.name ?: Bukkit.getOfflinePlayer(share.sharerId).name ?: share.sharerId.toString()
        end(share, EndReason.ADMIN_STOP, error = "An operator stopped your screen share.")
        return name
    }

    /** Displays with a share running now, for command suggestions. */
    fun sharedDisplays(): Set<UUID> = byDisplay.keys.toSet()

    /**
     * Once a second (global tick): ends every share whose sharer is gone, in another world, out of range, or silent
     * for longer than [ScreenShareServerRules.HEARTBEAT_TIMEOUT_MS].
     */
    fun tick() {
        if (byDisplay.isEmpty()) return
        val now = System.currentTimeMillis()
        for (share in byDisplay.values.toList()) {
            val display = DisplayManager.getDisplayData(share.displayId) as? PaperDisplayData
            if (display == null) {
                end(share, EndReason.DISPLAY_REMOVED)
                continue
            }
            val sharer = Bukkit.getPlayer(share.sharerId)
            val online = sharer != null && sharer.isOnline
            // Folia's global thread may not read entity positions: its proximity index answers range there
            val sameWorld = !online || PlatformUtil.isFolia || sharer!!.world == display.pos1.world
            val inRange = !online || !sameWorld || if (PlatformUtil.isFolia) {
                share.sharerId in DisplayManager.getTrackedNearbyPlayerIds(display)
            } else {
                DisplayManager.isPlayerInRange(sharer!!, display)
            }
            val reason = ScreenShareServerRules.endReason(
                ScreenShareServerRules.SharerState(
                    online = online, sameWorld = sameWorld, inRange = inRange, heartbeatExpected = true,
                    lastHeartbeatMs = share.lastHeartbeatMs, nowMs = now,
                ),
            ) ?: continue
            end(share, reason)
        }
    }

    /** Ends every share (plugin disable), restoring each display before it is saved. */
    fun stopAll() {
        byDisplay.values.toList().forEach { end(it, EndReason.PLUGIN_DISABLE) }
    }

    private fun end(share: Share, reason: EndReason, error: String = "") {
        if (!byDisplay.remove(share.displayId, share)) return
        val display = DisplayManager.getDisplayData(share.displayId) as? PaperDisplayData
        val sharer = Bukkit.getPlayer(share.sharerId)
        // Tell the sharer first, so their capture stops before (or as) the display changes back
        if (ScreenShareServerRules.notifiesSharer(reason) && sharer != null) {
            reply(sharer, share.displayId, active = false, error = error)
        }
        // Only while the display still shows this share: anything else was set since and stays. The previous video
        // comes back paused where it was, at its speed, and still the queue entry it was; it never plays by itself.
        if (ScreenShareServerRules.restoresPreviousVideo(reason) && display != null && display.url.contains(share.streamId)) {
            DisplayActions.applyVideo(
                display, share.previousUrl, share.previousLang, sharer, persist = false,
                restore = share.previousPlayback ?: SavedPlayback(0L, PlaybackSpeed.NORMAL),
            )
            QueueManager.resumePlayingEntry(display, share.previousEntry)
        }
        logger.info("Screen share to display {} ended ({}).", share.displayId, reason.name.lowercase(Locale.ROOT))
    }

    private fun reply(player: Player, displayId: UUID, active: Boolean, publishUrl: String = "", error: String = "") {
        val live = PaperServer.config.settings.live
        PaperV2Networking.send(
            [player],
            ScreenShareState(displayId, active, publishUrl, error, live.share_encoder, live.share_max_fps, live.share_max_height),
        )
    }

    private fun isAdmin(player: Player) = player.hasPermission(PaperServer.config.permissions.deleteOthers)

    /** 20 random characters: the stream path doubles as the only secret guarding the relay's publish side. */
    private fun newStreamId(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return "dd" + (1..20).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}
