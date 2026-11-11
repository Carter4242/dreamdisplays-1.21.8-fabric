package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.core.queue.DisplayQueue
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.datatypes.display.shortLabel
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.playback.PlaybackContexts
import com.dreamdisplays.platform.server.playback.QueueManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.net.CustomMediaGate
import com.dreamdisplays.platform.server.utils.sendTo
import com.dreamdisplays.shared.playback.PlaybackContext
import com.dreamdisplays.shared.policy.MediaUrlPolicy
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/display queue <display> [list | add <url> | remove <n> | move <n> <pos> | skip | clear | loop on|off]` for
 * operators (`permissions.queue`). Acts with admin rights on the display; positions are 1-based as listed.
 */
@PaperOnly
object QueueCommand {
    private val legacy = LegacyComponentSerializer.legacyAmpersand()

    /** Runs [action] (null = list) on the display [token] names, with its remaining [args]. */
    fun execute(sender: CommandSender, token: String, action: String?, args: List<String>) {
        val display = resolve(sender, token) ?: return
        val ctx = adminContext(display)
        when (action?.lowercase()) {
            null, "list" -> list(sender, display)
            "add" -> add(sender, display, ctx, args.firstOrNull().orEmpty())
            "remove" -> withItem(sender, display, args.getOrNull(0)) { itemId ->
                report(sender, display, QueueManager.remove(display, ctx, actorOf(sender, display), itemId), "queueRemoved")
            }

            "move" -> withItem(sender, display, args.getOrNull(0)) { itemId ->
                val to = args.getOrNull(1)?.toIntOrNull() ?: return@withItem MessageUtil.sendMessage(sender, "queueBadIndex")
                report(sender, display, QueueManager.move(display, ctx, actorOf(sender, display), itemId, to - 1), "queueMoved")
            }

            "skip" -> report(sender, display, QueueManager.skip(display, ctx, actorOf(sender, display)), "queueSkipped")
            "clear" -> report(sender, display, QueueManager.clear(display, ctx), "queueCleared")
            "loop" -> {
                val on = when (args.firstOrNull()?.lowercase()) {
                    "on", "true" -> true
                    "off", "false" -> false
                    else -> return MessageUtil.sendMessage(sender, "displayWrongCommand")
                }
                report(sender, display, QueueManager.setLoop(display, ctx, on), if (on) "queueLoopOn" else "queueLoopOff")
            }

            else -> MessageUtil.sendMessage(sender, "displayWrongCommand")
        }
    }

    /** Actions for tab-completion. */
    val actions: List<String> = listOf("list", "add", "remove", "move", "skip", "clear", "loop")

    /** 1-based positions of [token]'s queue for tab-completion. */
    fun positions(token: String): List<String> {
        val display = DisplayManager.resolveByIdOrPrefix(token) ?: return emptyList()
        val size = QueueManager.snapshotOf(display.id)?.size ?: 0
        return (1..size).map { it.toString() }
    }

    private fun resolve(sender: CommandSender, token: String): PaperDisplayData? {
        val display = if (sender is Player) {
            resolvePaperDisplayTarget(sender, sender, token)
        } else {
            DisplayManager.resolveByIdOrPrefix(token) ?: run {
                ServerMessage.NO_DISPLAY.sendTo(sender)
                null
            }
        }
        return display as? PaperDisplayData
    }

    private fun adminContext(display: DisplayData): PlaybackContext {
        val base = PlaybackContexts.of(display, display.ownerId, isAdmin = true)
        return base.copy(isOwner = false, isAdmin = true, isLocked = false)
    }

    private fun actorOf(sender: CommandSender, display: DisplayData) = (sender as? Player)?.uniqueId ?: display.ownerId

    /** [raw] as a link that may be queued, or null after telling [sender] why not. */
    internal fun queueableUrl(sender: CommandSender, raw: String): String? {
        val url = canonical(raw) ?: run {
            ServerMessage.INVALID_URL.sendTo(sender)
            return null
        }
        CustomMediaGate.refusalKey(
            url,
            PaperServer.config.settings.customMediaPolicy,
            hasPermission = true,
        )?.let {
            MessageUtil.sendMessage(sender, it)
            return null
        }
        return url
    }

    private fun add(sender: CommandSender, display: PaperDisplayData, ctx: PlaybackContext, raw: String) {
        val url = queueableUrl(sender, raw) ?: return
        val result = QueueManager.add(
            display, ctx, actorOf(sender, display), sender.name,
            url, "", "", 0L, "", throttled = false,
        )
        report(sender, display, result, "queueAdded")
    }

    private fun withItem(sender: CommandSender, display: DisplayData, position: String?, block: (String) -> Unit) {
        val index = position?.toIntOrNull()?.minus(1)
        val item = index?.let { QueueManager.snapshotOf(display.id)?.items?.getOrNull(it) }
            ?: return MessageUtil.sendMessage(sender, "queueBadIndex")
        block(item.itemId)
    }

    internal fun report(sender: CommandSender, display: DisplayData, result: QueueManager.Result, okKey: String) {
        val key = when (result) {
            QueueManager.Result.OK -> okKey
            QueueManager.Result.DISABLED -> "queueDisabled"
            QueueManager.Result.NOT_RUNNING -> "queueNotRunning"
            QueueManager.Result.FULL -> "queueFull"
            QueueManager.Result.EMPTY -> "queueEmpty"
            QueueManager.Result.NOT_FOUND -> "queueBadIndex"
            QueueManager.Result.NOT_ALLOWED -> ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.key
            QueueManager.Result.PLAYER_LIMIT, QueueManager.Result.THROTTLED, QueueManager.Result.REFUSED -> "queueRefused"
        }
        MessageUtil.sendColoredMessage(sender, MessageUtil.formatIndexed(sender, key, display.shortLabel))
    }

    private fun list(sender: CommandSender, display: DisplayData) {
        val queue = QueueManager.snapshotOf(display.id) ?: DisplayQueue()
        if (queue.isEmpty()) {
            MessageUtil.sendColoredMessage(sender, MessageUtil.formatIndexed(sender, "queueEmpty", display.shortLabel))
            return
        }
        val loop = if (queue.loop) MessageUtil.messageFor(sender, "queueLoopTag") else ""
        MessageUtil.sendColoredMessage(
            sender,
            MessageUtil.formatIndexed(sender, "queueHeader", display.shortLabel, queue.size.toString(), loop),
        )
        queue.items.forEachIndexed { i, item ->
            // Titles come from players: plain text components, never parsed for colour codes
            val line = Component.text()
                .append(legacy.deserialize("&7 ${i + 1}. "))
                .append(Component.text(item.title.ifEmpty { item.url }, NamedTextColor.WHITE))
                .append(Component.text(" — ${item.addedByName}" + duration(item.durationMs), NamedTextColor.GRAY))
                .build()
            MessageUtil.sendComponent(sender, line)
        }
    }

    private fun duration(ms: Long): String {
        if (ms <= 0) return ""
        val s = ms / 1_000
        return if (s >= 3_600) " (%d:%02d:%02d)".format(s / 3_600, s % 3_600 / 60, s % 60)
        else " (%d:%02d)".format(s / 60, s % 60)
    }

    private fun canonical(raw: String): String? {
        val input = raw.trim()
        if (input.isEmpty()) return null
        val resolvable = MediaSource.from(input).toResolvableUrl() ?: return null
        if (!resolvable.contains("://")) return null
        return resolvable.takeIf { MediaUrlPolicy.isAllowed(it) }
    }
}
