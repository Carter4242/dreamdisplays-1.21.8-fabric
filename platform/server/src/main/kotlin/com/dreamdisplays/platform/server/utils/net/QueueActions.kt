package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.core.protocol.common.packets.QueueAction
import com.dreamdisplays.core.protocol.common.packets.QueueActionType
import com.dreamdisplays.core.protocol.common.packets.QueuePreload
import com.dreamdisplays.core.queue.QueueActionLimits
import com.dreamdisplays.core.queue.QueueAddGate
import com.dreamdisplays.core.queue.QueueItem
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.api.ServerApiHost
import com.dreamdisplays.platform.server.commands.subcommands.QueueCommand
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.meta.Scheduler.runAsync
import com.dreamdisplays.platform.server.playback.PlaybackContexts
import com.dreamdisplays.platform.server.playback.QueueManager
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.WorldGuardRegions
import com.dreamdisplays.shared.playback.FullscreenPrepareState
import com.dreamdisplays.shared.playback.PlaybackContext
import com.dreamdisplays.shared.policy.MediaUrlPolicy
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import java.util.UUID

/**
 * `Paper` side of the "Up next" queue: client queue packets (checked like [DisplayActions.setVideo]: display rules,
 * URL policy, custom-link policy, proximity) and the video switch [QueueManager] performs when a display advances.
 * Every queue packet first passes a per-player rate limit, and an add is checked against the display's rules before
 * any of its URLs is validated, so a modified client flooding them costs the server next to nothing.
 */
@PaperOnly
@NullMarked
object QueueActions {
    private val limits = QueueActionLimits()

    /** The `[queue]` config as [QueueManager] wants it. */
    fun settings(): QueueManager.Settings = PaperServer.config.settings.queue.let {
        QueueManager.Settings(it.enabled, it.max_items, it.max_per_player, it.gap_ms.toLong())
    }

    /** Switches [display] to queue entry [item] exactly like a player-set video; [readyPlayers] preloaded it. */
    fun applyVideo(display: DisplayData, item: QueueItem, readyPlayers: Collection<UUID>, startAtMs: Long? = null): Boolean {
        val paper = display as? PaperDisplayData ?: return false
        if (ServerApiHost.current?.askSetUrl(paper, item.url, item.lang, item.addedBy) != null) return false
        paper.url = item.url
        paper.lang = item.lang
        runAsync { PaperServer.getInstance().storage.saveDisplay(paper) }
        DisplayManager.broadcastUpdate(paper)
        TimelineManager.onVideoChanged(paper, readyPlayers, startAtMs)
        ServerApiHost.current?.urlChanged(paper)
        return true
    }

    /** A queue edit from [player]'s menu. */
    fun onAction(player: Player, packet: QueueAction) {
        val display = DisplayManager.getDisplayData(packet.id) as? PaperDisplayData ?: return
        val action = QueueActionType.fromWire(packet.action) ?: return
        if (!limits.tryAction(player.uniqueId, action, System.currentTimeMillis())) {
            if (action == QueueActionType.ADD || action == QueueActionType.ADD_BATCH) {
                reportRefusal(player, display, QueueManager.Result.THROTTLED)
            }
            return
        }
        if (!DisplayManager.isPlayerInRange(player, display)) return
        val ctx = context(display, player)
        when (action) {
            QueueActionType.ADD -> add(player, display, ctx, packet)
            QueueActionType.REMOVE -> QueueManager.remove(display, ctx, player.uniqueId, packet.itemId)
            QueueActionType.MOVE -> QueueManager.move(display, ctx, player.uniqueId, packet.itemId, packet.toIndex)
            QueueActionType.SKIP -> {
                // Only a skip that happened is logged: a refused one (not allowed, empty queue, throttled) costs no log
                // write, so a client in range can't flood the log. The URL is read first: the skip replaces it.
                val skipped = display.url
                if (QueueManager.skip(display, ctx, player.uniqueId) == QueueManager.Result.OK) {
                    com.dreamdisplays.platform.server.policy.ClientErrorReports.logCommand(
                        player, display.id, "queue_skip", null, mapOf("mode" to display.mode.name.lowercase(), "url" to skipped),
                    )
                }
            }
            QueueActionType.CLEAR -> QueueManager.clear(display, ctx, player.uniqueId)
            QueueActionType.LOOP -> QueueManager.setLoop(display, ctx, packet.flag, player.uniqueId)
            QueueActionType.ADD_BATCH -> addBatch(player, display, ctx, packet)
        }
    }

    /** A client's preload progress for a display's next entry. */
    fun onPreload(player: Player, packet: QueuePreload) {
        val display = DisplayManager.getDisplayData(packet.id) ?: return
        val state = FullscreenPrepareState.fromWire(packet.state)
        QueueManager.onPreload(display, player.uniqueId, packet.itemId, state)
    }

    private fun add(player: Player, display: PaperDisplayData, ctx: PlaybackContext, packet: QueueAction) {
        // Before the URL is looked at: one that could not go in anyway costs no validation (or custom-link quota)
        QueueManager.addRefusal(display, ctx)?.let { return reportRefusal(player, display, it) }
        val url = packet.url.trim()
        if (url.isEmpty() || !MediaUrlPolicy.isAllowed(url)) return
        CustomMediaGate.refusalKey(
            url,
            PaperServer.config.settings.customMediaPolicy,
            player.hasPermission(PaperServer.config.permissions.custom),
            player.uniqueId,
        )?.let { key -> if (mayTell(player)) MessageUtil.sendMessage(player, key); return }
        val result = QueueManager.add(
            display, ctx, player.uniqueId, player.name,
            url, MediaUrlPolicy.sanitizeLang(packet.lang), packet.title, packet.durationMs, packet.thumbnailUrl,
        )
        reportRefusal(player, display, result)
    }

    /**
     * A menu add the server turned down is never silent: the player is told why (full, limit, too fast, ...), at most
     * once every few seconds, so a flood of refused packets is not a flood of chat lines.
     */
    private fun reportRefusal(player: Player, display: PaperDisplayData, result: QueueManager.Result) {
        if (result != QueueManager.Result.OK && mayTell(player)) QueueCommand.report(player, display, result, "queueAdded")
    }

    private fun mayTell(player: Player): Boolean = limits.tryRefusalMessage(player.uniqueId, System.currentTimeMillis())

    /**
     * A playlist add. Everything that can refuse it without looking at its URLs runs first (the display's rules, the
     * add cooldown), and only as many entries as can still go in are validated ([QueueAddGate]): validating a full
     * batch of URLs is what made a flood of them expensive.
     */
    private fun addBatch(player: Player, display: PaperDisplayData, ctx: PlaybackContext, packet: QueueAction) {
        QueueManager.addRefusal(display, ctx)?.let { return reportRefusal(player, display, it) }
        if (!QueueManager.tryAcquireAdd(player.uniqueId)) return reportRefusal(player, display, QueueManager.Result.THROTTLED)
        val candidates = QueueAddGate.toValidate(packet.items, null, QueueManager.addRoom(display, ctx, player.uniqueId))
        val policy = PaperServer.config.settings.customMediaPolicy
        val mayUseCustom = player.hasPermission(PaperServer.config.permissions.custom)
        val entries = candidates.mapNotNull { item ->
            val url = item.url.trim()
            if (url.isEmpty() || !MediaUrlPolicy.isAllowed(url)) return@mapNotNull null
            if (CustomMediaGate.refusalKey(url, policy, mayUseCustom) != null) return@mapNotNull null
            QueueManager.NewEntry(url, MediaUrlPolicy.sanitizeLang(item.lang), item.title, item.durationMs, item.thumbnailUrl)
        }
        val outcome = QueueManager.addAll(display, ctx, player.uniqueId, player.name, entries, throttled = false)
        reportRefusal(player, display, outcome.result)
    }

    /** Drops [playerId]'s rate-limit state; call when they leave. */
    fun onQuit(playerId: UUID) = limits.forget(playerId)

    /** The permission context for [player] on [display], admins being the `delete_others` holders. */
    fun context(display: PaperDisplayData, player: Player) =
        PlaybackContexts.of(display, player.uniqueId, player.hasPermission(PaperServer.config.permissions.deleteOthers)) {
            WorldGuardRegions.isRegionMember(player, display.pos1)
        }
}
