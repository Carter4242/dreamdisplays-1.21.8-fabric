package com.dreamdisplays.platform.server

import com.dreamdisplays.core.sync.ResyncMarker
import com.dreamdisplays.platform.server.api.PaperServerApi
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.karaoke.KaraokeManager
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.StorageManager
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import com.dreamdisplays.platform.server.metrics.TelemetryMetrics
import com.dreamdisplays.platform.server.playback.*
import com.dreamdisplays.platform.server.policy.DisplayPolicies
import com.dreamdisplays.platform.server.proxy.ProxyBridge
import com.dreamdisplays.platform.server.registrar.ChannelRegistrar
import com.dreamdisplays.platform.server.registrar.CommandRegistrar
import com.dreamdisplays.platform.server.registrar.ListenerRegistrar
import com.dreamdisplays.platform.server.storage.StorageBackend
import com.dreamdisplays.platform.server.storage.TimelineStore
import com.dreamdisplays.platform.server.utils.net.PaperV2Networking
import com.dreamdisplays.platform.server.utils.net.QueueActions
import io.github.arnodoelinger.platformweaver.PaperOnly
//? if >=1.20.5 {
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
//?} else
/*import com.dreamdisplays.platform.server.registrar.LegacyPaperCommands*/
import org.bstats.bukkit.Metrics
import org.bukkit.plugin.java.JavaPlugin
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory

/**
 * Entry point of `Dream Displays` server-side plugin. `@PaperOnly` annotation is used when code relies on `Paper`-specific
 * API not present on vanilla loaders.
 */
@PaperOnly
@NullMarked
class PaperServer : JavaPlugin() {
    /** Storage manager for persistent data. */
    lateinit var storage: StorageManager

    override fun onLoad() {
        instance = this
        Companion.config = Config(this)
        //? if >=1.20.5 {
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(CommandRegistrar.buildDisplayCommand(), "Main Dream Displays command")
        }
        //?}
    }

    override fun onEnable() {
        //? if <1.20.5 {
        LegacyPaperCommands.register(this, CommandRegistrar.buildDisplayCommand(), "Main Dream Displays command")
        //?}
        doEnable()
    }

    override fun onDisable() {
        doDisable()
    }

    /** Initializes scheduler, storage, listeners, channels, and metrics. Safe to call from a reload. */
    fun doEnable() {
        org.slf4j.LoggerFactory.getLogger(PaperServer::class.java).info(
            "Enabling DreamDisplays {} (build {})", pluginMeta.version, com.dreamdisplays.util.BuildInfo.commit,
        )
        ServerCoroutines.warmUp()
        TelemetryMetrics.warmUp()
        com.dreamdisplays.platform.server.utils.SecretLogFilter.install()

        Scheduler.init(this)

        val s = Companion.config.storage
        val backend = StorageBackend.fromConfig(s.type)
        storage = StorageManager(
            backend = backend, dataDir = dataFolder, tablePrefix = s.tablePrefix,
            host = s.host, port = s.port, database = s.database,
            username = s.username, password = s.password, useSSL = s.useSSL,
        )
        storage.createSchema()
        DisplayManager.register(storage.loadAllPaperDisplays().onEach(::stripResyncMarker))
        // A screen share's relay URL is never stored: every save of a shared display keeps the video it had before
        com.dreamdisplays.platform.server.managers.StoredVideos.override =
            com.dreamdisplays.platform.server.utils.net.ScreenShareActions::storedVideo
        DisplayPolicies.defaults = { Companion.config.settings.policy }
        DisplayPolicies.onOverrideChanged = { display ->
            (display as? PaperDisplayData)?.let {
                Scheduler.runAsync { storage.saveDisplay(it) }
                DisplayManager.broadcastUpdate(it)
            }
        }

        WatchPartyManager.init(PaperPlaybackTransport)
        TimelineManager.init(
            PaperPlaybackTransport,
            runCatching { TimelineStore.take() }
                .onFailure { Companion.logger.warn("Could not read the saved display timelines.", it) }
                .getOrDefault(emptyList()),
        )
        TimelineManager.resumeHoldMs = { Companion.config.settings.resumeHoldMs }
        TimelineManager.seekHoldMs = { Companion.config.settings.seekHoldMs }
        ReadinessTracker.startLeadMaxMs = { Companion.config.settings.startLeadMaxMs }
        TimelineManager.autoPauseWhenEmptyMs = { Companion.config.settings.autoPauseWhenEmptyMs }
        FullscreenBroadcastManager.init(PaperPlaybackTransport)
        FullscreenBroadcastManager.prepareTimeoutMs = { Companion.config.settings.fullscreenPrepareTimeoutMs }
        FullscreenBroadcastManager.restore()
        PipPinManager.init(PaperPlaybackTransport)
        PipPinManager.restore()
        QueueManager.init(PaperPlaybackTransport)
        QueueManager.settings = QueueActions::settings
        QueueManager.applyVideo = QueueActions::applyVideo
        QueueManager.farReceivers = { FullscreenBroadcastManager.timelineFollowers(it) + PipPinManager.pinnersOf(it) }
        QueueManager.restore()
        KaraokeManager.settings = KaraokeManager::settingsFromConfig
        DisplayPolicies.trackVolume = {
            if (KaraokeManager.isActive(it)) KaraokeManager.settings().let { s -> s.trackVolume to s.singerTrackVolume } else null
        }
        KaraokeManager.init()
        ScheduledPlaybackManager.init(PaperPlaybackTransport)
        ScheduledPlaybackManager.restoreOnStartup()

        ProxyBridge.init(Companion.config.proxy.enabled, Companion.config.proxy.clock_sync_interval)

        ListenerRegistrar.registerListeners(this)
        ChannelRegistrar.registerChannels(this)
        // Players still online across a plugin reload: let their clients handshake again
        Scheduler.runSync { runCatching { PaperV2Networking.greetOnlinePlayers() } }
        runRepeatingTasks()

        TelemetryMetrics.register(this, Metrics(this, 26488))

        PaperServerApi.start()
    }

    /** Removes the `ddsync=N` reload marker older builds put in display URLs, saving the cleaned URL. */
    private fun stripResyncMarker(display: PaperDisplayData) {
        val cleaned = ResyncMarker.strip(display.url)
        if (cleaned == display.url) return
        display.url = cleaned
        runCatching { storage.saveDisplay(display) }
            .onSuccess { Companion.logger.info("Removed the old resync marker from display ${display.id}'s URL.") }
            .onFailure { Companion.logger.warn("Could not save display ${display.id} after removing its resync marker.", it) }
    }

    private fun runRepeatingTasks() {
        val registrarClass = Class.forName("com.dreamdisplays.platform.server.registrar.SchedulerRegistrar")
        val registrar = registrarClass.getField("INSTANCE").get(null)
        registrarClass.getMethod("runRepeatingTasks", PaperServer::class.java).invoke(registrar, this)
    }

    /**
     * Persists state and tears down resources. Safe to call from a reload.
     *
     * Each step is isolated.
     */
    fun doDisable() {
        if (!::storage.isInitialized) return
        disableStep("stop addons") { PaperServerApi.stop() }
        disableStep("stop karaoke") { KaraokeManager.shutdown() }
        disableStep("end transient fullscreen sessions") { FullscreenBroadcastManager.endTransientSessions() }
        disableStep("end screen shares") { com.dreamdisplays.platform.server.utils.net.ScreenShareActions.stopAll() }
        disableStep("save timelines") { TimelineStore.save(TimelineManager.snapshot()) }
        disableStep("end watch parties") { WatchPartyManager.endAllForShutdown() }
        disableStep("save displays") { DisplayManager.save { data: PaperDisplayData -> storage.saveDisplay(data) } }
        disableStep("save queues") { QueueManager.flush() }
        disableStep("save PiP pins") { PipPinManager.flush() }
        disableStep("stop the client error log") { com.dreamdisplays.platform.server.policy.ClientErrorReports.shutdown() }
        disableStep("stop background IO") { ServerCoroutines.shutdown() }
        disableStep("close storage") { storage.disconnect() }
        disableStep("remove the log filter") { com.dreamdisplays.platform.server.utils.SecretLogFilter.uninstall() }
    }

    private fun disableStep(what: String, step: () -> Unit) {
        runCatching(step).onFailure { Companion.logger.error("Failed to $what while disabling.", it) }
    }

    companion object {
        /** Logger. */
        val logger = LoggerFactory.getLogger(javaClass)

        /** Mod config (`Fabric` server included). */
        lateinit var config: Config

        /** Returns the singleton plugin instance. */
        fun getInstance(): PaperServer = instance

        /** Forces `Bukkit` to disable this plugin (used when fatal startup errors occur). */
        fun disablePlugin() {
            instance.server.pluginManager.disablePlugin(instance)
        }

        private lateinit var instance: PaperServer
    }
}
