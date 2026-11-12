package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.core.activity.ActivityJournalFormat
import com.dreamdisplays.core.diagnostics.ReportLimits
import com.dreamdisplays.platform.server.datatypes.display.shortLabel
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.playback.ActivityJournal
import io.github.arnodoelinger.platformweaver.PaperOnly
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * `/display diag errors <display|player> commands [count|<n>h|<n>d]`: the latest lines of the permanent Activity
 * journal about a display or by a player (refused attempts in red), newest first, the whole line on hover. Reads the
 * daily files off the main thread, only the days the window covers.
 */
@PaperOnly
object ActivityJournalReader {
    const val DEFAULT_HOURS = 24
    private const val MAX_LINES = 50
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    /** `24h` / `3d` as hours, or null for any other token. */
    fun hoursOf(token: String): Int? {
        val m = Regex("""(\d{1,5})([hd])""").matchEntire(token.lowercase(Locale.ROOT)) ?: return null
        val n = m.groupValues[1].toInt()
        return (if (m.groupValues[2] == "d") n * 24 else n).coerceIn(1, 24 * 3_650)
    }

    fun show(sender: CommandSender, token: String?, count: Int, hours: Int) {
        if (token == null) {
            sender.sendMessage(text("Name a display or a player: /display diag errors <display|player> commands [count|24h|7d]", NamedTextColor.RED))
            return
        }
        val display = DisplayManager.resolveByIdOrPrefix(token) ?: DisplayManager.getDisplays().firstOrNull { it.name.equals(token, ignoreCase = true) }
        val player = if (display == null) Bukkit.getPlayerExact(token) else null
        val label = display?.let { "display ${it.shortLabel}" } ?: "player ${player?.name ?: token}"
        val displayId = display?.id
        val playerId = player?.uniqueId
        val playerName = if (display == null) player?.name ?: token else null
        val limit = count.coerceIn(1, MAX_LINES)
        val since = System.currentTimeMillis() - hours * 3_600_000L
        val task = Runnable {
            val found = read(since, limit) { ActivityJournalFormat.matches(it, displayId, playerId, playerName) }
            if (found.isEmpty()) {
                sender.sendMessage(text("Nothing in the activity log for $label in the last ${hours}h.", NamedTextColor.GRAY))
                return@Runnable
            }
            sender.sendMessage(
                text("Latest ${found.size} activity log lines for $label, last ${hours}h ", NamedTextColor.GOLD)
                    .append(text("(newest first; hover for the whole line; files in activity/)", NamedTextColor.DARK_GRAY)),
            )
            found.forEach { (obj, raw) -> sender.sendMessage(line(obj, raw)) }
        }
        // A player's chat can wait for the files; the console (and RCON) only keeps what the command prints at once
        if (sender is org.bukkit.entity.Player) Scheduler.runAsync(task) else task.run()
    }

    /** Up to [limit] journal lines at or after [sinceMs] that pass [filter], newest first. */
    private fun read(sinceMs: Long, limit: Int, filter: (JsonObject) -> Boolean): List<Pair<JsonObject, String>> {
        val out = ArrayList<Pair<JsonObject, String>>()
        val firstDay = Instant.ofEpochMilli(sinceMs).atZone(ActivityJournal.zone).toLocalDate()
        for (file in ActivityJournal.files()) {
            val day = ActivityJournalFormat.dateOf(file.name) ?: continue
            if (day.isBefore(firstDay)) break
            val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
            for (raw in lines.asReversed()) {
                val obj = ActivityJournalFormat.parse(raw) ?: continue
                val ms = ActivityJournalFormat.msOf(obj) ?: continue
                if (ms < sinceMs) return out
                if (!filter(obj)) continue
                out += obj to raw
                if (out.size >= limit) return out
            }
        }
        return out
    }

    private fun line(obj: JsonObject, raw: String): Component {
        fun s(key: String): String? = obj[key]?.jsonPrimitive?.content
        val refused = s("refused") == "true"
        val who = when (s("by")) {
            "player" -> s("player") ?: "?"
            "console" -> "console"
            else -> "(automatic)"
        }
        val what = buildString {
            append(s("event") ?: "?")
            listOfNotNull(s("text")?.let { "-> $it" }, s("old")?.let { "(was $it)" }, s("title"), s("url"), s("toMs")?.let { "to ${it}ms" })
                .forEach { append(' ').append(it) }
            if (refused) append(" REFUSED: ").append(s("reason") ?: "?")
            s("repeats")?.let { append(" (+$it more)") }
        }
        val where = s("displayName") ?: s("display")?.take(8) ?: "?"
        return text("${TIME.format(Instant.ofEpochMilli(ActivityJournalFormat.msOf(obj) ?: 0L))} ", NamedTextColor.GRAY)
            .append(text(safe(who, 32), NamedTextColor.WHITE))
            .append(text(" @ ${safe(where, 40)} ", NamedTextColor.GRAY))
            .append(text(safe(what, 200), if (refused) NamedTextColor.RED else NamedTextColor.AQUA))
            .hoverEvent(HoverEvent.showText(text(safe(raw, 1_000), NamedTextColor.GRAY)))
    }

    private fun text(content: String, color: NamedTextColor) = Component.text(content, color)

    private fun safe(text: String, max: Int): String = ReportLimits.forChat(text, max)
}
