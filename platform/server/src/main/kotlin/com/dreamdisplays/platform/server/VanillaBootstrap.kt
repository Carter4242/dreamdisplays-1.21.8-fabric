package com.dreamdisplays.platform.server

import com.dreamdisplays.platform.server.ModLoaderOnly
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.StorageManager
import com.dreamdisplays.platform.server.managers.VanillaSelectionVisualizer
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import com.dreamdisplays.platform.server.meta.Updater
import com.dreamdisplays.platform.server.playback.*
import com.dreamdisplays.platform.server.policy.DisplayPolicies
import com.dreamdisplays.platform.server.proxy.VanillaProxyBridge
import com.dreamdisplays.platform.server.api.VanillaServerApi
import com.dreamdisplays.platform.server.storage.StorageBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.server.MinecraftServer
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shared `Fabric` / `NeoForge` server-lifecycle bootstrap: storage bring-up, display registration, playback transport wiring,
 * and the repeating background tasks.
 */
@ModLoaderOnly
object VanillaBootstrap {
    /** How often loop ends are checked; the restart is sent up to this early (Paper runs them every 2 ticks too). */
    private const val END_TICK_MS = 100L

    /** Connects storage, loads displays, binds playback, and starts the repeating tasks. */
    fun onServerStarted(server: MinecraftServer, dataDir: File) {
        val s = VanillaServerState.config.storage
        val storage = StorageManager(
            backend = StorageBackend.fromConfig(s.type), dataDir = dataDir,
            tablePrefix = s.tablePrefix,
            host = s.host, port = s.port, database = s.database,
            username = s.username, password = s.password, useSSL = s.useSSL,
        )
        VanillaServerState.storage = storage
        storage.createSchema()
        DisplayManager.register(storage.loadAllVanillaDisplays())
        DisplayPolicies.defaults = { VanillaServerState.config.settings.policy }
        VanillaPlaybackTransport.bind(server)
        TimelineManager.init(VanillaPlaybackTransport)
        FullscreenBroadcastManager.init(VanillaPlaybackTransport)
        FullscreenBroadcastManager.prepareTimeoutMs = { VanillaServerState.config.settings.fullscreenPrepareTimeoutMs }
        ReadinessTracker.startLeadMaxMs = { VanillaServerState.config.settings.startLeadMaxMs }
        FullscreenBroadcastManager.restore()
        PipPinManager.init(VanillaPlaybackTransport)
        PipPinManager.restore()
        ScheduledPlaybackManager.init(VanillaPlaybackTransport)
        ScheduledPlaybackManager.restoreOnStartup()
        VanillaProxyBridge.init(
            VanillaServerState.config.proxy.enabled,
            VanillaServerState.config.proxy.clock_sync_interval
        )
        // Permission changes reach online clients without a relog
        com.dreamdisplays.platform.server.utils.net.VanillaHelloResync.start()
        startRepeatingTasks(server)
        VanillaServerApi.start()
    }

    /**
     * Persists all displays and disconnects storage. In single-player the game keeps running and the next world opened
     * starts another server in this same process: everything this one held is forgotten here, and the background scope
     * stays usable for it.
     */
    fun onServerStopping() {
        VanillaServerApi.stop()
        com.dreamdisplays.platform.server.utils.net.VanillaHelloResync.stop()
        DisplayManager.save { VanillaServerState.storage?.saveDisplay(it) }
        // A debounced save still waiting is cancelled with the background scope: write now, as Paper's disable does
        PipPinManager.flush()
        ServerCoroutines.shutdown()
        VanillaServerState.storage?.disconnect()
        forgetWorld()
    }

    private fun forgetWorld() {
        DisplayManager.clearAll()
        TimelineManager.reset()
        FullscreenBroadcastManager.forgetAll()
        PipPinManager.forgetAll()
        ScheduledPlaybackManager.forgetAll()
        com.dreamdisplays.platform.server.listeners.VanillaPlayerListener.onServerStopped()
    }

    private fun startRepeatingTasks(server: MinecraftServer) {
        val settings = VanillaServerState.config.settings

        ServerCoroutines.io.launch {
            while (!server.isStopped) {
                delay(1000L.milliseconds)
                runCatching {
                    server.execute {
                        DisplayManager.updateAllDisplays(server)
                        TimelineManager.tick()
                        FullscreenBroadcastManager.tick()
                        VanillaProxyBridge.tick(server)
                    }
                }
            }
        }

        // As on Paper: a video with nothing queued restarts for everyone at its end, and a clock already past an end
        // (a length that came down below it) restarts at once; without this it would never loop again
        ServerCoroutines.io.launch {
            while (!server.isStopped) {
                delay(END_TICK_MS.milliseconds)
                runCatching {
                    server.execute {
                        TimelineManager.tickEnds()
                        FullscreenBroadcastManager.tickEnds()
                        // A permission granted or revoked while a player is online reaches their client
                        runCatching { com.dreamdisplays.platform.server.utils.net.VanillaHelloResync.tick(server) }
                    }
                }
            }
        }

        if (settings.particlesEnabled) {
            ServerCoroutines.io.launch {
                while (!server.isStopped) {
                    delay((settings.particleRenderDelay * 50L).milliseconds)
                    runCatching { server.execute { VanillaSelectionVisualizer.tick(server) } }
                }
            }
        }

        if (settings.updatesEnabled) {
            ServerCoroutines.io.launch {
                delay(1000L.milliseconds)
                runCatching { Updater.checkForUpdates(settings.repoOwner, settings.repoName) }
                while (!server.isStopped) {
                    delay((60L * 60L * 1000L).milliseconds)
                    runCatching { Updater.checkForUpdates(settings.repoOwner, settings.repoName) }
                }
            }
        }
    }
}
