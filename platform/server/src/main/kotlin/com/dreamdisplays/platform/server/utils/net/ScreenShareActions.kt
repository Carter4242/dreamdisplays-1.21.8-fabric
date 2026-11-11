package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.core.protocol.common.packets.ScreenShareState
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.ActionThrottle
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.playback.PlaybackContexts
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
 * and gets its previous video back when it ends: on request, when the sharer leaves, or when someone sets another
 * video by hand. The share URL is never saved, so a restart mid-share also comes back to the previous video.
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
    )

    /** Running shares by display. */
    private val byDisplay = ConcurrentHashMap<UUID, Share>()

    /** True when [player] may share at all: a relay is configured and they hold the share permission. */
    fun mayShare(player: Player): Boolean =
        PaperServer.config.settings.live.shareEnabled && player.hasPermission(PaperServer.config.permissions.share)

    /** Handles a client's start / stop request for display [displayId]. */
    fun onRequest(player: Player, displayId: UUID, start: Boolean) {
        if (!start) {
            val share = byDisplay[displayId]
            if (share != null && (share.sharerId == player.uniqueId || isAdmin(player))) end(share, notifySharer = true)
            else reply(player, displayId, active = false)
            return
        }
        if (!throttle.tryAcquire(player.uniqueId, REQUEST_COOLDOWN_MS)) return
        val refusal = refusalFor(player, displayId)
        if (refusal != null) return reply(player, displayId, active = false, error = refusal)
        val display = DisplayManager.getDisplayData(displayId) as PaperDisplayData

        // One share per player: starting on another display ends the old one first
        byDisplay.values.filter { it.sharerId == player.uniqueId }.forEach { end(it, notifySharer = true) }
        byDisplay[displayId]?.let { end(it, notifySharer = true) }

        val live = PaperServer.config.settings.live
        val streamId = newStreamId()
        val share = Share(displayId, player.uniqueId, streamId, display.url, display.lang)
        byDisplay[displayId] = share
        DisplayActions.applyVideo(display, live.relay_play_url.replace("{id}", streamId), "", player, persist = false)
        logger.info("{} started sharing their screen to display {}.", player.name, display.id)
        reply(player, displayId, active = true, publishUrl = live.relay_publish_url.replace("{id}", streamId))
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
        val share = byDisplay.remove(displayId) ?: return
        Bukkit.getPlayer(share.sharerId)?.let { reply(it, displayId, active = false, error = "Your screen share was replaced.") }
    }

    /** The sharer left: their shares end and the displays get their previous videos back. */
    fun onPlayerQuit(playerId: UUID) {
        byDisplay.values.filter { it.sharerId == playerId }.forEach { end(it, notifySharer = false) }
    }

    /** The display was deleted: forget its share (the sharer's client stops encoding). */
    fun onDisplayRemoved(displayId: UUID) {
        val share = byDisplay.remove(displayId) ?: return
        Bukkit.getPlayer(share.sharerId)?.let { reply(it, displayId, active = false) }
    }

    /** Ends every share (plugin disable), restoring each display before it is saved. */
    fun stopAll() {
        byDisplay.values.toList().forEach { end(it, notifySharer = true) }
    }

    private fun end(share: Share, notifySharer: Boolean) {
        if (!byDisplay.remove(share.displayId, share)) return
        val display = DisplayManager.getDisplayData(share.displayId) as? PaperDisplayData
        val sharer = Bukkit.getPlayer(share.sharerId)
        // Only while the display still shows this share: anything else was set since and stays
        if (display != null && display.url.contains(share.streamId)) {
            DisplayActions.applyVideo(display, share.previousUrl, share.previousLang, sharer, persist = false)
        }
        if (notifySharer && sharer != null) reply(sharer, share.displayId, active = false)
        logger.info("Screen share to display {} ended.", share.displayId)
    }

    private fun reply(player: Player, displayId: UUID, active: Boolean, publishUrl: String = "", error: String = "") {
        PaperV2Networking.send([player], ScreenShareState(displayId, active, publishUrl, error))
    }

    private fun isAdmin(player: Player) = player.hasPermission(PaperServer.config.permissions.deleteOthers)

    /** 20 random characters: the stream path doubles as the only secret guarding the relay's publish side. */
    private fun newStreamId(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return "dd" + (1..20).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}
