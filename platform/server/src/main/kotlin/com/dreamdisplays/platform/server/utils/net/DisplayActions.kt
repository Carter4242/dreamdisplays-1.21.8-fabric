package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.platform.server.karaoke.KaraokeManager
import com.dreamdisplays.core.sync.SavedPlayback
import com.dreamdisplays.platform.server.policy.ClientErrorReports
import com.dreamdisplays.platform.server.policy.DisplayPolicies
import com.dreamdisplays.core.protocol.common.ClientBuild
import com.dreamdisplays.core.protocol.common.packets.MediaGen
import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.shared.playback.PlaybackPermissions
import com.dreamdisplays.api.playback.model.WatchPartyAction
import com.dreamdisplays.shared.policy.MediaUrlPolicy
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.api.ServerApiHost
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.ActionThrottle
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.meta.Scheduler.runAsync
import com.dreamdisplays.platform.server.meta.VersionState
import com.dreamdisplays.platform.server.playback.PlaybackContexts
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplays.platform.server.playback.QueueManager
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.playback.WatchPartyManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import com.dreamdisplays.platform.server.utils.VersionUtil
import com.dreamdisplays.platform.server.utils.WorldGuardRegions
import com.dreamdisplays.platform.server.utils.net.DisplayActions.context
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.kyori.adventure.text.TextReplacementConfig
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import org.semver4j.Semver
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Server-side actions triggered by client packets. The v2 [PaperV2Networking] dispatches here,
 * so permission checks and business logic exist exactly once.
 */
@PaperOnly
@NullMarked
object DisplayActions {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val setVideoThrottle = ActionThrottle()
    private const val SET_VIDEO_COOLDOWN_MS = 250L

    private fun String.sanitizedForLog(maxLength: Int = 200): String = take(maxLength).filter { !it.isISOControl() }

    private val requestSyncThrottle = ActionThrottle()
    private const val REQUEST_SYNC_COOLDOWN_MS = 250L

    /** Handles a client-requested deletion, enforcing owner-or-permission check and physical proximity. */
    fun delete(player: Player, displayId: UUID) {
        val displayData = DisplayManager.getDisplayData(displayId)
            ?: return ServerMessage.NO_DISPLAY.sendTo(player)

        val isOwner = displayData.ownerId == player.uniqueId
        val canDelete = (isOwner && player.hasPermission(PaperServer.config.permissions.delete)) ||
                player.hasPermission(PaperServer.config.permissions.deleteOthers)
        if (!canDelete) {
            ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.sendTo(player)
            return
        }
        if (displayData !is PaperDisplayData || !DisplayManager.isPlayerInRange(player, displayData)) {
            return ServerMessage.NO_DISPLAY.sendTo(player)
        }

        ServerApiHost.current?.askDelete(displayData, player.uniqueId)?.let { reason ->
            if (reason.isNotEmpty()) MessageUtil.sendColoredMessage(player, reason)
            return
        }

        DisplayManager.delete(displayId)
        ServerMessage.DISPLAY_DELETED.sendTo(player)
    }

    /** Applies a client-supplied URL / language to a display, broadcasting and resetting the timeline. */
    fun setVideo(player: Player, displayId: UUID, url: String, lang: String) {
        val displayData = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        if (!PlaybackPermissions.canSetVideo(context(displayData, player))) return
        if (!MediaUrlPolicy.isAllowed(url)) return
        // Custom links go through the server's own policy on top of the URL-shape check: a player
        // is told why the link was refused rather than watching the display silently not change.
        CustomMediaGate.refusalKey(
            url,
            PaperServer.config.settings.customMediaPolicy,
            player.hasPermission(PaperServer.config.permissions.custom),
            player.uniqueId,
        )?.let { return MessageUtil.sendMessage(player, it) }
        // Checked before the throttle below: an attacker who is never nearby must not be able to
        // burn the per-display cooldown window against the display's real, present owner.
        if (!DisplayManager.isPlayerInRange(player, displayData)) return
        if (!setVideoThrottle.tryAcquire(displayId, SET_VIDEO_COOLDOWN_MS)) return

        val sanitizedLang = MediaUrlPolicy.sanitizeLang(lang)
        ServerApiHost.current?.askSetUrl(displayData, url, sanitizedLang, player.uniqueId)?.let { reason ->
            if (reason.isNotEmpty()) MessageUtil.sendColoredMessage(player, reason)
            return
        }

        // A video set by hand ends any screen share on this display (the share no longer owns it)
        ScreenShareActions.onVideoSetByHand(displayId)
        applyVideo(displayData, url, sanitizedLang, player)
    }

    /**
     * A client's request to clear [displayId]'s video (`SetVideo` with `clear`): anyone allowed to set its video may,
     * from its range, like a set; an addon may veto it like one.
     */
    fun clearVideo(player: Player, displayId: UUID) {
        val displayData = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        if (!PlaybackPermissions.canSetVideo(context(displayData, player))) return
        if (!DisplayManager.isPlayerInRange(player, displayData)) return
        if (!setVideoThrottle.tryAcquire(displayId, SET_VIDEO_COOLDOWN_MS)) return
        ServerApiHost.current?.askSetUrl(displayData, "", "", player.uniqueId)?.let { reason ->
            if (reason.isNotEmpty()) MessageUtil.sendColoredMessage(player, reason)
            return
        }
        clearVideo(displayData, player)
    }

    /**
     * Takes [displayData]'s video off, with no permission checks (callers check; [actor] is logged): its URL becomes
     * empty and is saved, and everyone who gets its updates (in range, far fullscreen viewers, PiP pinners) is told. Its
     * clock and length go, a screen share there ends without bringing anything back, karaoke there stops and its
     * fullscreen sessions end. The queue
     * keeps its entries but does not start them: a skip or a play does. The command form calls this.
     */
    fun clearVideo(displayData: PaperDisplayData, actor: Player?) {
        ScreenShareActions.onVideoSetByHand(displayData.id)
        KaraokeManager.endSinging(displayData)
        val previousUrl = displayData.url
        displayData.url = ""
        displayData.lang = ""
        if (actor != null) ClientErrorReports.logCommand(
            actor, displayData.id, "clear", null,
            mapOf("mode" to displayData.mode.name.lowercase(), "previousUrl" to previousUrl),
        )
        runAsync { PaperServer.getInstance().storage.saveDisplay(displayData) }
        TimelineManager.onVideoCleared(displayData)
        // Older clients ignore the empty URL below and would play the cleared video on: they drop the display first
        DisplayManager.dropForPreClearClients(displayData)
        DisplayManager.broadcastUpdate(displayData)
        FullscreenBroadcastManager.endSessionsOf(displayData.id)
        QueueManager.onVideoSetByHand(displayData)
        ServerApiHost.current?.urlChanged(displayData)
    }

    /**
     * Points [displayData] at [url] / [lang] and tells everyone: broadcast, timeline reset, queue, addons. [persist]
     * false keeps the stored URL as it was (a screen share's relay URL must never outlive the share in storage).
     * [restore]: the video comes back paused where it was ([TimelineManager.onVideoChanged]) instead of starting.
     */
    internal fun applyVideo(
        displayData: PaperDisplayData, url: String, lang: String, actor: Player?, persist: Boolean = true,
        restore: SavedPlayback? = null,
    ) {
        val previousUrl = displayData.url
        displayData.url = url
        displayData.lang = lang
        if (actor != null) ClientErrorReports.logCommand(
            actor, displayData.id, "video", null,
            mapOf("mode" to displayData.mode.name.lowercase(), "url" to url, "previousUrl" to previousUrl),
        )

        if (persist) runAsync { PaperServer.getInstance().storage.saveDisplay(displayData) }
        DisplayManager.broadcastUpdate(displayData)
        TimelineManager.onVideoChanged(displayData, restore = restore)
        QueueManager.onVideoSetByHand(displayData)
        ServerApiHost.current?.urlChanged(displayData)
    }

    /** Sets who may use a display owned by [player] and rebroadcasts. */
    fun setAccess(player: Player, displayId: UUID, access: DisplayAccess) {
        val displayData = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        if (!player.hasPermission(PaperServer.config.permissions.lock)) return
        if (!PlaybackPermissions.canToggleLock(lockContext(displayData, player))) return
        if (!DisplayManager.isPlayerInRange(player, displayData)) return

        displayData.access = access

        runAsync { PaperServer.getInstance().storage.saveDisplay(displayData) }
        DisplayManager.broadcastUpdate(displayData)
    }

    /** A client's request to switch a display's persistent base mode (`LOCAL` / `SYNCED` / `BROADCAST`). */
    fun setMode(player: Player, displayId: UUID, mode: PlaybackMode, positionMs: Long, mediaGen: Long = 0L) {
        if (!PlaybackMode.isBaseMode(mode)) return
        val displayData = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        // A position on the video the display held before is no place to start this one
        val position = if (MediaGen.matches(mediaGen, displayData.mediaGen)) positionMs else -1L
        if (!PlaybackPermissions.canSetMode(context(displayData, player))) return
        // Local takes a display off the shared clock for everyone. The menu no longer offers a mode switch
        // (`/display mode` does it), so only an op's client may still ask for Local.
        if (mode == PlaybackMode.LOCAL && !player.isOp) {
            logger.info("Refused a switch to Local for display ${displayData.id} from ${player.name} (not op)")
            return
        }

        // Check mode-specific permissions
        if (!canAccessMode(player, mode)) {
            ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.sendTo(player)
            return
        }
        if (!DisplayManager.isPlayerInRange(player, displayData)) return

        applyMode(displayData, mode, position, player)
    }

    /**
     * Stores [mode] as the display's base mode, tells its viewers and re-anchors its clock at [positionMs]; [by] is the
     * player who asked (logged to the client error log with the previous mode).
     */
    fun applyMode(displayData: PaperDisplayData, mode: PlaybackMode, positionMs: Long, by: Player? = null) {
        val previousMode = displayData.mode
        displayData.mode = mode
        if (by != null) {
            ClientErrorReports.logCommand(
                by, displayData.id, "mode", positionMs,
                mapOf("mode" to mode.name.lowercase(), "previousMode" to previousMode.name.lowercase()),
            )
        }
        runAsync { PaperServer.getInstance().storage.saveDisplay(displayData) }
        DisplayManager.broadcastUpdate(displayData)
        TimelineManager.onModeChanged(displayData, positionMs)
    }

    /** Applies a playback intent (play / pause / seek / restart / speed) to a `SYNCED` display's server clock. */
    fun playbackCommand(
        player: Player, displayId: UUID, action: PlaybackAction, positionMs: Long, rate: Double = 1.0, mediaGen: Long = 0L,
    ) {
        val displayData = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        // Meant for the video the display held before (it changed while the command was on its way)
        if (!MediaGen.matches(mediaGen, displayData.mediaGen)) {
            logger.debug("Dropped a {} for display {}: it was meant for its previous video.", action, displayId)
            return
        }
        val before = TimelineManager.timelineOf(displayData.id)
        val atMs = System.currentTimeMillis()
        if (!TimelineManager.onCommand(displayData, player.uniqueId, action, positionMs, rate)) return
        val name = when (action) {
            PlaybackAction.PLAY -> "resume"
            PlaybackAction.PAUSE -> "pause"
            PlaybackAction.SEEK -> "seek"
            PlaybackAction.RESTART -> "restart"
            PlaybackAction.SPEED -> "speed"
        }
        // Where the shared timeline was when the command landed; a seek or speed change also says where it went
        val details = buildMap {
            put("mode", displayData.mode.name.lowercase())
            if (before != null) put("wasPaused", before.paused.toString())
            if (action == PlaybackAction.SEEK) put("seekToMs", positionMs.toString())
            if (action == PlaybackAction.SPEED) put("rate", rate.toString())
        }
        ClientErrorReports.logCommand(player, displayId, name, before?.positionAt(atMs) ?: positionMs, details)
    }

    /** Starts a watch-party session with [player] as host. */
    fun watchPartyStart(player: Player, displayId: UUID, url: String, lang: String) {
        val displayData = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        if (!player.hasPermission(PaperServer.config.permissions.watchparty)) {
            ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.sendTo(player)
            return
        }
        if (!MediaUrlPolicy.isAllowed(url)) {
            logger.warn("Rejected unsafe watch-party URL from ${player.name}: ${url.sanitizedForLog(120)}")
            return
        }
        CustomMediaGate.refusalKey(
            url,
            PaperServer.config.settings.customMediaPolicy,
            player.hasPermission(PaperServer.config.permissions.custom),
            player.uniqueId,
        )?.let { return MessageUtil.sendMessage(player, it) }
        if (!DisplayManager.isPlayerInRange(player, displayData)) return
        WatchPartyManager.start(displayData, player.uniqueId, url, MediaUrlPolicy.sanitizeLang(lang))
    }

    /** Routes a watch-party control (ready / host action) to the session manager. */
    fun watchPartyControl(player: Player, displayId: UUID, action: WatchPartyAction, positionMs: Long) {
        val displayData = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        WatchPartyManager.control(displayData, player.uniqueId, action, positionMs)
    }

    /** Replies to a client's catch-up request with the current timeline and any live session. */
    fun requestSync(player: Player, displayId: UUID, knownMediaGen: Long = 0L) {
        if (!requestSyncThrottle.tryAcquire(displayId to player.uniqueId, REQUEST_SYNC_COOLDOWN_MS)) return
        resendInfoIfStale(player, displayId, knownMediaGen)
        // A fullscreen session or preparation answers with its own timeline; the queue and party state still go out
        val fullscreen = FullscreenBroadcastManager.sendCurrentTo(displayId, player.uniqueId)
        val displayData = DisplayManager.getDisplayData(displayId) ?: return
        if (!fullscreen) TimelineManager.sendCurrent(displayData, player.uniqueId)
        WatchPartyManager.sendCurrent(displayData, player.uniqueId)
        QueueManager.sendCurrent(displayData, player.uniqueId)
    }

    /**
     * A protocol-12 client holds another description of [displayId] than the server's ([knownMediaGen] differs; 0 = it
     * holds none): send it the current one before the timeline, so it plays the right video. Only to players the
     * display is for: in range, or following it from afar (fullscreen, preparation).
     */
    private fun resendInfoIfStale(player: Player, displayId: UUID, knownMediaGen: Long) {
        if ((V2PlayerTracker.helloOf(player.uniqueId)?.protocolVersion ?: 0) < MEDIA_GEN_PROTOCOL) return
        val display = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        if (knownMediaGen == display.mediaGen) return
        when {
            DisplayManager.isPlayerInRange(player, display) -> DisplayManager.sendUpdate(display, [player])
            player.uniqueId in TimelineManager.extraReceivers(displayId) ->
                DisplayManager.sendUpdate(display, [player], forced = true)
        }
    }

    /** First protocol whose clients echo `mediaGen` and say which one they hold in `RequestSync`. */
    private const val MEDIA_GEN_PROTOCOL = 12

    /** Applies a client-reported media duration to the display's server timeline (SYNCED / BROADCAST only). */
    fun reportDuration(player: Player, displayId: UUID, durationMs: Long, url: String = "", mediaGen: Long = 0L) {
        val displayData = DisplayManager.getDisplayData(displayId) ?: return
        TimelineManager.onDurationReported(displayData, player.uniqueId, durationMs, url, mediaGen)
    }

    private fun context(display: PaperDisplayData, player: Player) =
        PlaybackContexts.of(display, player.uniqueId, isAdmin(player)) {
            WorldGuardRegions.isRegionMember(player, display.pos1)
        }

    private fun lockContext(display: PaperDisplayData, player: Player) = context(display, player)

    private fun isAdmin(player: Player) = player.hasPermission(PaperServer.config.permissions.deleteOthers)

    private fun canAccessMode(player: Player, mode: PlaybackMode): Boolean {
        val permission = when (mode) {
            PlaybackMode.LOCAL -> PaperServer.config.permissions.local
            PlaybackMode.SYNCED -> PaperServer.config.permissions.synced
            PlaybackMode.BROADCAST -> PaperServer.config.permissions.broadcast
            else -> return true
        }
        return player.hasPermission(permission)
    }

    /** Tells a v1-only client that its Dream Displays is too old for this server. */
    fun notifyOutdatedClient(player: Player) {
        logger.info("${player.name} joined with an outdated Dream Displays (protocol v1); asked to update.")
        ServerMessage.OUTDATED_CLIENT.sendTo(player)
    }

    /** Records the player's reported mod version and runs the mod / plugin update checks. */
    fun recordVersionAndCheckUpdates(player: Player, versionString: String, buildCommit: String? = null) {
        val build = ClientBuild.of(buildCommit)?.let { "build $it" } ?: "build unknown, older jar"
        logger.info("${player.name} joined with Dream Displays ${versionString.sanitizedForLog()} ($build).")
        val version = VersionUtil.parseOrNull(versionString)
        PlayerManager.setVersion(player, version)

        if (version != null) checkModUpdate(player, version)
        if (PaperServer.config.settings.updatesEnabled &&
            player.hasPermission(PaperServer.config.permissions.updates)
        ) {
            checkPluginUpdate(player)
        }
    }

    /** Streams every display in [player]'s world to them in small staggered batches. */
    fun sendAllDisplays(player: Player) {
        val displays = DisplayManager.getDisplays()
            .filterIsInstance<PaperDisplayData>()
            .filter { DisplayManager.isPlayerInRange(player, it) }
        if (displays.isEmpty()) return

        val batchSize = 5
        displays.chunked(batchSize).forEachIndexed { index, batch ->
            val delayTicks = (index * 2).toLong()
            if (delayTicks == 0L) {
                sendDisplayBatch(player, batch)
            } else {
                Scheduler.runPlayerLater(player, delayTicks) {
                    if (player.isOnline) sendDisplayBatch(player, batch)
                }
            }
        }
    }

    private fun sendDisplayBatch(player: Player, displays: List<PaperDisplayData>) {
        // The same description the periodic refresh sends (generation, schedule and all)
        displays.forEach { display -> DisplayManager.sendUpdate(display, [player]) }
    }

    private fun checkModUpdate(player: Player, userVersion: Semver) {
        val latestVersion = VersionState.modLatestVersion ?: return

        if (userVersion < latestVersion && !PlayerManager.hasBeenNotifiedAboutModUpdate(player)) {
            sendModUpdateMessage(player, latestVersion)
            PlayerManager.setModUpdateNotified(player, true)
        }
    }

    private fun checkPluginUpdate(player: Player) {
        val latestPluginVersion = VersionState.pluginLatestVersion ?: return

        if (PlayerManager.hasBeenNotifiedAboutPluginUpdate(player)) return

        @Suppress("DEPRECATION")
        val currentVersionString = PaperServer.getInstance().description.version
        if (currentVersionString.contains("-SNAPSHOT", ignoreCase = true) ||
            currentVersionString.contains("-DEV", ignoreCase = true)
        ) {
            return
        }

        val currentVersion = Semver.coerce(currentVersionString) ?: return
        val latestVersion = Semver.coerce(latestPluginVersion) ?: return

        if (currentVersion < latestVersion) {
            sendPluginUpdateMessage(player, latestPluginVersion)
            PlayerManager.setPluginUpdateNotified(player, true)
        }
    }

    private fun sendModUpdateMessage(player: Player, version: Semver) {
        val message = when (val rawMessage = PaperServer.config.getMessageForPlayer(player, "newVersion")) {
            is String -> String.format(rawMessage, version.toString())
            else -> {
                val component = MessageUtil.deserializeJsonComponent(rawMessage)

                component.replaceText(
                    TextReplacementConfig.builder()
                        .matchLiteral("%s")
                        .replacement(version.toString())
                        .build()
                )
            }
        }
        MessageUtil.sendColoredMessage(player, message)
    }

    private fun sendPluginUpdateMessage(player: Player, version: String) {
        val template = PaperServer.config.getMessageForPlayer(player, "newPluginVersion") as? String ?: return
        val message = String.format(template, version)
        MessageUtil.sendColoredMessage(player, message)
    }
}
