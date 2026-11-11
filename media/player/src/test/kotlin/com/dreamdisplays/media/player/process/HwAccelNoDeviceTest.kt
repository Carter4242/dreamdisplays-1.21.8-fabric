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
    fun `a backend without a device stays off for the session`() {
        assertEquals(HwAccelBackend.VAAPI, HwAccelBackend.usable(HwAccelBackend.VAAPI))
        HwAccelBackend.markNoDevice(HwAccelBackend.VAAPI)
        assertEquals(HwAccelBackend.NONE, HwAccelBackend.usable(HwAccelBackend.VAAPI))
        assertEquals(HwAccelBackend.D3D11VA, HwAccelBackend.usable(HwAccelBackend.D3D11VA))
    }
}
