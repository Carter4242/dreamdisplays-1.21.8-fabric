package com.dreamdisplays.media.player.process

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HwAccelNoDeviceTest {
    @AfterTest
    fun reset() = HwAccelBackend.forgetNoDevice()

    @Test
    fun `a missing device is told apart from a stream the device refused`() {
        assertTrue(HwAccelBackend.looksLikeNoDevice("Device creation failed: -542398533.\nFailed to set value 'vaapi=hw'"))
        assertTrue(HwAccelBackend.looksLikeNoDevice("[VAAPI @ 0x1] Failed to initialise VAAPI connection: -1 (unknown libva error)."))
        assertTrue(HwAccelBackend.looksLikeNoDevice("Cannot load libcuda.so.1"))
        assertFalse(HwAccelBackend.looksLikeNoDevice("[h264 @ 0x1] Failed setup for format vaapi: hwaccel initialisation returned error."))
        assertFalse(HwAccelBackend.looksLikeNoDevice("Error while decoding stream #0:0: Invalid data found when processing input"))
    }

    @Test
    fun `only a device-only run that fails for want of a device counts as no device`() {
        assertTrue(HwAccelBackend.deviceMissing(1, "Device creation failed: -542398533."))
        assertFalse(HwAccelBackend.deviceMissing(0, "Device creation failed: -542398533."))
        assertFalse(HwAccelBackend.deviceMissing(1, "Error opening output files: Invalid argument"))
    }

    @Test
    fun `a stream's report alone does not turn a backend off, and nothing is checked without an FFmpeg`() {
        HwAccelBackend.confirmNoDeviceAsync(HwAccelBackend.VAAPI) { null }
        assertEquals(HwAccelBackend.VAAPI, HwAccelBackend.usable(HwAccelBackend.VAAPI))
        Thread.sleep(HwAccelBackend.CONFIRM_DELAY_MS + 500)
        assertEquals(HwAccelBackend.VAAPI, HwAccelBackend.usable(HwAccelBackend.VAAPI))
    }

    @Test
    fun `a backend without a device stays off for the session`() {
        assertEquals(HwAccelBackend.VAAPI, HwAccelBackend.usable(HwAccelBackend.VAAPI))
        HwAccelBackend.markNoDevice(HwAccelBackend.VAAPI)
        assertEquals(HwAccelBackend.NONE, HwAccelBackend.usable(HwAccelBackend.VAAPI))
        assertEquals(HwAccelBackend.D3D11VA, HwAccelBackend.usable(HwAccelBackend.D3D11VA))
    }
}
