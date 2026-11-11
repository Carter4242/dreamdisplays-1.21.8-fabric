package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.core.diagnostics.ClientErrorLog
import com.dreamdisplays.core.diagnostics.ReportLimits
import com.dreamdisplays.core.protocol.common.packets.ClientEnvironment
import com.dreamdisplays.core.protocol.common.packets.ClientErrorReport
import com.dreamdisplays.platform.server.datatypes.display.shortLabel
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.policy.ClientErrorReports
import com.dreamdisplays.platform.server.policy.ClientErrorReports.Entry
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * `/display errors [player|display|#n] [errors|hitches|commands] [count]`: the latest client error reports, playback
 * hitches and display commands (newest first), each with its detail on hover; clicking one (or `#n`) prints all of
 * it. `/display diag <player>`: that player's client environment summary. Same permission as `/display policy`;
 * output only goes to the sender. Client-supplied text is printed as plain text, never parsed for formatting.
 */
@PaperOnly
object ErrorsCommand {
    private const val DEFAULT_COUNT = 10
    private const val MAX_COUNT = 50
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    private val TYPES = mapOf(
        "errors" to ClientErrorReport.TYPE_ERROR, "error" to ClientErrorReport.TYPE_ERROR,
        "hitches" to ClientErrorReport.TYPE_HITCH, "hitch" to ClientErrorReport.TYPE_HITCH,
        "shares" to ClientErrorReport.TYPE_SHARE, "share" to ClientErrorReport.TYPE_SHARE,
        "commands" to "command", "command" to "command", "all" to null,
    )

    /** Suggestions for the first argument: known players, displays, filters. */
    fun targetSuggestions(): List<String> =
        (Bukkit.getOnlinePlayers().map { it.name } + ClientErrorReports.knownPlayerNames() +
            listOf("errors", "hitches", "shares", "commands")).distinct()

    fun optionSuggestions(): List<String> = listOf("errors", "hitches", "shares", "commands", "all", "10", "25", "50")

    fun execute(sender: CommandSender, args: List<String>) {
        var count = DEFAULT_COUNT
        var type: String? = null
        var filterLabel = "all"
        var filter: (Entry) -> Boolean = { true }
        for ((index, raw) in args.withIndex()) {
            val token = raw.trim()
            if (token.isEmpty()) continue
            token.toIntOrNull()?.let { count = it.coerceIn(1, MAX_COUNT); continue }
            if (token.lowercase() in TYPES) {
                type = TYPES[token.lowercase()]
                continue
            }
            if (index == 0 && token.startsWith("#")) {
                showDetail(sender, token.removePrefix("#").toLongOrNull())
                return
            }
            if (index != 0) {
                sender.sendMessage(text("Unknown option '${ReportLimits.forChat(token, 32)}'. Use errors, hitches, shares, commands, all or a count.", NamedTextColor.RED))
                return
            }
            val target = resolveTarget(token) ?: run {
                sender.sendMessage(text("No player or display '${ReportLimits.forChat(token, 40)}' with reports.", NamedTextColor.RED))
                return
            }
            filterLabel = target.first
            filter = target.second
        }
        val wanted = type
        val entries = ClientErrorReports.recent(count) { filter(it) && (wanted == null || it.type == wanted) }
        val what = when (wanted) {
            ClientErrorReport.TYPE_ERROR -> "errors"
            ClientErrorReport.TYPE_HITCH -> "hitches"
            ClientErrorReport.TYPE_SHARE -> "screen share reports"
            "command" -> "display commands"
            else -> "client reports"
        }
        if (entries.isEmpty()) {
            sender.sendMessage(text("No $what for $filterLabel since the server started (log: ${ClientErrorReports.logFile().name}).", NamedTextColor.GRAY))
            return
        }
        sender.sendMessage(
            text("Latest ${entries.size} $what for $filterLabel ", NamedTextColor.GOLD)
                .append(text("(newest first; hover for detail, click to expand)", NamedTextColor.DARK_GRAY))
        )
        entries.forEach { sender.sendMessage(line(it)) }
    }

    private fun resolveTarget(token: String): Pair<String, (Entry) -> Boolean>? {
        val online = Bukkit.getPlayerExact(token)
        val player = online?.let { it.uniqueId to it.name } ?: ClientErrorReports.findPlayer(token)
        if (player != null) return "player ${player.second}" to { e: Entry -> e.playerId == player.first }
        DisplayManager.resolveByIdOrPrefix(token)?.let { display ->
            return "display ${display.shortLabel}" to { e: Entry -> e.displayId == display.id }
        }
        val byName = ClientErrorReports.recent(1) { it.displayName.equals(token, ignoreCase = true) }.firstOrNull()
            ?: return null
        return "display ${byName.displayName}" to { e: Entry -> e.displayId == byName.displayId }
    }

    private fun line(e: Entry): Component {
        val head = text("#${e.seq} ", NamedTextColor.DARK_GRAY)
            .append(text(TIME.format(Instant.ofEpochMilli(e.atMs)) + " ", NamedTextColor.GRAY))
            .append(text(e.playerName, NamedTextColor.WHITE))
            .append(text(" @ ${safe(e.displayName ?: e.displayId.toString().take(8), 40)} ", NamedTextColor.GRAY))
        val body = when (val r = e.report) {
            null -> text("${e.command}", NamedTextColor.AQUA)
                .append(text(listOfNotNull(e.positionMs?.let { " at ${clock(it)}" }, e.commandDetail?.let { " $it" })
                    .joinToString("").let { safe(it, 160) }, NamedTextColor.GRAY))

            else -> if (r.isShare) {
                text("SHARE ${r.stage}${if (r.action.isNotEmpty()) " [${safe(r.action, 24)}]" else ""}", NamedTextColor.LIGHT_PURPLE)
                    .append(text(": ${safe(r.message, 100)}", NamedTextColor.GRAY))
            } else if (r.isHitch) {
                text("HITCH ${r.stage}${if (r.cause.isNotEmpty()) "/${safe(r.cause, 24)}" else ""} ${r.durationMs}ms", NamedTextColor.YELLOW)
                    .append(text(" ${r.action} at ${clock(r.positionMs)}: ${safe(r.message, 100)}", NamedTextColor.GRAY))
            } else {
                val tag = if (r.final) "FAILED" else "retry ${r.retry}/${r.maxRetries}"
                text("${r.stage} ${r.kind}${if (r.httpStatus != 0) " ${r.httpStatus}" else ""} [$tag]", NamedTextColor.RED)
                    .append(text(": ${safe(r.message, 100)}", NamedTextColor.GRAY))
            }
        }
        return head.append(body)
            .hoverEvent(HoverEvent.showText(text(detail(e, hover = true), NamedTextColor.GRAY)))
            .clickEvent(ClickEvent.runCommand("/display errors #${e.seq}"))
    }

    private fun showDetail(sender: CommandSender, seq: Long?) {
        val entry = seq?.let(ClientErrorReports::entry) ?: run {
            sender.sendMessage(text("No such entry (only the latest 500 are kept in memory; the rest are in ${ClientErrorReports.logFile().name}).", NamedTextColor.RED))
            return
        }
        sender.sendMessage(text("Entry #${entry.seq}", NamedTextColor.GOLD))
        sender.sendMessage(text(detail(entry, hover = false), NamedTextColor.GRAY))
    }

    /** Everything known about [e], as plain lines; the hover version is shorter. */
    private fun detail(e: Entry, hover: Boolean): String = buildString {
        appendLine("${ClientErrorLog.time(e.atMs)}  ${e.playerName}${e.build?.let { " (build $it)" }.orEmpty()}")
        appendLine("display ${safe(e.displayName ?: "-", 40)} ${e.displayId}")
        val r = e.report
        if (r == null) {
            append("command ${e.command}")
            e.positionMs?.let { append(" at ${clock(it)}") }
            e.commandDetail?.let { append("\n${safe(it, 600)}") }
            return@buildString
        }
        appendLine("url ${safe(r.url, if (hover) 120 else 512)}")
        appendLine("${r.type.ifEmpty { "error" }} stage=${r.stage} kind=${r.kind}${if (r.httpStatus != 0) " http=${r.httpStatus}" else ""}")
        if (r.isHitch) {
            appendLine("frozen ${r.durationMs} ms at ${clock(r.positionMs)}, action ${r.action}")
            if (r.cause.isNotEmpty()) appendLine("cause ${safe(r.cause, 32)}")
            if (r.metrics.isNotEmpty()) {
                appendLine(r.metrics.toSortedMap().entries.joinToString(" ") { "${safe(it.key, 32)}=${safe(it.value, 80)}" })
            }
        }
        else if (r.isShare) {
            if (r.action.isNotEmpty()) appendLine("outcome ${safe(r.action, 32)}")
            r.metrics.toSortedMap().forEach { (k, v) -> appendLine("${safe(k, 32)}: ${safe(v, if (hover) 120 else 256)}") }
        }
        else appendLine("retry ${r.retry}/${r.maxRetries} final=${r.final} fatal=${r.fatal}${if (r.positionMs > 0) " at ${clock(r.positionMs)}" else ""}")
        if (r.suppressed > 0) appendLine("(${r.suppressed} identical reports held back by the client before this one)")
        appendLine(safe(r.message, if (hover) 200 else 512))
        if (r.attempts.isNotEmpty()) {
            appendLine(if (r.isShare) "pipeline tests (oldest first):" else "resolve steps (oldest first):")
            r.attempts.forEach { a ->
                appendLine(
                    "  ${a.source}${if (a.client.isNotEmpty()) "[${a.client}]" else ""} " +
                        (if (a.ok) "ok" else "FAILED: ${safe(a.error, if (hover) 100 else 300)}") +
                        (if (a.agoMs > 0) " (${a.agoMs / 1000}s before)" else "")
                )
            }
        }
        if (r.stderr.isNotEmpty()) {
            appendLine("stderr:")
            val lines = r.stderr.lines()
            (if (hover) lines.takeLast(6) else lines).forEach { appendLine("  ${safe(it, if (hover) 140 else 400)}") }
        }
        if (hover) append("click for everything")
    }.trimEnd()

    /** `/display diag <player>`: the stored environment summary. */
    fun diag(sender: CommandSender, name: String) {
        val online = Bukkit.getPlayerExact(name)
        val id = online?.uniqueId ?: ClientErrorReports.findPlayer(name)?.first
        val stored = id?.let(ClientErrorReports::environment)
        if (stored == null) {
            sender.sendMessage(text("No client environment from '${safe(name, 40)}' (they need the mod, and it is sent ~15 s after joining).", NamedTextColor.RED))
            return
        }
        val build = ClientsCommand.buildLabel(stored.playerId)
        sender.sendMessage(
            text("Client environment of ${stored.playerName}", NamedTextColor.GOLD)
                .append(text(" (${ClientErrorLog.time(stored.atMs)}${build?.let { ", build $it" }.orEmpty()})", NamedTextColor.DARK_GRAY))
        )
        envLines(stored.env).forEach { (k, v) ->
            sender.sendMessage(text("$k: ", NamedTextColor.GRAY).append(text(safe(v, 300), NamedTextColor.WHITE)))
        }
    }

    private fun envLines(e: ClientEnvironment): List<Pair<String, String>> = buildList {
        add("OS" to listOf(e.osName, e.osVersion, e.osArch).filter { it.isNotEmpty() }.joinToString(" "))
        add("Java" to listOf(e.javaVersion, e.javaVendor).filter { it.isNotEmpty() }.joinToString(", "))
        add("GPU" to listOf(e.gpuVendor, e.gpuRenderer).filter { it.isNotEmpty() }.joinToString(" / ").ifEmpty { "?" })
        if (e.glVersion.isNotEmpty()) add("OpenGL" to e.glVersion)
        add("Shader pack" to e.shaderPack.ifEmpty { "?" })
        add("yt-dlp" to listOf(e.ytDlpMode, e.ytDlpVersion, e.ytDlpPath).filter { it.isNotEmpty() }.joinToString(", "))
        if (e.ytDlpPython.isNotEmpty()) add("yt-dlp Python" to "${e.ytDlpPython} ${e.ytDlpPythonVersion}".trim())
        if (e.ytDlpNote.isNotEmpty()) add("yt-dlp note" to e.ytDlpNote)
        add("FFmpeg" to listOf(e.ffmpegSource, e.ffmpegVersion, e.ffmpegPath).filter { it.isNotEmpty() }.joinToString(", "))
        add("FFmpeg optional" to "decoders ${e.ffmpegDecoders.ifEmpty { listOf("none") }.joinToString(",")}; " +
            "filters ${e.ffmpegFilters.ifEmpty { listOf("none") }.joinToString(",")}")
        add("HW decode" to e.hwAccel.ifEmpty { "?" })
        add("Native pipeline" to if (e.nativeLoaded) "loaded" else "unavailable (${e.nativeUnavailableReason})")
        add("LAV pipeline" to if (e.lavLoaded) "loaded" else "unavailable (${e.lavUnavailableReason})")
        add("Audio line" to e.audioLine.ifEmpty { "none opened yet" })
    }

    private fun text(content: String, color: NamedTextColor): TextComponent = Component.text(content, color)

    private fun safe(text: String, max: Int): String = ReportLimits.forChat(text, max)

    private fun clock(positionMs: Long): String {
        val total = positionMs.coerceAtLeast(0L) / 1_000L
        val h = total / 3_600L
        val m = (total / 60L) % 60L
        val s = total % 60L
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}

/**
 * `/display debug <player> [quiet|normal|verbose|ultra|reset]`: shows or sets how much that player's client writes to
 * its own log. Saved per player and pushed to their client within a second; reports to the server are the same at
 * every level. Same permission as `/display policy`.
 */
@PaperOnly
object DebugLevelCommand {
    fun levelSuggestions(): List<String> =
        com.dreamdisplays.core.display.policy.ClientLogLevel.entries.map { it.wire } + "reset"

    fun execute(sender: CommandSender, name: String, level: String?) {
        val target = Bukkit.getPlayerExact(name)?.let { it.uniqueId to it.name }
            ?: ClientErrorReports.findPlayer(name)
            ?: Bukkit.getOfflinePlayerIfCached(name)?.let { it.uniqueId to (it.name ?: name) }
        if (target == null) {
            sender.sendMessage(Component.text("Unknown player '${ReportLimits.forChat(name, 40)}'.", NamedTextColor.RED))
            return
        }
        val (id, playerName) = target
        val serverWide = com.dreamdisplays.core.display.policy.ServerClientSettings(
            com.dreamdisplays.platform.server.policy.DisplayPolicies.defaults().serverWide(),
        ).logLevel
        if (level == null) {
            val own = com.dreamdisplays.platform.server.policy.ClientLogLevels.get(id)
            sender.sendMessage(
                Component.text("$playerName client log level: ", NamedTextColor.GRAY)
                    .append(Component.text((own ?: serverWide).wire, NamedTextColor.WHITE))
                    .append(Component.text(if (own == null) " (server-wide default)" else " (set for this player)", NamedTextColor.DARK_GRAY))
            )
            return
        }
        val parsed = if (level.equals("reset", ignoreCase = true)) null
        else com.dreamdisplays.core.display.policy.ClientLogLevel.fromWire(level) ?: run {
            sender.sendMessage(Component.text("Level must be quiet, normal, verbose, ultra or reset.", NamedTextColor.RED))
            return
        }
        com.dreamdisplays.platform.server.policy.ClientLogLevels.set(id, parsed)
        sender.sendMessage(
            Component.text("$playerName client log level is now ", NamedTextColor.GRAY)
                .append(Component.text((parsed ?: serverWide).wire, NamedTextColor.WHITE))
                .append(Component.text(if (parsed == null) " (server-wide default)" else "; applies live if they are online", NamedTextColor.DARK_GRAY))
        )
    }
}
