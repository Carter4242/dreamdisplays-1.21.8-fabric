package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.core.protocol.common.ZERO_UUID
import com.dreamdisplays.core.protocol.common.packets.ReportKind
import com.dreamdisplays.core.protocol.common.packets.ReportRequest
import com.dreamdisplays.platform.client.managers.DisplayWarmup
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers the server's [ReportRequest]s: sends this client's readiness, preload and duration reports again, even
 * unchanged. Answers are rate-limited per display and kind (a request for every display counts as one key), so a
 * server cannot make this client flood it.
 */
object ReportRequests {
    private const val PER_DISPLAY_MS = 1_000L
    private const val ALL_DISPLAYS_MS = 5_000L

    private val answeredAt = ConcurrentHashMap<Pair<UUID, Int>, Long>()

    fun handle(request: ReportRequest, nowMs: Long = System.currentTimeMillis()) {
        val all = request.displayId == ZERO_UUID
        val target = if (all) null else request.displayId
        for (kind in KINDS) {
            if (!ReportKind.has(request.kinds, kind)) continue
            if (!mayAnswer(answeredAt, request.displayId, kind, nowMs, if (all) ALL_DISPLAYS_MS else PER_DISPLAY_MS)) continue
            when (kind) {
                ReportKind.READINESS -> DisplayWarmup.reReport(target)
                ReportKind.PRELOAD -> screensFor(target).forEach { it.reReportPreload() }
                ReportKind.DURATION -> screensFor(target).forEach { it.reReportDuration() }
            }
        }
    }

    /** The server hello: a server that was reloaded has forgotten every readiness and preload report. */
    fun onServerHello() {
        DisplayWarmup.reReport(null)
        DisplayRegistry.screens.values.forEach { it.reReportPreload() }
    }

    fun reset() = answeredAt.clear()

    private fun screensFor(displayId: UUID?): Collection<DisplayScreen> =
        if (displayId == null) DisplayRegistry.screens.values else listOfNotNull(DisplayRegistry.screens[displayId])

    private val KINDS = intArrayOf(ReportKind.READINESS, ReportKind.PRELOAD, ReportKind.DURATION)
}

/**
 * Whether a request for [kind] reports on [displayId] may be answered at [nowMs], at most once per [intervalMs];
 * records the answer in [answeredAt] when it may.
 */
internal fun mayAnswer(
    answeredAt: MutableMap<Pair<UUID, Int>, Long>, displayId: UUID, kind: Int, nowMs: Long, intervalMs: Long,
): Boolean {
    val key = displayId to kind
    val last = answeredAt[key]
    if (last != null && nowMs - last < intervalMs) return false
    if (answeredAt.size > MAX_ANSWER_KEYS) answeredAt.clear()
    answeredAt[key] = nowMs
    return true
}

private const val MAX_ANSWER_KEYS = 4_096
