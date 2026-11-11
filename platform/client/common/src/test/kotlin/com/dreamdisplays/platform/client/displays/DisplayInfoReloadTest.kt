package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayInfoReloadTest {
    @Test
    fun newScreenLoadsItsVideo() {
        assertTrue(DisplayInfoReload.needsReload(null, null, "https://a", ""))
    }

    @Test
    fun periodicDescriptionOfTheSameVideoDoesNotRestartIt() {
        assertFalse(DisplayInfoReload.needsReload("https://a", "", "https://a", ""))
        assertFalse(DisplayInfoReload.needsReload("https://a", "en", "https://a", "en"))
    }

    @Test
    fun screenRestoredFromCacheAdoptsThePacketLanguage() {
        // A distance restore loaded the video itself and has not seen a packet yet
        assertFalse(DisplayInfoReload.needsReload("https://a", null, "https://a", "de"))
    }

    @Test
    fun urlOrServerLanguageChangeReloads() {
        assertTrue(DisplayInfoReload.needsReload("https://a", "", "https://b", ""))
        assertTrue(DisplayInfoReload.needsReload("https://a", "en", "https://a", "de"))
    }

    @Test
    fun noVideoOnEitherSideIsNoChange() {
        assertFalse(DisplayInfoReload.needsReload(null, null, "", ""))
        assertFalse(DisplayInfoReload.needsReload("", "", "", ""))
        assertTrue(DisplayInfoReload.needsReload(null, null, "https://a", ""))
    }
}
