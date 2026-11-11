package com.dreamdisplays.core.protocol.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientBuildTest {
    @Test
    fun ofSanitizesAndTreatsEmptyAsOlder() {
        assertNull(ClientBuild.of(""))
        assertNull(ClientBuild.of(null))
        assertEquals("1a2b3c4d-dev", ClientBuild.of("1A2B3C4D-dev"))
        assertEquals("1a2b3c4d", ClientBuild.of("1a2b§3c4d\n"))
    }

    @Test
    fun differsOnlyWhenLatestIsSet() {
        assertFalse(ClientBuild.differs("1a2b3c4d", ""))
        assertFalse(ClientBuild.differs("", ""))
        assertFalse(ClientBuild.differs("1a2b3c4d", "1a2b3c4d"))
        // A full SHA in the config matches the 8-digit id clients send
        assertFalse(ClientBuild.differs("1a2b3c4d", "1A2B3C4D9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b"))
        assertTrue(ClientBuild.differs("", "1a2b3c4d"))
        // A local build of the same commit matches; another commit does not
        assertFalse(ClientBuild.differs("1a2b3c4d-dev", "1a2b3c4d"))
        assertFalse(ClientBuild.differs("1a2b3c4d-dev-dirty", "1a2b3c4d-dev"))
        assertTrue(ClientBuild.differs("deadbeef", "1a2b3c4d"))
        assertTrue(ClientBuild.differs("deadbeef-dev", "1a2b3c4d-dev"))
    }

    @Test
    fun expectedFallsBackToTheServerBuild() {
        assertEquals("1a2b3c4d", ClientBuild.expected("1A2B3C4D9e8f", "deadbeef-dev"))
        assertEquals("deadbeef-dev", ClientBuild.expected("", "deadbeef-dev"))
        assertEquals("", ClientBuild.expected("", "unknown"))
        assertEquals("", ClientBuild.expected("", null))
        assertFalse(ClientBuild.differs("deadbeef", ClientBuild.expected("", "deadbeef-dev-dirty")))
        assertFalse(ClientBuild.differs("1a2b3c4d", ClientBuild.expected("", "unknown")))
    }

    @Test
    fun latestPattern() {
        assertTrue(ClientBuild.LATEST_PATTERN.matches("1a2b3c4d"))
        assertTrue(ClientBuild.LATEST_PATTERN.matches("1a2b3c4d-dev-dirty"))
        assertFalse(ClientBuild.LATEST_PATTERN.matches("1a2b"))
        assertFalse(ClientBuild.LATEST_PATTERN.matches("hello world"))
    }
}
