package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.shared.capability.ServerFeature
import com.dreamdisplays.shared.playback.FullscreenAckAction
import com.dreamdisplays.shared.playback.FullscreenPrepareState
import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.shared.protocol.PacketDirection
import com.dreamdisplays.shared.protocol.ProtocolVersion
import com.dreamdisplays.core.protocol.common.PacketRegistry
import com.dreamdisplays.core.protocol.common.packets.*
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplays.platform.server.playback.ReadinessTracker
import com.dreamdisplays.platform.server.playback.PipPinManager
import com.dreamdisplays.platform.server.proxy.ProxyBridge
import com.dreamdisplays.platform.server.utils.WorldGuardRegions
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory

/** The single protocol-v2 plugin-message channel. */
const val V2_CHANNEL: String = "dreamdisplays:v2"

/**
 * Protocol-v2 networking for the Paper flavor: receives envelope frames on [V2_CHANNEL], answers
 * the [ClientHello] handshake, and sends v2 packets to negotiated players. Business logic is
 * shared with the other receivers through [DisplayActions].
 */
@PaperOnly
@NullMarked
object PaperV2Networking : PluginMessageListener {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val plugin: PaperServer by lazy { PaperServer.getInstance() }

    /** Encodes [packet] once and sends it to every non-null player in [players]. */
    fun send(players: List<Player?>, packet: DreamPacket) {
        val bytes = runCatching { PacketRegistry.encode(packet) }
            .onFailure { logger.warn("Failed to encode v2 packet", it) }
            .getOrNull() ?: return
        players.filterNotNull().forEach { player ->
            runCatching { player.sendPluginMessage(plugin, V2_CHANNEL, bytes) }
                .onFailure { logger.warn("Failed to send v2 packet to ${player.name}", it) }
            EmptyDisplayDescriptions.onSent(player.uniqueId, packet)
        }
    }

    /** The capability snapshot for [player], rebuilt from permissions and config. */
    fun buildServerHello(player: Player): ServerHello = ServerHello(
        protocolVersion = ProtocolVersion.CURRENT,
        isPremium = player.hasPermission(PaperServer.config.permissions.premium),
        isAdmin = player.hasPermission(PaperServer.config.permissions.deleteOthers),
        isReportingEnabled = PaperServer.config.settings.webhookUrl.isNotEmpty(),
        allowedFeatures = serverFeatureWires().let { wires ->
            if (ScreenShareActions.mayShare(player)) {
                wires + listOf(ServerFeature.SCREEN_SHARE.wire, ServerFeature.SCREEN_SHARE_HEARTBEAT.wire)
            } else wires
        },
        defaultVolume = PaperServer.config.settings.defaultVolume,
        maxDisplays = maxDisplaysFor(player.hasPermission(PaperServer.config.permissions.createBypass)),
    )

    /**
     * Greets every online player whose client listens on [V2_CHANNEL] but who has not said hello to this plugin
     * instance: after a plugin reload the tracker is empty while everyone is still connected. Clients that know
     * this greeting answer with their hello, which restores displays without a relog; older clients ignore it.
     */
    fun greetOnlinePlayers() {
        if (greetWaiting() == 0) return
        // A client answers a greeting only when it says hello after one it had sent more than a few seconds earlier
        // (it joined while no plugin listened, or just said hello to the previous instance): greet again until it does
        REGREET_DELAYS_TICKS.forEach { ticks -> com.dreamdisplays.platform.server.meta.Scheduler.runLater(ticks) { greetWaiting() } }
    }

    /** Greets the online players who listen on [V2_CHANNEL] but have not said hello to this instance; returns how many. */
    private fun greetWaiting(): Int {
        val waiting = plugin.server.onlinePlayers.filter {
            !V2PlayerTracker.isV2(it.uniqueId) && V2_CHANNEL in it.listeningPluginChannels
        }
        if (waiting.isEmpty()) return 0
        waiting.forEach { player -> send([player], buildServerHello(player).copy(greeting = true)) }
        logger.info("Greeted {} online player(s) so their clients re-handshake.", waiting.size)
        return waiting.size
    }

    /** Follow-up greetings after a reload: 12 s and 30 s, past the client's 10 s window for answering one. */
    private val REGREET_DELAYS_TICKS = listOf(12L * 20, 30L * 20)

    /**
     * Sends every connected client a fresh hello after the config was reloaded: the features it advertises (queue,
     * error reports, ...) follow the settings, and clients only learn them from a hello.
     */
    fun refreshServerHellos() {
        val players = plugin.server.onlinePlayers.filter { V2PlayerTracker.isV2(it.uniqueId) }
        players.forEach(::sendServerHello)
    }

    /** Sends [player] their hello as it stands now, remembered so a later change is sent too ([PaperHelloResync]). */
    private fun sendServerHello(player: Player) {
        val hello = buildServerHello(player)
        send([player], hello)
        PaperHelloResync.onSent(player, hello)
    }

    private fun serverFeatureWires(): List<String> = buildList {
        addAll(ServerFeature.playbackFeatureWires)
        if (WorldGuardRegions.isAvailable()) add(ServerFeature.REGION_ACCESS.wire)
        if (PaperServer.config.settings.queue.enabled) add(ServerFeature.QUEUE.wire)
        if (PaperServer.config.settings.clients.error_reports) add(ServerFeature.CLIENT_ERROR_REPORTS.wire)
        if (com.dreamdisplays.platform.server.karaoke.KaraokeManager.available) add(ServerFeature.KARAOKE.wire)
        add(ServerFeature.CLEAR_VIDEO.wire)
    }

    private fun maxDisplaysFor(hasBypass: Boolean): Int {
        val cap = PaperServer.config.settings.maxDisplaysPerPlayer
        return if (hasBypass || cap <= 0) -1 else cap
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != V2_CHANNEL) return
        // As early as possible: karaoke clock pongs are timed by it
        val receivedNanos = System.nanoTime()
        val packet = runCatching { PacketRegistry.decode(message, PacketDirection.CLIENT_TO_SERVER) }
            .onFailure { logDecodeFailure(player, it) }
            .getOrNull() ?: return

        when (packet) {
            is ClientHello -> handleHello(player, packet)
            is RequestSync -> DisplayActions.requestSync(player, packet.id, packet.knownMediaGen)
            is ReportDuration -> DisplayActions.reportDuration(
                player, packet.id, packet.durationMs, packet.url, packet.mediaGen, packet.observedEnd,
            )
            is ViewerReport -> com.dreamdisplays.platform.server.policy.ClientDiagnostics.onReport(player, packet)
            is QueueAction -> QueueActions.onAction(player, packet)
            is QueuePreload -> QueueActions.onPreload(player, packet)
            is ClientErrorReport -> com.dreamdisplays.platform.server.policy.ClientErrorReports.onReport(player, packet)
            is ClientEnvironment -> com.dreamdisplays.platform.server.policy.ClientErrorReports.onEnvironment(player, packet)
            is DisplayDelete -> DisplayActions.delete(player, packet.id)
            is ReportDisplay -> DisplayManager.report(packet.id, player)
            is SetVideo -> if (packet.clear) {
                DisplayActions.clearVideo(player, packet.id)
            } else {
                DisplayActions.setVideo(player, packet.id, packet.url, packet.lang)
            }
            is SetLocked -> DisplayActions.setAccess(player, packet.id, packet.accessLevel())
            is SetMode -> DisplayActions.setMode(
                player,
                packet.id,
                PlaybackMode.fromWire(packet.mode),
                packet.positionMs,
                packet.mediaGen,
            )

            is PlaybackCommand -> PlaybackAction.fromWire(packet.action)?.let {
                DisplayActions.playbackCommand(player, packet.id, it, packet.positionMs, packet.rate, packet.mediaGen)
            }

            is SetDisplaysEnabled -> PlayerManager.setDisplaysEnabled(player, packet.enabled)
            is KaraokeLatencyReport -> com.dreamdisplays.platform.server.karaoke.KaraokeMeasurements.onLatencyReport(
                player.uniqueId, packet, System.currentTimeMillis(),
            )

            is KaraokeClockPong -> com.dreamdisplays.platform.server.karaoke.KaraokeMeasurements.onPong(
                player.uniqueId, packet, receivedNanos,
            )

            is KaraokeMicMarkers -> com.dreamdisplays.platform.server.karaoke.KaraokeMeasurements.onMarkers(
                player.uniqueId, packet, receivedNanos,
            )

            is KaraokeOffsetReport -> com.dreamdisplays.platform.server.karaoke.KaraokeManager.onOffsetReport(player, packet)
            is FullscreenAck -> FullscreenBroadcastManager.handleAck(
                packet.sessionId, player.uniqueId, FullscreenAckAction.fromWire(packet.action),
            )

            is DisplayReadiness -> ReadinessTracker.onReport(player.uniqueId, packet)
            is FullscreenPrepareStatus -> FullscreenBroadcastManager.onPrepareStatus(
                player.uniqueId, packet.prepareId, packet.displayId, FullscreenPrepareState.fromWire(packet.state),
            )

            is ScreenShareRequest -> if (packet.heartbeat) {
                ScreenShareActions.onHeartbeat(player, packet.id, packet.nonce)
            } else {
                ScreenShareActions.onRequest(player, packet.id, packet.start, packet.nonce)
            }

            is PipPin -> if (packet.pinned) {
                PipPinManager.pin(player.uniqueId, packet.id)
            } else {
                PipPinManager.unpin(player.uniqueId, packet.id)
            }

            else -> logger.debug("Ignoring non-serverbound v2 packet {}.", packet::class.simpleName)
        }
    }

    /** When each player's undecodable packet was last logged in full; one a minute each, the rest at debug. */
    private val decodeFailureLoggedAt = java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long>()
    private const val DECODE_FAILURE_LOG_MS = 60_000L

    private fun logDecodeFailure(player: Player, error: Throwable) {
        val now = System.currentTimeMillis()
        val last = decodeFailureLoggedAt[player.uniqueId]
        if (last != null && now - last < DECODE_FAILURE_LOG_MS) {
            logger.debug("Failed to decode v2 packet from {}: {}", player.name, error.toString())
            return
        }
        if (decodeFailureLoggedAt.size > MAX_DECODE_FAILURE_PLAYERS) decodeFailureLoggedAt.clear()
        decodeFailureLoggedAt[player.uniqueId] = now
        logger.warn("Failed to decode v2 packet from ${player.name} (further ones logged at most once a minute)", error)
    }

    private const val MAX_DECODE_FAILURE_PLAYERS = 1_000

    private fun handleHello(player: Player, hello: ClientHello) {
        if (V2PlayerTracker.isV2(player.uniqueId)) return
        V2PlayerTracker.markV2(player.uniqueId, hello)
        sendServerHello(player)
        // Before the displays: the policy decides how their players are opened
        com.dreamdisplays.platform.server.policy.ClientDiagnostics.sendPolicy(player)
        DisplayActions.recordVersionAndCheckUpdates(player, hello.modVersion, hello.buildCommit)
        DisplayActions.sendAllDisplays(player)
        FullscreenBroadcastManager.onPlayerJoin(player.uniqueId)
        PipPinManager.onPlayerJoin(player.uniqueId)
        ProxyBridge.onPlayerReady(player)
    }
}
