package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.media.audio.AcousticQuality
import com.dreamdisplays.core.display.policy.DisplayPolicy
import com.dreamdisplays.core.display.policy.ServerClientSettings
import com.dreamdisplays.core.protocol.common.packets.ServerPolicy
import com.dreamdisplays.core.runtime.getOrNull
import com.dreamdisplays.core.services.keys.AudioAcousticsServices
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.core.modules.ClientAudioModule
import com.dreamdisplays.platform.client.managers.ClientStateManager
import org.slf4j.LoggerFactory

/**
 * The server's overrides of this player's client settings ([ServerPolicy]), applied on top of the saved config
 * without ever writing to it. Everything reads the effective values from here; with no server policy (or after
 * leaving the server) they are just the player's own settings.
 */
object ServerOverrides {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Volatile
    var settings: ServerClientSettings = ServerClientSettings.NONE
        private set

    private var lastBytes: ByteArray? = null

    fun apply(packet: ServerPolicy) {
        synchronized(this) {
            if (lastBytes?.contentEquals(packet.policy) == true) return
            lastBytes = packet.policy
        }
        update(ServerClientSettings.fromWire(packet.policy))
    }

    /** Back to the player's own settings (disconnect). */
    fun reset() {
        synchronized(this) { lastBytes = null }
        if (settings != ServerClientSettings.NONE) update(ServerClientSettings.NONE)
    }

    private fun update(next: ServerClientSettings) {
        val previous = settings
        settings = next
        if (previous.serverWide != next.serverWide) {
            logger.info("Server client policy: {}", next.serverWide?.let { String(it.encode(), Charsets.UTF_8) } ?: "none")
        }
        if (previous.displaysEnabled != next.displaysEnabled) {
            logger.info("Displays {} by the server.", if (next.displaysEnabled) "enabled" else "disabled")
        }
        runCatching {
            DreamServices.registry.getOrNull(AudioAcousticsServices.ACOUSTICS)?.setGlobalQuality(audioAcoustics)
        }
        runCatching { ClientAudioModule.applyOutputSettings() }
        if (previous.logLevel != next.logLevel) {
            com.dreamdisplays.media.runtime.diagnostics.PlaybackDiagnostics.logLevel = next.logLevel.ordinal
            logger.info("Display log level: {} (set by the server).", next.logLevel.wire)
        }
        if (previous.verbose != next.verbose) VerboseLogging.set(next.verbose)
    }

    /** Displays may run at all (false while the server's kill switch is on). */
    val displaysEnabled: Boolean get() = settings.displaysEnabled

    /** Whether the display with [policy] may be loaded and played. */
    fun displayEnabled(policy: DisplayPolicy?): Boolean = settings.displayEnabled(policy)

    val maxActiveDisplays: Int get() = settings.maxActiveDisplays(ClientStateManager.config.maxActiveDisplays)

    val useHwAccel: Boolean get() = settings.hardwareDecoding(ClientStateManager.config.useHwAccel)

    val audioAcoustics: AcousticQuality
        get() {
            val own = ClientStateManager.config.audioAcoustics
            val name = settings.audioAcoustics(own.name)
            return AcousticQuality.entries.firstOrNull { it.name == name } ?: own
        }

    /** Binaural (headphones) rendering: the server's output profile when it sets one, else the player's. */
    val audioBinauralOutput: Boolean get() = settings.audioBinauralOutput(ClientStateManager.config.audioBinauralOutput)

    /** Loudness normalization: the server's when it sets it, else the player's. */
    val audioNormalization: Boolean get() = settings.audioNormalization(ClientStateManager.config.audioNormalization)

    /** The acoustics tier the display with [policy] sets for itself, or null (then [audioAcoustics] applies). */
    fun displayAcoustics(policy: DisplayPolicy?): AcousticQuality? =
        settings.displayAcoustics(policy)?.let { name -> AcousticQuality.entries.firstOrNull { it.name == name } }

    /**
     * The tier the display with [policy] gives its audio source for itself ([AcousticsTiers.sourceTier]), with the
     * viewer's own [choice] for it; null = it follows the global [audioAcoustics].
     */
    fun sourceAcoustics(policy: DisplayPolicy?, choice: AcousticQuality?): AcousticQuality? =
        AcousticsTiers.sourceTier(displayAcoustics(policy), settings.acousticsForced, choice)

    /** Effective acoustics tier for the display with [policy] and the viewer's [choice] for it ([AcousticsTiers]). */
    fun audioAcoustics(policy: DisplayPolicy?, choice: AcousticQuality?): AcousticQuality =
        sourceAcoustics(policy, choice) ?: audioAcoustics

    /** Whether the server sets the tier of the display with [policy], so the viewer's choice is ignored. */
    fun acousticsForced(policy: DisplayPolicy?): Boolean =
        AcousticsTiers.forced(displayAcoustics(policy), settings.acousticsForced)

    val unshadedDisplays: Boolean get() = settings.unshadedDisplays(ClientStateManager.config.unshadedDisplays)

    /** Display distance in chunks for a display with [policy] (0 = follow render distance). */
    fun displayDistanceChunks(policy: DisplayPolicy? = null): Int =
        settings.displayDistanceChunks(ClientStateManager.config.displayDistanceChunks, policy)
}

/**
 * Turns the mod's media / sync debug logging on or off at runtime (server `debug.verbose`). Uses log4j through
 * reflection, so it simply does nothing where log4j is not the logging backend.
 */
internal object VerboseLogging {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val packages = listOf(
        "com.dreamdisplays.media",
        "com.dreamdisplays.core.sync",
        "com.dreamdisplays.platform.client.displays",
        "com.dreamdisplays.platform.client.player",
    )

    fun set(enabled: Boolean) {
        runCatching {
            val levelClass = Class.forName("org.apache.logging.log4j.Level")
            val level = if (enabled) levelClass.getField("DEBUG").get(null) else null
            val setLevel = Class.forName("org.apache.logging.log4j.core.config.Configurator")
                .getMethod("setLevel", String::class.java, levelClass)
            packages.forEach { setLevel.invoke(null, it, level) }
        }.onSuccess {
            logger.info("Verbose display logging {} by the server.", if (enabled) "enabled" else "disabled")
        }.onFailure {
            logger.info("Verbose display logging is not available here: {}", it.toString())
        }
    }
}
