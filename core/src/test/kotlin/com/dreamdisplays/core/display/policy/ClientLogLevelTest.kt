package com.dreamdisplays.core.display.policy

import kotlin.test.Test
import kotlin.test.assertEquals

class ClientLogLevelTest {
    private fun settings(debug: DisplayPolicy.Debug?) = ServerClientSettings(DisplayPolicy(debug = debug))

    @Test
    fun `quiet by default with no drift lines`() {
        assertEquals(ClientLogLevel.QUIET, ServerClientSettings.NONE.logLevel)
        assertEquals(0L, ServerClientSettings.NONE.syncLogIntervalMs)
        assertEquals(false, ServerClientSettings.NONE.verbose)
    }

    @Test
    fun `levels drive verbosity and drift line interval unless set explicitly`() {
        assertEquals(1_000L, settings(DisplayPolicy.Debug(logLevel = "ultra")).syncLogIntervalMs)
        assertEquals(true, settings(DisplayPolicy.Debug(logLevel = "ultra")).verbose)
        assertEquals(10_000L, settings(DisplayPolicy.Debug(logLevel = "VERBOSE")).syncLogIntervalMs)
        assertEquals(0L, settings(DisplayPolicy.Debug(logLevel = "normal")).syncLogIntervalMs)
        assertEquals(false, settings(DisplayPolicy.Debug(logLevel = "normal")).verbose)
        assertEquals(5_000L, settings(DisplayPolicy.Debug(logLevel = "ultra", syncLogIntervalSeconds = 5)).syncLogIntervalMs)
        // The old switch still works
        assertEquals(ClientLogLevel.VERBOSE, settings(DisplayPolicy.Debug(verbose = true)).logLevel)
        assertEquals(ClientLogLevel.QUIET, settings(DisplayPolicy.Debug(logLevel = "bogus")).logLevel)
    }

    @Test
    fun `level travels in the policy and old clients ignore it`() {
        val policy = DisplayPolicy(debug = DisplayPolicy.Debug(logLevel = "ultra"))
        assertEquals(ClientLogLevel.ULTRA, ServerClientSettings.fromWire(policy.encode()).logLevel)
        assertEquals("ultra", PolicyPaths.find("debug.log_level")?.kind?.parse("ultra"))
    }
}
