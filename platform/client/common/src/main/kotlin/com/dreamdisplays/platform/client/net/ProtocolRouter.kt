package com.dreamdisplays.platform.client.net

import com.dreamdisplays.core.protocol.common.HelloRefresh
import com.dreamdisplays.platform.client.capabilities.CapabilityNegotiationService
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.core.runtime.getOrNull
import com.dreamdisplays.platform.client.ui.ClientMessage
import com.dreamdisplays.shared.protocol.PacketDirection
import com.dreamdisplays.core.protocol.common.PacketRegistry
import com.dreamdisplays.core.protocol.common.packets.*
import com.dreamdisplays.platform.client.managers.ClientPacketManager
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory

/**
 * Client-side protocol negotiation: speaks v2 only after the server has proven v2 support by
 * answering the blind [ClientHello] with a [ServerHello].
 */
object ProtocolRouter {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** True once the server has proven v2 support by answering with a [ServerHello]. */
    @Volatile
    var v2Negotiated: Boolean = false
        private set

    @Volatile
    private var outdatedServerReported: Boolean = false

    /** When to say hello again so a server that forgot this client (plugin reload) picks it back up. */
    private val helloRefresh = HelloRefresh()

    /** Sends [packet] over v2; dropped until the server has negotiated v2. */
    fun send(packet: DreamPacket) {
        if (v2Negotiated) sendV2(packet)
        else logger.debug("Dropping {}: protocol v2 not negotiated.", packet::class.simpleName)
    }

    /** Sends [packet] over the v2 channel unconditionally; used for the blind hello bootstrap. */
    fun sendV2(packet: DreamPacket) {
        if (packet is ClientHello) synchronized(helloRefresh) { helloRefresh.onHelloSent(System.nanoTime()) }
        ClientPacketManager.send(V2Payload(PacketRegistry.encode(packet)))
    }

    /** Sends the v1 `version` probe; only v1-only servers answer it (see [onLegacyServerDetected]). */
    fun sendLegacyProbe(modVersion: String) {
        ClientPacketManager.send(LegacyProbe.Version(modVersion))
    }

    /** Decodes and dispatches v2 envelope bytes; the first [ServerHello] flips the v2 switch. */
    fun onV2Received(bytes: ByteArray) {
        val packet = runCatching { PacketRegistry.decode(bytes, PacketDirection.SERVER_TO_CLIENT) }
            .onFailure { logger.warn("Failed to decode v2 packet", it) }
            .getOrNull() ?: return
        val negotiatedBefore = v2Negotiated
        val answer = synchronized(helloRefresh) {
            helloRefresh.onPacket(System.nanoTime(), packet is ServerHello, negotiatedBefore)
        }
        if (packet is ServerHello && !v2Negotiated) {
            v2Negotiated = true
            logger.info("Protocol v2 negotiated (server protocol ${packet.protocolVersion}).")
        }
        ClientPacketManager.handle(packet)
        if (answer) {
            logger.info("Server greeted this client again (plugin reloaded?); answering with a hello.")
            sendHello()
        }
    }

    /**
     * Called every client tick: re-sends the hello when the server has gone quiet while displays are loaded,
     * which is what a server whose plugin was reloaded (and forgot this client) looks like. Bounded and backed off.
     */
    fun tickHelloRefresh(displaysLoaded: Boolean) {
        val due = synchronized(helloRefresh) { helloRefresh.tick(System.nanoTime(), v2Negotiated, displaysLoaded) }
        if (!due) return
        logger.info("No display traffic from the server while displays are loaded; re-sending hello.")
        sendHello()
    }

    private fun sendHello() {
        val hello = DreamServices.registry.getOrNull<CapabilityNegotiationService>()?.localCapabilities ?: return
        runCatching { sendV2(hello) }.onFailure { logger.debug("Could not re-send hello.", it) }
    }

    /** A v1 reply arrived: the server's `Dream Displays` is too old, so tell the player once. */
    fun onLegacyServerDetected() {
        if (v2Negotiated || outdatedServerReported) return
        outdatedServerReported = true
        logger.warn("Server runs a Dream Displays version that only supports protocol v1; displays are disabled.")
        val mc = Minecraft.getInstance()
        mc.execute {
            val message = ClientMessage.MESSAGE_OUTDATED_SERVER.component()
                .withStyle(ChatFormatting.RED)
            //? if >=26.2 {
            mc.gui.chatListener().handleSystemMessage(message, false)
            //?}
            //? if >=26.1 <26.2 {
            mc.gui.chat.addClientSystemMessage(message)
            //?}
            //? if <26.1 {
            mc.gui.chat.addMessage(message)
            //?}
        }
    }

    /** Resets negotiation state on disconnect. */
    fun reset() {
        v2Negotiated = false
        outdatedServerReported = false
        synchronized(helloRefresh) { helloRefresh.reset() }
    }
}
