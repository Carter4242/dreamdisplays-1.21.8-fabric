package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.core.karaoke.KaraokeAction
import com.dreamdisplays.core.karaoke.KaraokeArgs
import com.dreamdisplays.core.karaoke.KaraokeTiming
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.shortLabel
import com.dreamdisplays.platform.server.karaoke.KaraokeManager
import com.dreamdisplays.platform.server.karaoke.KaraokeManager.LineResult
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.*

/**
 * `/display karaoke <display> ...`: `on|off`, `status`, `trim <ms>`, `singer add <player> [lead_ms]`,
 * `singer remove <player>`, `singer trim <player> <ms>`, and the singer line: `join <url>`, `leave`, `line`,
 * `line add <player> <url>`, `line remove <player>`, `line clear`. Join, leave and viewing the line need
 * `permissions.karaoke_join`, the rest `permissions.karaoke` (both operator-only by default).
 */
@PaperOnly
object KaraokeCommand {
    private val legacy = LegacyComponentSerializer.legacyAmpersand()

    /** True when [sender] may use any karaoke command. */
    fun allowed(sender: CommandSender): Boolean = operator(sender) || joiner(sender)

    fun operator(sender: CommandSender): Boolean = sender.hasPermission(PaperServer.config.permissions.karaoke)

    fun joiner(sender: CommandSender): Boolean = sender.hasPermission(PaperServer.config.permissions.karaokeJoin)

    /** Runs the karaoke command for the display [token] names with the words after it. */
    fun execute(sender: CommandSender, token: String, args: List<String>) {
        if (!KaraokeManager.available) return say(sender, "karaokeNoVoice")
        val action = KaraokeArgs.parse(args)
        if (action is KaraokeAction.Invalid) {
            return say(
                sender, when (action.reason) {
                    KaraokeAction.Reason.BAD_LEAD -> "karaokeBadLead"
                    KaraokeAction.Reason.BAD_TRIM -> "karaokeBadTrim"
                    KaraokeAction.Reason.USAGE -> "karaokeUsage"
                }
            )
        }
        if (if (action.operatorOnly) !operator(sender) else !allowed(sender)) {
            return ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.sendTo(sender)
        }
        val display = resolve(sender, token) ?: return
        if (!KaraokeManager.runsOn(display)) return say(sender, "karaokeNotTimeline")
        val label = display.shortLabel
        when (action) {
            is KaraokeAction.Toggle -> {
                KaraokeManager.setEnabled(display, action.on)
                say(sender, if (action.on) "karaokeOn" else "karaokeOff", label)
            }

            KaraokeAction.Status -> status(sender, display)
            is KaraokeAction.Trim -> {
                KaraokeManager.setTrim(display, action.ms)
                say(sender, "karaokeTrimSet", label, action.ms.toString())
            }

            is KaraokeAction.SingerAdd -> {
                if (!KaraokeManager.isEnabled(display)) return say(sender, "karaokeIsOff", label)
                val player = onlinePlayer(action.player) ?: return say(sender, "karaokeNoPlayer", action.player)
                val lead = KaraokeManager.addSinger(display, player.uniqueId, player.name, action.leadMs)
                say(sender, "karaokeSingerAdded", player.name, label, lead.toString())
            }

            is KaraokeAction.SingerRemove -> {
                val singer = KaraokeManager.singerNamed(display, action.player)
                    ?: return say(sender, "karaokeNotSinger", action.player, label)
                KaraokeManager.removeSinger(display, singer.playerId)
                say(sender, "karaokeSingerRemoved", singer.name, label)
            }

            is KaraokeAction.SingerTrim -> {
                val singer = KaraokeManager.singerNamed(display, action.player)
                    ?: return say(sender, "karaokeNotSinger", action.player, label)
                KaraokeManager.setSingerTrim(display, singer.playerId, action.ms)
                say(sender, "karaokeSingerTrimSet", singer.name, action.ms.toString())
            }

            is KaraokeAction.Join -> {
                val player = sender as? Player ?: return ServerMessage.COMMAND_PLAYERS_ONLY.sendTo(sender)
                join(sender, display, player.uniqueId, player.name, action.url, self = true)
            }

            KaraokeAction.Leave -> {
                val player = sender as? Player ?: return ServerMessage.COMMAND_PLAYERS_ONLY.sendTo(sender)
                when (KaraokeManager.leave(display, player.uniqueId)) {
                    LineResult.OK -> say(sender, "karaokeLeft", label)
                    else -> say(sender, "karaokeNotInLine", player.name, label)
                }
            }

            KaraokeAction.Line -> line(sender, display)
            is KaraokeAction.LineAdd -> {
                val player = onlinePlayer(action.player) ?: return say(sender, "karaokeNoPlayer", action.player)
                join(sender, display, player.uniqueId, player.name, action.url, self = false)
            }

            is KaraokeAction.LineRemove -> {
                val entry = KaraokeManager.lineEntryNamed(display, action.player)
                val singer = KaraokeManager.singerNamed(display, action.player)?.takeIf { it.itemId != null }
                val playerId = entry?.playerId ?: singer?.playerId
                    ?: return say(sender, "karaokeNotInLine", action.player, label)
                KaraokeManager.leave(display, playerId)
                say(sender, "karaokeLineRemoved", entry?.name ?: singer?.name ?: action.player, label)
            }

            KaraokeAction.LineClear -> say(sender, "karaokeLineCleared", label, KaraokeManager.clearLine(display).toString())
            is KaraokeAction.Invalid -> {}
        }
    }

    private fun join(sender: CommandSender, display: DisplayData, playerId: UUID, name: String, raw: String, self: Boolean) {
        val label = display.shortLabel
        val url = QueueCommand.queueableUrl(sender, raw) ?: return
        val outcome = KaraokeManager.join(display, playerId, name, url)
        when (outcome.result) {
            LineResult.OK -> when {
                outcome.position == 0 -> say(sender, "karaokeSingingNow", name, label)
                self -> say(sender, "karaokeJoined", label, outcome.position.toString())
                else -> say(sender, "karaokeLineAdded", name, label, outcome.position.toString())
            }

            LineResult.OFF -> say(sender, "karaokeIsOff", label)
            LineResult.ALREADY -> say(sender, "karaokeAlreadyInLine", name, label)
            LineResult.NOT_IN_LINE -> say(sender, "karaokeNotInLine", name, label)
            LineResult.QUEUE_REFUSED -> QueueCommand.report(sender, display, outcome.queueResult ?: return, "queueAdded")
        }
    }

    private fun status(sender: CommandSender, display: DisplayData) {
        val label = display.shortLabel
        val on = KaraokeManager.isEnabled(display)
        val listeners = KaraokeManager.listenersNear(display)
        say(
            sender, "karaokeStatusHeader", label,
            MessageUtil.messageFor(sender, if (on) "karaokeStateOn" else "karaokeStateOff"),
            KaraokeManager.trimOf(display).toString(), listeners.size.toString(),
            KaraokeManager.audioRadius(display).toInt().toString(),
        )
        val singers = KaraokeManager.singersOf(display)
        if (singers.isEmpty()) return say(sender, "karaokeStatusNoSingers")
        val stage = KaraokeManager.stageOf(display)
        val latency = KaraokeManager.settings().latency
        for (singer in singers) {
            val ping = stage?.singers?.get(singer.playerId)?.pingMs ?: Bukkit.getPlayer(singer.playerId)?.ping?.toLong() ?: 0L
            val trim = stage?.singers?.get(singer.playerId)?.trimMs ?: singer.trimMs
            // Listeners whose computed send delay is negative can't be reached in time at all
            val short = listeners.count {
                KaraokeTiming.emitDelayMs(singer.leadMs, ping, it.pingMs, trim, latency) < 0
            }
            val stats = KaraokeManager.voice?.stats(singer.playerId)
            val voice = if (stats == null) MessageUtil.messageFor(sender, "karaokeStatusNoVoice")
            else MessageUtil.formatIndexed(
                sender, "karaokeStatusVoice", stats.frames.toString(), stats.bursts.toString(),
                stats.sent.toString(), stats.late.toString(),
            )
            say(
                sender, "karaokeStatusSinger", singer.name, singer.leadMs.toString(), ping.toString(),
                singer.trimMs.toString(),
                if (singer.itemId != null) MessageUtil.messageFor(sender, "karaokeStatusFromLine") else "",
                voice,
            )
            val late = maxOf(short, stats?.lateListeners ?: 0)
            if (late > 0) say(sender, "karaokeStatusLate", late.toString(), (stats?.worstLateMs ?: 0L).toString())
        }
    }

    private fun line(sender: CommandSender, display: DisplayData) {
        val label = display.shortLabel
        val now = KaraokeManager.singersOf(display).filter { it.itemId != null }
        val line = KaraokeManager.lineOf(display)
        if (now.isEmpty() && line.isEmpty()) return say(sender, "karaokeLineEmpty", label)
        say(sender, "karaokeLineHeader", label, line.size.toString())
        now.forEach { say(sender, "karaokeLineNow", it.name) }
        line.forEachIndexed { i, (item, entry) ->
            // Titles come from players: plain text components, never parsed for colour codes
            MessageUtil.sendComponent(
                sender,
                Component.text()
                    .append(legacy.deserialize("&7 ${i + 1}. "))
                    .append(Component.text(entry.name, NamedTextColor.WHITE))
                    .append(Component.text(" — " + item.title.ifEmpty { item.url }, NamedTextColor.GRAY))
                    .build(),
            )
        }
    }

    /** Player names of [display]'s singers and line, for tab-completion. */
    fun knownNames(token: String): List<String> {
        val display = DisplayManager.resolveByIdOrPrefix(token) ?: return emptyList()
        return (KaraokeManager.singersOf(display).map { it.name } + KaraokeManager.lineOf(display).map { it.second.name }).distinct()
    }

    private fun onlinePlayer(nameOrId: String): Player? =
        Bukkit.getPlayerExact(nameOrId) ?: runCatching { UUID.fromString(nameOrId) }.getOrNull()?.let(Bukkit::getPlayer)

    private fun resolve(sender: CommandSender, token: String): DisplayData? =
        if (sender is Player) {
            resolvePaperDisplayTarget(sender, sender, token)
        } else {
            DisplayManager.resolveByIdOrPrefix(token) ?: run {
                ServerMessage.NO_DISPLAY.sendTo(sender)
                null
            }
        }

    private fun say(sender: CommandSender, key: String, vararg args: String) =
        MessageUtil.sendColoredMessage(sender, MessageUtil.formatIndexed(sender, key, *args))
}
