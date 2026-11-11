package com.dreamdisplays.platform.server.policy

import com.dreamdisplays.core.display.policy.ServerClientSettings
import com.dreamdisplays.core.display.viewer.ViewerEntry
import com.dreamdisplays.core.display.viewer.ViewerReportStore
import com.dreamdisplays.core.protocol.common.packets.ServerPolicy
import com.dreamdisplays.core.protocol.common.packets.ViewerReport
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.utils.net.PaperV2Networking
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Server end of the client diagnostics channel: pushes the server-wide client policy ([ServerPolicy]) to every
 * v2 client, and keeps the latest [ViewerReport] each viewer sends per display, in memory only.
 */
@PaperOnly
object ClientDiagnostics {
    /** Re-send the unchanged policy this often, in case a client lost it (e.g. its state was reset). */
    private const val RESEND_MS = 30_000L

    /** A report older than this (or [STALE_INTERVALS] report intervals) means the viewer stopped reporting. */
    private const val MIN_STALE_MS = 15_000L
    private const val STALE_INTERVALS = 3

    private class Sent(val bytes: ByteArray, val atMs: Long)

    private val sent = ConcurrentHashMap<UUID, Sent>()
    private val reports = ViewerReportStore()

    /** Every second: sends the policy to v2 clients that lack the current one, and drops stale reports. */
    fun tick() {
        val bytes = DisplayPolicies.defaults().serverWide().encode()
        val now = System.currentTimeMillis()
        Scheduler.forEachTrackedPlayer { player ->
            if (!V2PlayerTracker.isV2(player.uniqueId)) return@forEachTrackedPlayer
            val last = sent[player.uniqueId]
            if (last != null && last.bytes.contentEquals(bytes) && now - last.atMs < RESEND_MS) return@forEachTrackedPlayer
            sent[player.uniqueId] = Sent(bytes, now)
            PaperV2Networking.send([player], ServerPolicy(bytes))
        }
        reports.prune(now, staleAfterMs())
        reports.displays().forEach { id -> if (DisplayManager.getDisplayData(id) == null) reports.forgetDisplay(id) }
    }

    /** Takes a report from [player]; unknown displays and reports over the rate limit are dropped. */
    fun onReport(player: Player, report: ViewerReport) {
        if (DisplayManager.getDisplayData(report.id) == null) return
        reports.offer(player.uniqueId, player.name, report, System.currentTimeMillis())
    }

    /** Fresh reports on [displayId]. */
    fun viewers(displayId: UUID): List<ViewerEntry> =
        reports.viewers(displayId, System.currentTimeMillis(), staleAfterMs())

    /** Displays with at least one fresh report. */
    fun reportedDisplays(): List<UUID> = reports.displays().filter { viewers(it).isNotEmpty() }

    fun onQuit(playerId: UUID) {
        sent.remove(playerId)
        reports.forgetPlayer(playerId)
    }

    private fun staleAfterMs(): Long {
        val interval = ServerClientSettings(DisplayPolicies.defaults().serverWide()).reportIntervalMs
        return maxOf(MIN_STALE_MS, interval * STALE_INTERVALS)
    }
}
