package com.dreamdisplays.platform.server.policy

import com.dreamdisplays.core.diagnostics.ClientErrorLog
import com.dreamdisplays.core.diagnostics.ReportLimits
import com.dreamdisplays.core.protocol.common.ClientBuild
import com.dreamdisplays.core.protocol.common.packets.ClientEnvironment
import com.dreamdisplays.core.protocol.common.packets.ClientErrorReport
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.shortLabel
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.entity.Player
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Server end of client error reporting: takes [ClientErrorReport]s (errors and hitches) and [ClientEnvironment]
 * summaries from players, appends them to `[clients] error_log` as JSON lines (with the reporter's environment the
 * first time they report something each session), and keeps the latest ones in memory for `/display errors` and
 * `/display diag`. Display commands (who paused, seeked, changed the video or mode) go to the same log. Nothing is
 * ever shown to the reporting player.
 */
@PaperOnly
object ClientErrorReports {
    private val logger = LoggerFactory.getLogger(javaClass)

    private const val MAX_RECENT = 500
    private const val MAX_ENVIRONMENTS = 500
    private const val REPORTS_PER_MINUTE = 30
    private const val ENVIRONMENTS_PER_MINUTE = 6

    /** One remembered line: a report from a player, or a display command. */
    class Entry(
        val seq: Long,
        val atMs: Long,
        val playerName: String,
        val playerId: UUID,
        val build: String?,
        val displayId: UUID,
        val displayName: String?,
        /** Null for a command. */
        val report: ClientErrorReport?,
        val command: String? = null,
        val commandDetail: String? = null,
        val positionMs: Long? = null,
    ) {
        val type: String
            get() = when {
                report == null -> "command"
                report.isHitch -> ClientErrorReport.TYPE_HITCH
                else -> ClientErrorReport.TYPE_ERROR
            }
    }

    /** Last environment summary per player, kept after they leave so `/display diag` still answers. */
    class Environment(val playerName: String, val playerId: UUID, val atMs: Long, val env: ClientEnvironment)

    private val seq = AtomicLong()
    private val recent = kotlin.collections.ArrayDeque<Entry>()
    private val environments = Collections.synchronizedMap(object : LinkedHashMap<UUID, Environment>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, Environment>?): Boolean = size > MAX_ENVIRONMENTS
    })
    private val envLoggedThisSession = ConcurrentHashMap.newKeySet<UUID>()
    private val reportRate = ReportLimits.Rate(REPORTS_PER_MINUTE)
    private val envRate = ReportLimits.Rate(ENVIRONMENTS_PER_MINUTE)
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "DreamDisplays-client-errors").apply { isDaemon = true } }

    @Volatile
    private var log: ClientErrorLog? = null

    private val settings get() = PaperServer.config.settings.clients

    fun onReport(player: Player, packet: ClientErrorReport) {
        if (!settings.error_reports) return
        val now = System.currentTimeMillis()
        if (!reportRate.tryAcquire(player.uniqueId, now)) return
        val report = ReportLimits.sanitize(packet)
        val hello = V2PlayerTracker.helloOf(player.uniqueId)
        val build = hello?.let { ClientBuild.of(it.buildCommit) }
        val version = hello?.modVersion?.take(32)
        val displayName = DisplayManager.getDisplayData(report.id)?.shortLabel
        val name = player.name
        remember(Entry(seq.incrementAndGet(), now, name, player.uniqueId, build, report.id, displayName, report))
        // The environment goes with the first report of the session, so one log line has what is needed
        val env = if (envLoggedThisSession.add(player.uniqueId)) environments[player.uniqueId]?.env else null
        write(ClientErrorLog.reportLine(now, name, player.uniqueId, build, version, report, displayName, env))
    }

    fun onEnvironment(player: Player, packet: ClientEnvironment) {
        if (!settings.error_reports) return
        val now = System.currentTimeMillis()
        if (!envRate.tryAcquire(player.uniqueId, now)) return
        val previous = environments[player.uniqueId]
        val env = ReportLimits.sanitize(packet)
        environments[player.uniqueId] = Environment(player.name, player.uniqueId, now, env)
        // This session's reports were already logged without this summary: log it on its own line
        if (previous?.env != env && player.uniqueId in envLoggedThisSession) {
            write(
                kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("environment"))
                    put("time", kotlinx.serialization.json.JsonPrimitive(ClientErrorLog.time(now)))
                    put("player", kotlinx.serialization.json.JsonPrimitive(player.name))
                    put("uuid", kotlinx.serialization.json.JsonPrimitive(player.uniqueId.toString()))
                    put("env", ClientErrorLog.envJson(env))
                }.toString()
            )
        }
    }

    /**
     * Logs a display command by [player]: [action] (`pause`, `resume`, `seek`, `restart`, `video`, `mode`) at
     * [positionMs], with [details] such as the new URL or mode.
     */
    fun logCommand(player: Player, displayId: UUID, action: String, positionMs: Long?, details: Map<String, String> = emptyMap()) {
        if (!settings.command_log) return
        val now = System.currentTimeMillis()
        val displayName = DisplayManager.getDisplayData(displayId)?.shortLabel
        val safe = details.mapValues { ReportLimits.forChat(it.value, ReportLimits.MAX_URL) }
        remember(
            Entry(
                seq.incrementAndGet(), now, player.name, player.uniqueId,
                V2PlayerTracker.helloOf(player.uniqueId)?.let { ClientBuild.of(it.buildCommit) },
                displayId, displayName, report = null, command = action,
                commandDetail = safe.entries.joinToString(" ") { "${it.key}=${it.value}" }.ifEmpty { null },
                positionMs = positionMs,
            )
        )
        write(ClientErrorLog.commandLine(now, player.name, player.uniqueId, displayId, displayName, action, positionMs, safe))
    }

    /** Most recent entries first, filtered by [predicate], at most [limit]. */
    fun recent(limit: Int, predicate: (Entry) -> Boolean = { true }): List<Entry> = synchronized(recent) {
        recent.asReversed().asSequence().filter(predicate).take(limit).toList()
    }

    fun entry(seq: Long): Entry? = synchronized(recent) { recent.firstOrNull { it.seq == seq } }

    fun environment(playerId: UUID): Environment? = environments[playerId]

    /** Players with a stored summary or a remembered entry whose name matches [name]. */
    fun findPlayer(name: String): Pair<UUID, String>? {
        synchronized(environments) {
            environments.values.firstOrNull { it.playerName.equals(name, ignoreCase = true) }
                ?.let { return it.playerId to it.playerName }
        }
        return synchronized(recent) {
            recent.lastOrNull { it.playerName.equals(name, ignoreCase = true) }?.let { it.playerId to it.playerName }
        }
    }

    fun knownPlayerNames(): List<String> {
        val names = HashSet<String>()
        synchronized(environments) { environments.values.forEach { names += it.playerName } }
        synchronized(recent) { recent.forEach { names += it.playerName } }
        return names.sorted()
    }

    fun onQuit(playerId: UUID) {
        envLoggedThisSession.remove(playerId)
        reportRate.forget(playerId)
        envRate.forget(playerId)
    }

    /** The log file in use (for `/display errors` to point at). */
    fun logFile(): File = File(PaperServer.getInstance().dataFolder, settings.error_log)

    private fun remember(entry: Entry) {
        synchronized(recent) {
            recent.addLast(entry)
            while (recent.size > MAX_RECENT) recent.removeFirst()
        }
    }

    private fun write(line: String) {
        val file = logFile().toPath()
        try {
            writer.execute {
                val current = log?.takeIf { it.path == file } ?: ClientErrorLog(file).also { log = it }
                if (!current.append(line)) logger.warn("Could not write to {}.", file)
            }
        } catch (_: RejectedExecutionException) {
            // Shut down (plugin disable): the line is dropped
        }
    }

    /**
     * Stops the writer thread once the lines already queued are written (plugin disable); a live thread would keep
     * this plugin instance's classes loaded after a reload.
     */
    fun shutdown() {
        writer.shutdown()
        if (!writer.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) writer.shutdownNow()
    }

    private const val SHUTDOWN_WAIT_SECONDS = 2L
}
