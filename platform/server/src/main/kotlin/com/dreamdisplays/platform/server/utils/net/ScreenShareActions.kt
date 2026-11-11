package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.core.protocol.common.packets.RemotePlaybackToggle
import com.dreamdisplays.core.protocol.common.packets.ScreenShareState
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.model.PlaybackSpeed
import com.dreamdisplays.core.queue.QueueItem
import com.dreamdisplays.core.share.ScreenShareRelayTemplates
import com.dreamdisplays.core.share.ScreenShareServerRules
import com.dreamdisplays.core.sync.SavedPlayback
import com.dreamdisplays.core.share.ScreenShareServerRules.EndReason
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.SettingsSection
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.ActionThrottle
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.StoredVideo
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplays.platform.server.playback.PaperPlaybackTransport
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
 *
 * With a relay lease service (`[live] relay_control_url`, see [RelayControl]) every share gets its own publish key, a
 * start is only answered once the service knows the share, and the relay cuts the stream as soon as the share ends
 * here, even if the sharer's capture keeps running.
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
        /** The previous video's stored length (ns), saved in its place while the share runs. */
        val previousDuration: Long?,
        /** The sharer's [ScreenShareRequest.nonce] for this share (0: an older client), echoed on every state about it. */
        val nonce: Long,
        /** This share's relay publish key (empty without a lease service). */
        val key: String,
        /** The `[live]` settings the share started under: the sharer's capture keeps them until it ends. */
        val live: SettingsSection.LiveConfig,
    ) {
        /** When the sharer's last heartbeat (or the start) arrived, epoch ms. */
        @Volatile
        var lastHeartbeatMs: Long = System.currentTimeMillis()
    }

    /** Running shares by display. */
    private val byDisplay = ConcurrentHashMap<UUID, Share>()

    /** A start the relay lease service has not confirmed yet (nothing on the display changed so far). */
    private class PendingStart(val displayId: UUID, val sharerId: UUID, val streamId: String, val key: String, val nonce: Long) {
        /** When the start was asked for: the share's start log says how long the relay lease took. */
        val askedNanos: Long = System.nanoTime()
    }

    /** Starts waiting on the relay lease service, by display. */
    private val starting = ConcurrentHashMap<UUID, PendingStart>()

    /**
     * What [displayId]'s row stores while it shows ([url]) a share: the video it had before, never the relay URL (every
     * save of the display, by any path, goes through this). Null when it shows anything else, even with the share not
     * ended yet (a video set by a command, an addon or the queue is saved before [tick] ends the share).
     */
    fun storedVideo(displayId: UUID, url: String): StoredVideo? =
        byDisplay[displayId]?.takeIf { ScreenShareServerRules.stillShown(url, it.streamId) }
            ?.let { StoredVideo(it.previousUrl, it.previousLang, it.previousDuration) }

    /** True when [player] may share at all: a relay is configured and they hold the share permission. */
    fun mayShare(player: Player): Boolean =
        PaperServer.config.settings.live.shareEnabled && player.hasPermission(PaperServer.config.permissions.share)

    /** Handles a client's start / stop request for display [displayId]; [nonce] names the share (0: an older client). */
    fun onRequest(player: Player, displayId: UUID, start: Boolean, nonce: Long = 0L) {
        if (!start) {
            val pending = starting[displayId]
            if (pending != null && pending.sharerId == player.uniqueId && ScreenShareServerRules.sameShare(pending.nonce, nonce)) {
                if (starting.remove(displayId, pending)) syncRelay()
                return reply(player, displayId, active = false, nonce = nonce)
            }
            val share = byDisplay[displayId]
            val own = share != null && share.sharerId == player.uniqueId
            // The sharer's stop for an earlier share on this display must not end the one that replaced it
            if (share != null && own && ScreenShareServerRules.sameShare(share.nonce, nonce)) {
                end(share, EndReason.REQUESTED)
            } else if (share != null && !own && isAdmin(player)) {
                end(share, EndReason.REQUESTED, error = OPERATOR_STOPPED)
            } else {
                reply(player, displayId, active = false, nonce = nonce)
            }
            return
        }
        if (!throttle.tryAcquire(player.uniqueId, REQUEST_COOLDOWN_MS)) {
            // Answer it: a dropped start would leave the client waiting with nothing on screen
            return reply(player, displayId, active = false, error = "Wait a moment before sharing again.", nonce = nonce)
        }
        val refusal = refusalFor(player, displayId)
        if (refusal != null) return reply(player, displayId, active = false, error = refusal, nonce = nonce)
        val streamId = newStreamId()
        if (!RelayControl.enabled) return begin(player, displayId, streamId, key = "", nonce = nonce)

        // The relay must know the share (and its key) before the client publishes, or the publish is refused
        val pending = PendingStart(displayId, player.uniqueId, streamId, newKey(), nonce)
        starting.values.filter { it.sharerId == player.uniqueId }.forEach { starting.remove(it.displayId, it) }
        starting[displayId] = pending
        syncRelay { leased -> Scheduler.runSync { onLeased(pending, leased) } }
    }

    /** The lease service took (or could not take) [pending]'s share: start it now unless it was called off meanwhile. */
    private fun onLeased(pending: PendingStart, leased: Boolean) {
        // Cancelled, or replaced by a newer start: the next push no longer carries its key
        if (starting[pending.displayId] !== pending) return
        val player = Bukkit.getPlayer(pending.sharerId)
        val refusal = when {
            player == null -> null
            !leased -> "Screen sharing can't reach its relay right now. Try again in a moment."
            else -> refusalFor(player, pending.displayId)
        }
        if (player == null || refusal != null) {
            if (starting.remove(pending.displayId, pending)) syncRelay()
            if (player != null) reply(player, pending.displayId, active = false, error = refusal!!, nonce = pending.nonce)
            return
        }
        // Still in [starting] until the share is in [byDisplay]: a push from ending this sharer's older share keeps it
        begin(player, pending.displayId, pending.streamId, pending.key, pending.nonce, pending)
    }

    /** Starts [player]'s share to [displayId]: the display shows the relay stream and the sharer gets the publish URL. */
    private fun begin(
        player: Player, displayId: UUID, streamId: String, key: String, nonce: Long, pending: PendingStart? = null,
    ) {
        val display = DisplayManager.getDisplayData(displayId) as PaperDisplayData

        // Only this player is ever sent the publish URL (it carries the relay's publish credentials); everyone sees the
        // play URL on the display
        // One share per player: starting on another display ends the old one first
        byDisplay.values.filter { it.sharerId == player.uniqueId }.forEach { end(it, EndReason.REPLACED) }
        byDisplay[displayId]?.let { end(it, EndReason.REPLACED) }

        val live = PaperServer.config.settings.live
        val share = Share(
            displayId, player.uniqueId, streamId, display.url, display.lang,
            TimelineManager.savedPlaybackOf(display.id),
            QueueManager.playingEntry(display),
            display.duration,
            nonce,
            key,
            live,
        )
        byDisplay[displayId] = share
        if (pending != null) starting.remove(displayId, pending)
        DisplayActions.applyVideo(display, ScreenShareRelayTemplates.playUrl(live.relay_play_url, streamId), "", player, persist = false)
        if (pending == null) {
            logger.info("{} started sharing their screen to display {}.", player.name, display.id)
        } else {
            logger.info(
                "{} started sharing their screen to display {} (relay lease {} ms).",
                player.name, display.id, (System.nanoTime() - pending.askedNanos) / 1_000_000L,
            )
        }
        reply(
            player, displayId, active = true,
            publishUrl = ScreenShareRelayTemplates.publishUrl(live.relay_publish_url, streamId, key), nonce = nonce,
        )
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
        // The lease answer is checked on Folia's global thread, which may not read entity positions: its proximity index
        // answers range there, as in [tick]
        val inRange = if (PlatformUtil.isFolia) {
            player.uniqueId in DisplayManager.getTrackedNearbyPlayerIds(display)
        } else {
            DisplayManager.isPlayerInRange(player, display)
        }
        if (!inRange) return "Get closer to the display to share to it."
        val running = byDisplay[displayId]?.sharerId ?: starting[displayId]?.sharerId
        if (running != null && running != player.uniqueId && !isAdmin(player)) {
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
        cancelStarts { it.sharerId == playerId }
        byDisplay.values.filter { it.sharerId == playerId }.forEach { end(it, EndReason.SHARER_QUIT) }
    }

    /** The sharer changed world: their shares end (a display lives in one world). */
    fun onPlayerChangedWorld(playerId: UUID) {
        cancelStarts { it.sharerId == playerId }
        byDisplay.values.filter { it.sharerId == playerId }.forEach { end(it, EndReason.SHARER_CHANGED_WORLD) }
    }

    /** The display was deleted: forget its share (the sharer's client stops encoding). */
    fun onDisplayRemoved(displayId: UUID) {
        cancelStarts { it.displayId == displayId }
        val share = byDisplay[displayId] ?: return
        end(share, EndReason.DISPLAY_REMOVED)
    }

    /**
     * The sharing client is still capturing to [displayId]. A heartbeat for a share this server does not know (ended
     * here, never started, or an earlier share of [nonce] on this display) is answered with "inactive" for that
     * [nonce], so a client that missed the end stops that capture and nothing newer.
     */
    fun onHeartbeat(player: Player, displayId: UUID, nonce: Long = 0L) {
        val share = byDisplay[displayId]
        if (share != null && share.sharerId == player.uniqueId && ScreenShareServerRules.sameShare(share.nonce, nonce)) {
            share.lastHeartbeatMs = System.currentTimeMillis()
            return
        }
        reply(player, displayId, active = false, nonce = nonce)
    }

    /**
     * `/display share stop`: ends the share on [displayId] at once (previous video restored, sharer's capture stopped).
     * Returns the sharer's name, or null when nothing is shared there.
     */
    fun adminStop(displayId: UUID): String? {
        val share = byDisplay[displayId]
        if (share == null) {
            // Still waiting for its relay lease: called off before it starts
            val pending = starting[displayId] ?: return null
            if (!starting.remove(displayId, pending)) return null
            syncRelay()
            Bukkit.getPlayer(pending.sharerId)?.let {
                reply(it, displayId, active = false, error = OPERATOR_STOPPED, nonce = pending.nonce)
            }
            return nameOf(pending.sharerId)
        }
        val name = nameOf(share.sharerId)
        end(share, EndReason.ADMIN_STOP, error = OPERATOR_STOPPED)
        return name
    }

    private fun nameOf(playerId: UUID): String =
        Bukkit.getPlayer(playerId)?.name ?: Bukkit.getOfflinePlayer(playerId).name ?: playerId.toString()

    private const val OPERATOR_STOPPED = "An operator stopped your screen share."

    /** True while [displayId] shows a screen share or one is about to start on it. */
    fun isSharing(displayId: UUID): Boolean = byDisplay.containsKey(displayId) || starting.containsKey(displayId)

    /** Displays with a share running now or about to start (waiting for its relay lease), for commands. */
    fun sharedDisplays(): Set<UUID> = byDisplay.keys + starting.keys

    /**
     * Everyone a share's latency is measured between: each sharer and the players reporting on their display, and
     * every player who may share at all, so a sharer's client knows the server's clock before its first share starts
     * (its capture reads the offset once, at launch). They get the karaoke clock pings, which carry their clock offset
     * back, so the share's timestamps and the viewers' presentation times meet on the server's clock.
     */
    fun clockParticipants(viewersOf: (UUID) -> Collection<UUID>): Set<UUID> {
        val ids = HashSet(possibleSharers())
        starting.values.forEach { ids += it.sharerId }
        for (share in byDisplay.values) {
            ids += share.sharerId
            ids += viewersOf(share.displayId)
        }
        return ids
    }

    @Volatile
    private var possibleSharers: Pair<Long, Set<UUID>> = 0L to emptySet()

    /** Online players who may share, looked up at most once a second (the caller runs ten times a second). */
    private fun possibleSharers(): Set<UUID> {
        val now = System.currentTimeMillis()
        val (at, ids) = possibleSharers
        if (now - at < POSSIBLE_SHARERS_REFRESH_MS) return ids
        val fresh = if (!PaperServer.config.settings.live.shareEnabled) emptySet()
        else Bukkit.getOnlinePlayers().filter(::mayShare).mapTo(HashSet()) { it.uniqueId }
        possibleSharers = now to fresh
        return fresh
    }

    private const val POSSIBLE_SHARERS_REFRESH_MS = 1_000L

    /**
     * Once a second (global tick): ends every share whose display shows something else now, or whose sharer is gone, in
     * another world, out of range, or silent for longer than [ScreenShareServerRules.HEARTBEAT_TIMEOUT_MS].
     */
    fun tick() {
        val now = System.currentTimeMillis()
        val stallCredit = ScreenShareServerRules.stallCreditMs(lastTickMs, now)
        lastTickMs = now
        if (byDisplay.isEmpty()) return
        // Heartbeats queued behind a server stall are not the sharer's silence
        if (stallCredit > 0L) byDisplay.values.forEach { it.lastHeartbeatMs = maxOf(it.lastHeartbeatMs, minOf(now, it.lastHeartbeatMs + stallCredit)) }
        for (share in byDisplay.values.toList()) {
            val display = DisplayManager.getDisplayData(share.displayId) as? PaperDisplayData
            if (display == null) {
                end(share, EndReason.DISPLAY_REMOVED)
                continue
            }
            // Its video was set some other way (a command, an addon, the queue): the share no longer owns the display
            if (!ScreenShareServerRules.stillShown(display.url, share.streamId)) {
                end(share, EndReason.VIDEO_SET_BY_HAND, error = "Your screen share was replaced.")
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
                    online = online, sameWorld = sameWorld, inRange = inRange,
                    heartbeatExpected = ScreenShareServerRules.expectsHeartbeat(share.nonce),
                    lastHeartbeatMs = share.lastHeartbeatMs, nowMs = now,
                    permitted = !online || mayShare(sharer!!),
                ),
            ) ?: continue
            end(share, reason, error = if (reason == EndReason.NOT_PERMITTED) "You may no longer share your screen here." else "")
        }
    }

    /** When [tick] last ran, epoch ms (0: never). */
    @Volatile
    private var lastTickMs = 0L

    /**
     * The config was reloaded: a running share keeps the `[live]` settings it started with (its sound, encoder, limits
     * and relay), so every share started under other settings ends, and its sharer is asked to share again.
     */
    fun onSettingsChanged() {
        val live = PaperServer.config.settings.live
        byDisplay.values.filter { ScreenShareServerRules.endsOnSettingsChange(it.live, live) }.forEach {
            end(it, EndReason.SETTINGS_CHANGED, error = "Screen sharing settings changed. Share again to continue.")
        }
    }

    /** Ends every share (plugin disable), restoring each display before it is saved. */
    fun stopAll() {
        starting.clear()
        byDisplay.values.toList().forEach { end(it, EndReason.PLUGIN_DISABLE) }
        // The relay cuts every stream now rather than when the lease lapses
        RelayControl.shutdown()
    }

    /** Drops waiting starts matching [which] (their sharer is not answered: they left, or the display is gone). */
    private fun cancelStarts(which: (PendingStart) -> Boolean) {
        var any = false
        starting.values.filter(which).forEach { any = starting.remove(it.displayId, it) || any }
        if (any) syncRelay()
    }

    /**
     * Tells the relay lease service which shares run now (running and waiting starts) and so which streams it must
     * cut. [onAcked] (push thread): the service took this set, or could not be reached.
     */
    private fun syncRelay(onAcked: ((Boolean) -> Unit)? = null) {
        if (!RelayControl.enabled) return
        val leases = LinkedHashMap<String, String>()
        byDisplay.values.forEach { if (it.key.isNotEmpty()) leases[it.streamId] = it.key }
        starting.values.forEach { leases[it.streamId] = it.key }
        RelayControl.set(leases, onAcked)
    }

    private fun end(share: Share, reason: EndReason, error: String = "") {
        if (!byDisplay.remove(share.displayId, share)) return
        // First: the relay cuts the stream even if the sharer's capture never stops
        syncRelay()
        val display = DisplayManager.getDisplayData(share.displayId) as? PaperDisplayData
        val sharer = Bukkit.getPlayer(share.sharerId)
        // Tell the sharer first, so their capture stops before (or as) the display changes back
        if (ScreenShareServerRules.notifiesSharer(reason) && sharer != null) {
            reply(sharer, share.displayId, active = false, error = error, nonce = share.nonce)
        }
        // Only while the display still shows this share: anything else was set since and stays. The previous video
        // comes back paused where it was, at its speed, and still the queue entry it was; it never plays by itself.
        if (ScreenShareServerRules.restoresPreviousVideo(reason) && display != null && display.url.contains(share.streamId)) {
            DisplayActions.applyVideo(
                display, share.previousUrl, share.previousLang, sharer, persist = false,
                restore = share.previousPlayback ?: SavedPlayback(0L, PlaybackSpeed.NORMAL),
            )
            QueueManager.resumePlayingEntry(display, share.previousEntry)
            // A Local display has no clock to come back paused on: its viewers' players are told to stay paused
            if (display.mode == PlaybackMode.LOCAL && share.previousUrl.isNotBlank()) {
                PaperPlaybackTransport.broadcast(display, RemotePlaybackToggle(display.id, paused = true))
            }
            // Viewers' clients closed their fullscreen of this display when the share's picture went away: open it again
            FullscreenBroadcastManager.redeliver(display.id)
            // Entries queued while the share ran on an empty display waited for an end the live stream never had
            if (reason != EndReason.PLUGIN_DISABLE && share.previousUrl.isBlank()) QueueManager.startFromEmpty(display)
        }
        logger.info("Screen share to display {} ended ({}).", share.displayId, reason.name.lowercase(Locale.ROOT))
    }

    private fun reply(
        player: Player, displayId: UUID, active: Boolean, publishUrl: String = "", error: String = "", nonce: Long = 0L,
    ) {
        val live = PaperServer.config.settings.live
        PaperV2Networking.send(
            [player],
            ScreenShareState(
                displayId, active, publishUrl, error, live.share_encoder, live.share_max_fps, live.share_max_height,
                live.share_bitrate_kbps,
            ).copy(nonce = nonce, noSound = !live.share_sound),
        )
    }

    private fun isAdmin(player: Player) = player.hasPermission(PaperServer.config.permissions.deleteOthers)

    /** 24 random bytes, URL-safe: this share's relay publish key (only its sharer is ever sent it). */
    private fun newKey(): String {
        val bytes = ByteArray(24).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * 20 random characters: the stream path. Without a lease service it is the only secret guarding the relay's
     * publish side; with one, it is what lets viewers read while the share runs.
     */
    private fun newStreamId(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return "dd" + (1..20).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}
