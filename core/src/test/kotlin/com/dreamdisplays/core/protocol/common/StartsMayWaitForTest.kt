package com.dreamdisplays.core.protocol.common

import com.dreamdisplays.core.protocol.common.packets.ClientHello
import com.dreamdisplays.shared.capability.ClientFeature
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synced starts wait only for clients that can also report a display they won't hold ready. */
class StartsMayWaitForTest {
    @Test
    fun readinessWithUnholdableIsWaitedFor() {
        // Protocol 12 clients (038a5ab3 on) advertise it
        val hello = ClientHello(protocolVersion = 12, supportsReadiness = true, features = listOf(ClientFeature.READINESS_UNHOLDABLE.wire))
        assertTrue(hello.startsMayWaitFor())
    }

    @Test
    fun readinessWithoutUnholdableIsNot() {
        // Protocol-0 builds (7522afe5 era) report readiness but stay not-ready on media they can't hold
        assertFalse(ClientHello(supportsReadiness = true).startsMayWaitFor())
        assertFalse(ClientHello(supportsReadiness = true, features = listOf(ClientFeature.MEDIA_GEN.wire)).startsMayWaitFor())
    }

    @Test
    fun noReadinessIsNot() {
        assertFalse(ClientHello().startsMayWaitFor())
        assertFalse(ClientHello(features = listOf(ClientFeature.READINESS_UNHOLDABLE.wire)).startsMayWaitFor())
    }
}
