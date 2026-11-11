package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.core.display.viewer.ViewerEntry
import com.dreamdisplays.core.display.viewer.ViewerSummary
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.shortLabel
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.policy.ClientDiagnostics
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Locale

/**
 * `/display stats <display>`: what every viewer of a display last reported (sync drift, rate, latency compensation,
 * corrections, stalls, errors, quality); `/display stats` adds a one-line summary per reported display. Same
 * permission as `/display policy`; output only goes to the sender.
 */
@PaperOnly
object ViewerStatsCommand {
    private val actions = listOf("none", "rate", "snap", "seek")

    fun execute(sender: CommandSender, token: String) {
        val display = resolve(sender, token) ?: return
        val entries = ClientDiagnostics.viewers(display.id)
        if (entries.isEmpty()) {
            ServerMessage.VIEWER_STATS_NONE.sendTo(sender, display.shortLabel)
            return
        }
        ServerMessage.VIEWER_STATS_HEADER.sendTo(sender, display.shortLabel, entries.size.toString())
        val now = System.currentTimeMillis()
        entries.forEach { line(sender, it, now) }
        summary(sender, ViewerSummary.of(entries))
    }

    /** One summary line per display that has viewer reports. */
    fun executeAll(sender: CommandSender) {
        val displays = ClientDiagnostics.reportedDisplays().mapNotNull { DisplayManager.getDisplayData(it) }
        ServerMessage.VIEWER_STATS_ALL_HEADER.sendTo(sender, displays.size.toString())
        displays.sortedBy { it.shortLabel }.forEach { display ->
            val s = ViewerSummary.of(ClientDiagnostics.viewers(display.id))
            ServerMessage.VIEWER_STATS_ALL_LINE.sendTo(
                sender, display.shortLabel, s.viewers.toString(), ms(s.maxAbsDriftMs), ms(s.spreadMs), s.erroring.toString(),
            )
        }
    }

    private fun line(sender: CommandSender, e: ViewerEntry, now: Long) {
        val r = e.report
        val age = (e.ageMs(now) / 1_000L).toString()
        if (e.erroring) {
            ServerMessage.VIEWER_STATS_ERROR.sendTo(
                sender, withBuild(e), r.errorKind.ifEmpty { "ERROR" }, r.errorMessage.ifEmpty { "-" }, age,
            )
            return
        }
        ServerMessage.VIEWER_STATS_LINE.sendTo(
            sender,
            withBuild(e),
            e.state.label,
            if (r.syncing) signedMs(r.driftMs) else "-",
            if (r.syncing) String.format(Locale.ROOT, "%.4f", r.rate) else "-",
            "${r.latencyCompMs}ms",
            actions.getOrElse(r.action) { "?" },
            r.seeks.toString(),
            r.stalls.toString(),
            if (r.qualityHeight > 0) "${r.qualityHeight}p" else "auto",
            clock(r.positionMs),
            age,
        )
    }

    /** The viewer's name with its client build (`Steve [1a2b3c4d]`, `[older]` for jars without one). */
    private fun withBuild(e: ViewerEntry): String =
        ClientsCommand.buildLabel(e.playerId)?.let { "${e.playerName} [$it]" } ?: e.playerName

    private fun summary(sender: CommandSender, s: ViewerSummary) {
        ServerMessage.VIEWER_STATS_SUMMARY.sendTo(
            sender, s.viewers.toString(), s.syncing.toString(), ms(s.maxAbsDriftMs), ms(s.spreadMs),
            s.erroring.toString(), s.buffering.toString(),
        )
    }

    private fun resolve(sender: CommandSender, token: String): DisplayData? {
        if (sender is Player) return resolvePaperDisplayTarget(sender, sender, token)
        return DisplayManager.resolveByIdOrPrefix(token) ?: run {
            ServerMessage.NO_DISPLAY.sendTo(sender)
            null
        }
    }

    private fun ms(value: Int?): String = value?.let { "${it}ms" } ?: "-"

    private fun signedMs(value: Int): String = if (value >= 0) "+${value}ms" else "${value}ms"

    private fun clock(positionMs: Long): String {
        val total = positionMs / 1_000L
        val h = total / 3_600L
        val m = (total / 60L) % 60L
        val s = total % 60L
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}
