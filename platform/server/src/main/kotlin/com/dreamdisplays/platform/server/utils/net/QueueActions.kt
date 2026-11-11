package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.core.protocol.common.packets.QueueAction
import com.dreamdisplays.core.protocol.common.packets.QueueActionType
import com.dreamdisplays.core.protocol.common.packets.QueuePreload
import com.dreamdisplays.core.queue.QueueItem
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.api.ServerApiHost
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
import com.dreamdisplays.shared.policy.MediaUrlPolicy
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import java.util.UUID

/**
 * `Paper` side of the "Up next" queue: client queue packets (checked like [DisplayActions.setVideo]: display rules,
 * URL policy, custom-link policy, proximity) and the video switch [QueueManager] performs when a display advances.
 */
@PaperOnly
@NullMarked
object QueueActions {
    /** The `[queue]` config as [QueueManager] wants it. */
    fun settings(): QueueManager.Settings = PaperServer.config.settings.queue.let {
        QueueManager.Settings(it.enabled, it.max_items, it.max_per_player)
    }

    /** Switches [display] to queue entry [item] exactly like a player-set video; [readyPlayers] preloaded it. */
    fun applyVideo(display: DisplayData, item: QueueItem, readyPlayers: Collection<UUID>): Boolean {
        val paper = display as? PaperDisplayData ?: return false
        if (ServerApiHost.current?.askSetUrl(paper, item.url, item.lang, item.addedBy) != null) return false
        paper.url = item.url
        paper.lang = item.lang
        runAsync { PaperServer.getInstance().storage.saveDisplay(paper) }
        DisplayManager.broadcastUpdate(paper)
        TimelineManager.onVideoChanged(paper, readyPlayers)
        ServerApiHost.current?.urlChanged(paper)
        return true
    }

    /** A queue edit from [player]'s menu. */
    fun onAction(player: Player, packet: QueueAction) {
        val display = DisplayManager.getDisplayData(packet.id) as? PaperDisplayData ?: return
        val action = QueueActionType.fromWire(packet.action) ?: return
        if (!DisplayManager.isPlayerInRange(player, display)) return
        val ctx = context(display, player)
        when (action) {
            QueueActionType.ADD -> add(player, display, packet)
            QueueActionType.REMOVE -> QueueManager.remove(display, ctx, player.uniqueId, packet.itemId)
            QueueActionType.MOVE -> QueueManager.move(display, ctx, player.uniqueId, packet.itemId, packet.toIndex)
            QueueActionType.SKIP -> QueueManager.skip(display, ctx, player.uniqueId)
            QueueActionType.CLEAR -> QueueManager.clear(display, ctx)
            QueueActionType.LOOP -> QueueManager.setLoop(display, ctx, packet.flag)
            QueueActionType.ADD_BATCH -> addBatch(player, display, packet)
        }
    }

    /** A client's preload progress for a display's next entry. */
    fun onPreload(player: Player, packet: QueuePreload) {
        val display = DisplayManager.getDisplayData(packet.id) ?: return
        val state = FullscreenPrepareState.fromWire(packet.state)
        QueueManager.onPreload(display, player.uniqueId, packet.itemId, state)
    }

    private fun add(player: Player, display: PaperDisplayData, packet: QueueAction) {
        val url = packet.url.trim()
        if (url.isEmpty() || !MediaUrlPolicy.isAllowed(url)) return
        CustomMediaGate.refusalKey(
            url,
            PaperServer.config.settings.customMediaPolicy,
            player.hasPermission(PaperServer.config.permissions.custom),
            player.uniqueId,
        )?.let { return MessageUtil.sendMessage(player, it) }
        QueueManager.add(
            display, context(display, player), player.uniqueId, player.name,
            url, MediaUrlPolicy.sanitizeLang(packet.lang), packet.title, packet.durationMs, packet.thumbnailUrl,
        )
    }

    private fun addBatch(player: Player, display: PaperDisplayData, packet: QueueAction) {
        val entries = packet.items.take(QueueManager.MAX_BATCH).mapNotNull { item ->
            val url = item.url.trim()
            if (url.isEmpty() || !MediaUrlPolicy.isAllowed(url)) return@mapNotNull null
            val refused = CustomMediaGate.refusalKey(
                url,
                PaperServer.config.settings.customMediaPolicy,
                player.hasPermission(PaperServer.config.permissions.custom),
            )
            if (refused != null) return@mapNotNull null
            QueueManager.NewEntry(url, MediaUrlPolicy.sanitizeLang(item.lang), item.title, item.durationMs, item.thumbnailUrl)
        }
        QueueManager.addAll(display, context(display, player), player.uniqueId, player.name, entries)
    }

    /** The permission context for [player] on [display], admins being the `delete_others` holders. */
    fun context(display: PaperDisplayData, player: Player) =
        PlaybackContexts.of(display, player.uniqueId, player.hasPermission(PaperServer.config.permissions.deleteOthers)) {
            WorldGuardRegions.isRegionMember(player, display.pos1)
        }
}
