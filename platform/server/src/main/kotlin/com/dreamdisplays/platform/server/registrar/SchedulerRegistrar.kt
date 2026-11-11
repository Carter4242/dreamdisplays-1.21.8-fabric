package com.dreamdisplays.platform.server.registrar

import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.karaoke.KaraokeManager
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.meta.Updater
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplays.platform.server.playback.QueueManager
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.playback.WatchPartyManager
import com.dreamdisplays.platform.server.policy.ClientDiagnostics
import com.dreamdisplays.platform.server.proxy.ProxyBridge
import com.dreamdisplays.platform.server.utils.PlatformUtil
import io.github.arnodoelinger.platformweaver.PaperOnly
import java.util.concurrent.TimeUnit

/**
 * Manages the registration of scheduled tasks.
 */
@PaperOnly
object SchedulerRegistrar {
    private const val TICKS_PER_SECOND = 20L
    private const val TICK_MILLIS = 50L
    private const val DISPLAY_UPDATE_INTERVAL_TICKS = 1L * TICKS_PER_SECOND
    private const val QUEUE_TICK_INTERVAL_TICKS = 2L
    private const val UPDATE_CHECK_INTERVAL_TICKS = 60L * 60L * TICKS_PER_SECOND

    /** Schedules the periodic display update tick and, when enabled, the hourly update check. */
    fun runRepeatingTasks(plugin: PaperServer) {
        runRepeatingSync(
            plugin,
            DISPLAY_UPDATE_INTERVAL_TICKS,
            DISPLAY_UPDATE_INTERVAL_TICKS
        ) {
            // Each on its own: one throwing every tick must not starve the others
            step("display update") {
                if (PlatformUtil.isFolia) {
                    DisplayManager.updateAllDisplaysForTrackedPlayers()
                } else {
                    DisplayManager.updateAllDisplays()
                }
            }
            step("timeline tick") { TimelineManager.tick() }
            step("watch party tick") { WatchPartyManager.tick() }
            step("fullscreen tick") { FullscreenBroadcastManager.tick() }
            step("proxy tick") { ProxyBridge.tick() }
            step("diagnostics tick") { ClientDiagnostics.tick() }
        }
        // Queue advances land within a tenth of a second of a video's end; a no-op while nothing is queued
        runRepeatingSync(plugin, QUEUE_TICK_INTERVAL_TICKS, QUEUE_TICK_INTERVAL_TICKS) {
            step("queue tick") { QueueManager.tick() }
            // Karaoke stages (who hears a singer, their pings) stay at most a tenth of a second old
            step("karaoke tick") { KaraokeManager.tick() }
        }
        val settings = PaperServer.config.settings
        if (settings.updatesEnabled) {
            runRepeatingAsync(
                plugin,
                TICKS_PER_SECOND,
                UPDATE_CHECK_INTERVAL_TICKS
            ) {
                Updater.checkForUpdates(
                    settings.repoOwner,
                    settings.repoName
                )
            }
        }
    }

    private val logger = org.slf4j.LoggerFactory.getLogger(javaClass)
    private const val STEP_ERROR_LOG_MS = 60_000L
    private val stepErrorLoggedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Runs [task]; a failure is logged (once a minute per step) instead of skipping the steps after it. */
    private fun step(name: String, task: () -> Unit) {
        try {
            task()
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            val last = stepErrorLoggedAt[name]
            if (last == null || now - last >= STEP_ERROR_LOG_MS) {
                stepErrorLoggedAt[name] = now
                logger.error("Scheduled {} failed (logged at most once a minute).", name, e)
            }
        }
    }

    private fun runRepeatingSync(plugin: PaperServer, delayTicks: Long, intervalTicks: Long, task: Runnable) {
        if (PlatformUtil.isFolia) {
            plugin.server.globalRegionScheduler.runAtFixedRate(
                plugin,
                { task.run() },
                delayTicks.coerceAtLeast(1L),
                intervalTicks.coerceAtLeast(1L),
            )
        } else {
            plugin.server.scheduler.runTaskTimer(plugin, task, delayTicks, intervalTicks)
        }
    }

    // The Paper async scheduler only exists from 1.20 (alongside Folia), so plain Paper uses the Bukkit one
    private fun runRepeatingAsync(plugin: PaperServer, delayTicks: Long, intervalTicks: Long, task: Runnable) {
        if (PlatformUtil.isFolia) {
            plugin.server.asyncScheduler.runAtFixedRate(
                plugin,
                { task.run() },
                delayTicks.coerceAtLeast(0L) * TICK_MILLIS,
                intervalTicks.coerceAtLeast(1L) * TICK_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        } else {
            plugin.server.scheduler.runTaskTimerAsynchronously(plugin, task, delayTicks.coerceAtLeast(0L), intervalTicks.coerceAtLeast(1L))
        }
    }
}
