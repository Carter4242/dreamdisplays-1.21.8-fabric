@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.proxy

import com.dreamdisplays.core.protocol.proxy.packets.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyRoundTripTest {
    private fun roundTrip(packet: ProxyPacket) {
        assertEquals(packet, ProxyPacketRegistry.decode(ProxyPacketRegistry.encode(packet)))
    }

    @Test
    fun handshakePackets() {
        roundTrip(BackendHello(pluginVersion = "1.9.0", mcVersion = "1.21.11", platform = "paper"))
        roundTrip(
            ProxyWelcome(
                yourServerName = "lobby",
                allServerNames = ["lobby", "survival"],
                proxyNowMs = 1_700_000_000_000
            )
        )
        roundTrip(ClockProbe(backendSendMs = 1_700_000_000_000))
        roundTrip(
            ClockReply(
                backendSendMs = 1_700_000_000_000,
                proxyRecvMs = 1_700_000_000_050,
                proxySendMs = 1_700_000_000_055
            )
        )
    }

    @Test
    fun networkFullscreenPackets() {
        roundTrip(
            StartNetworkFullscreen(
                scope = "global", ownerId = "01234567-89ab-cdef-0123-456789abcdef",
                url = "https://youtu.be/abc?x=привет 世界", mode = 1, forced = true, volume = 0.75f,
                loop = true, quality = "1080", title = "Season finale", targetsRaw = "@a,%vip",
            )
        )
        roundTrip(
            ApplyFullscreen(
                sessionId = "a1b2c3d4", anchorProxyMs = 1_700_000_001_500,
                sharedDisplayId = "11111111-2222-3333-4444-555555555555",
                ownerId = "01234567-89ab-cdef-0123-456789abcdef", url = "https://youtu.be/abc",
                mode = 0, forced = false, volume = -1f, loop = false, quality = "", title = "",
                targetsRaw = "",
            )
        )
        roundTrip(StopNetworkFullscreen(sessionId = "a1b2c3d4"))
        roundTrip(NetworkFullscreenAck(sessionId = "a1b2c3d4", reach = 12, pending = false))
        roundTrip(ListNetworkSessions())
        roundTrip(
            NetworkSessionList(
                [
                    NetworkSessionInfo(
                        sessionId = "a1b2c3d4",
                        scope = "global",
                        url = "https://youtu.be/abc",
                        totalReach = 12
                    ),
                    NetworkSessionInfo(),
                ]
            )
        )
        roundTrip(PlayerReady(playerId = "01234567-89ab-cdef-0123-456789abcdef"))
        roundTrip(
            ReplayForPlayer(
                playerId = "01234567-89ab-cdef-0123-456789abcdef",
                sessionIds = ["a1b2c3d4", "e5f6a7b8"],
                minimizedSessionIds = ["e5f6a7b8"]
            )
        )
        roundTrip(
            PlayerFullscreenMinimized(
                sessionId = "a1b2c3d4",
                playerId = "01234567-89ab-cdef-0123-456789abcdef",
                minimized = true
            )
        )
    }

    @Test
    fun transferAndIndexPackets() {
        roundTrip(
            PlayerTransferring(
                playerId = "01234567-89ab-cdef-0123-456789abcdef",
                from = "lobby",
                to = "survival"
            )
        )
        roundTrip(PlayerLeftNetwork(playerId = "01234567-89ab-cdef-0123-456789abcdef", server = "lobby"))
        roundTrip(
            BackendDisplayIndex(
                [
                    DisplayIndexEntry(id = "11111111-2222-3333-4444-555555555555", url = "https://youtu.be/abc"),
                    DisplayIndexEntry(),
                ]
            )
        )
        roundTrip(ResolveDisplayToken(requestId = "req12345", token = "1234abcd", originServer = "survival"))
        roundTrip(DisplayTokenResolved(requestId = "req12345", originServer = "survival", url = "https://youtu.be/abc"))
    }

    @Test
    fun defaultsRoundTrip() {
        roundTrip(BackendHello())
        roundTrip(ProxyWelcome())
        roundTrip(ClockProbe())
        roundTrip(ClockReply())
        roundTrip(StartNetworkFullscreen())
        roundTrip(ApplyFullscreen())
        roundTrip(StopNetworkFullscreen())
        roundTrip(NetworkFullscreenAck())
        roundTrip(ListNetworkSessions())
        roundTrip(NetworkSessionList())
        roundTrip(PlayerReady())
        roundTrip(ReplayForPlayer())
        roundTrip(PlayerTransferring())
        roundTrip(PlayerLeftNetwork())
        roundTrip(ResolveDisplayToken())
        roundTrip(DisplayTokenResolved())
        roundTrip(PlayerFullscreenMinimized())
        roundTrip(BackendDisplayIndex())
    }

    @Test
    fun unknownTypeIdIsIgnored() {
        val proto = ProtoBuf { }
        val bytes = proto.encodeToByteArray(ProxyEnvelope.serializer(), ProxyEnvelope(9999, byteArrayOf(1, 2, 3)))
        assertNull(ProxyPacketRegistry.decode(bytes))
    }

    @Test
    fun retiredWatchPartyPacketsAreDropped() {
        assertEquals(setOf(15, 16, 17, 18, 19), ProxyPacketType.RETIRED_IDS)
        assertTrue(ProxyPacketType.entries.none { it.id in ProxyPacketType.RETIRED_IDS })
        // What an older peer still sends: field 1 (the party id) set
        val payload = byteArrayOf((1 shl 3 or 2).toByte(), 8) + "p1p2p3p4".toByteArray()
        val proto = ProtoBuf { }
        for (type in ProxyPacketType.RETIRED_IDS) {
            val frame = proto.encodeToByteArray(ProxyEnvelope.serializer(), ProxyEnvelope(type, payload))
            assertNull(ProxyPacketRegistry.decode(frame))
            assertNull(ProxyPacketRegistry.decode(frame, ProxyPacketDirection.PROXY_TO_BACKEND))
            assertNull(ProxyPacketRegistry.decode(frame, ProxyPacketDirection.BACKEND_TO_PROXY))
        }
    }

    @Test
    fun directionsMatchRegistry() {
        assertEquals(ProxyPacketDirection.BACKEND_TO_PROXY, ProxyPacketRegistry.directionOf(BackendHello()))
        assertEquals(ProxyPacketDirection.PROXY_TO_BACKEND, ProxyPacketRegistry.directionOf(ProxyWelcome()))
        assertEquals(ProxyPacketDirection.BIDIRECTIONAL, ProxyPacketRegistry.directionOf(StopNetworkFullscreen()))
    }

    @Test
    fun decodeRejectsWrongDirection() {
        val bytes = ProxyPacketRegistry.encode(ProxyWelcome(yourServerName = "lobby"))
        assertEquals(
            ProxyWelcome(yourServerName = "lobby"),
            ProxyPacketRegistry.decode(bytes, ProxyPacketDirection.PROXY_TO_BACKEND),
        )
        runCatching { ProxyPacketRegistry.decode(bytes, ProxyPacketDirection.BACKEND_TO_PROXY) }
            .onSuccess { throw AssertionError("Expected decode to reject a PROXY_TO_BACKEND packet as BACKEND_TO_PROXY.") }
    }
}
