package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.core.karaoke.KaraokeAction
import com.dreamdisplays.core.karaoke.KaraokeArgs
import com.dreamdisplays.core.karaoke.KaraokeTiming
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.shortLabel
import com.dreamdisplays.platform.server.karaoke.KaraokeManager
import com.dreamdisplays.platform.server.karaoke.KaraokeMeasurements
import com.dreamdisplays.platform.server.playback.TimelineManager
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
 * `/display karaoke <display> ...`: `stop` (alias `off`), `status`, `trim <ms>`, `singer add <player> [duet] [lead_ms]` (replaces the singers unless `duet`),
 * `singer remove <player>`, `singer trim <player> <ms>`, and the singer line: `join <url>`, `leave`, `line`,
 * `line add <player> <url>`, `line remove <player>`, `line clear`. Join, leave and viewing the line need
 * `permissions.karaoke_join`, the rest `permissions.karaoke` (both operator-only by default). There is no `on`: a display
 * is in karaoke while someone sings on it.
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
            KaraokeAction.Stop -> {
                val (singers, line) = KaraokeManager.stop(display)
                say(sender, "karaokeStopped", label, singers.toString(), line.toString())
            }

            KaraokeAction.Status -> status(sender, display)
            is KaraokeAction.Trim -> {
                KaraokeManager.setTrim(display, action.ms)
                say(sender, "karaokeTrimSet", label, action.ms.toString())
            }

            is KaraokeAction.SingerAdd -> {
                val player = onlinePlayer(action.player) ?: return say(sender, "karaokeNoPlayer", action.player)
                val lead = KaraokeManager.addSinger(display, player.uniqueId, player.name, action.leadMs, duet = action.duet)
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

            LineResult.ALREADY -> say(sender, "karaokeAlreadyInLine", name, label)
            LineResult.NOT_IN_LINE -> say(sender, "karaokeNotInLine", name, label)
            LineResult.QUEUE_REFUSED -> QueueCommand.report(sender, display, outcome.queueResult ?: return, "queueAdded")
        }
    }

    /** Strictly read-only: reads the singers, the stage and what clients measured; changes nothing. */
    private fun status(sender: CommandSender, display: DisplayData) {
        val label = display.shortLabel
        val singers = KaraokeManager.singersOf(display)
        val listeners = KaraokeManager.listenersNear(display)
        val waiting = KaraokeManager.lineSize(display)
        if (singers.isEmpty()) {
            say(sender, "karaokeStatusIdle", label, waiting.toString())
        } else {
            say(
                sender, "karaokeStatusHeader", label, singers.size.toString(), waiting.toString(),
                KaraokeManager.trimOf(display).toString(), listeners.size.toString(),
                KaraokeManager.audioRadius(display).toInt().toString(),
            )
        }
        val stage = KaraokeManager.stageOf(display)
        val latency = KaraokeManager.settings().latency
        val nowNanos = System.nanoTime()
        for (singer in singers) {
            val staged = stage?.singers?.get(singer.playerId)
            val ping = staged?.pingMs ?: Bukkit.getPlayer(singer.playerId)?.ping?.toLong() ?: 0L
            val measured = KaraokeMeasurements.singerLatency(singer.playerId, nowNanos)
            val report = KaraokeMeasurements.reportOf(singer.playerId)?.report
            val auto = KaraokeMeasurements.statusOf(singer.playerId, nowNanos)
            val trim = staged?.trimMs ?: (KaraokeManager.trimOf(display) + singer.trimMs + auto.autoTrimMs)
            // Listeners whose computed send delay is negative can't be reached in time at all
            val short = listeners.count {
                KaraokeTiming.emitDelayMs(singer.leadMs, ping, measured, it.pingMs, it.latency, trim, latency) < 0
            }
            val stats = KaraokeManager.voice?.stats(singer.playerId)
            val voice = if (stats == null) MessageUtil.messageFor(sender, "karaokeStatusNoVoice")
            else MessageUtil.formatIndexed(
                sender, "karaokeStatusVoice", stats.frames.toString(), stats.bursts.toString(),
                stats.sent.toString(), stats.late.toString(),
            )
            val autoInfo = if (auto.reports == 0 && auto.lastOffsetMs == null) {
                MessageUtil.messageFor(sender, "karaokeStatusAutoNone")
            } else MessageUtil.formatIndexed(
                sender, "karaokeStatusAuto", "%.2f".format(auto.confidence), auto.reports.toString(),
                auto.lastOffsetMs?.let { signed(Math.round(it)) } ?: "-",
            )
            say(
                sender, "karaokeStatusSinger", singer.name,
                if (singer.itemId != null) MessageUtil.messageFor(sender, "karaokeStatusFromLine") else "",
                singer.leadMs.toString(), KaraokeManager.autoLeadMs(display, singer.playerId).toString(),
                TimelineManager.lastSentLeadMs(display.id, singer.playerId)?.toString() ?: "-",
                signed(KaraokeManager.trimOf(display) + singer.trimMs), signed(auto.autoTrimMs), autoInfo, voice,
            )
            val output = measured.outputMs?.let {
                "$it ms" + if (report?.trackOutputMeasured == true) "" else " " + word(sender, "karaokeStatusEstimated")
            } ?: "-"
            val capture = measured.captureMs?.let { "$it ms" } ?: "-"
            val fallback = if (measured.outputMs != null && measured.captureMs != null) "" else
                MessageUtil.formatIndexed(sender, "karaokeStatusDefault", latency.singerOutputCaptureMs.toString())
            val network = measured.uplinkMs?.let {
                MessageUtil.formatIndexed(
                    sender, "karaokeStatusClockSynced", it.toString(),
                    auto.clockRttMs?.let { rtt -> Math.round(rtt / 2).toString() } ?: "?", auto.uplinkSamples.toString(),
                )
            } ?: MessageUtil.formatIndexed(sender, "karaokeStatusHalfPing", (ping / 2).toString())
            say(sender, "karaokeStatusMeasured", output, capture, fallback, network, ping.toString())
            val late = maxOf(short, stats?.lateListeners ?: 0)
            if (late > 0) say(sender, "karaokeStatusLate", late.toString(), (stats?.worstLateMs ?: 0L).toString())
        }
        if (listeners.isNotEmpty()) {
            val reported = listeners.mapNotNull { it.latency }
            say(
                sender, "karaokeStatusListeners", reported.size.toString(), listeners.size.toString(),
                summary(reported.mapNotNull { it.voiceBufferMs }), summary(reported.mapNotNull { it.voiceOutputMs }),
                summary(reported.mapNotNull { it.trackOutputMs }),
                listeners.maxOf { KaraokeTiming.listenerSideMs(it.pingMs, it.latency, latency) }.toString(),
            )
        }
    }

    /** "median ms (min-max)" of [values], or "-". */
    private fun summary(values: List<Long>): String {
        if (values.isEmpty()) return "-"
        val sorted = values.sorted()
        val median = sorted[sorted.size / 2]
        return if (sorted.first() == sorted.last()) "$median ms" else "$median ms (${sorted.first()}-${sorted.last()})"
    }

    private fun signed(ms: Long): String = if (ms > 0) "+$ms" else ms.toString()

    private fun word(sender: CommandSender, key: String): String = MessageUtil.messageFor(sender, key)

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
