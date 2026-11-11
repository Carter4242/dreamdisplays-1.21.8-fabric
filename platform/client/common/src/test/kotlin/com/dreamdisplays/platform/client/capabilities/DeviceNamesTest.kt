package com.dreamdisplays.platform.client.capabilities

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceNamesTest {
    @Test
    fun anOwnersNameInABluetoothDeviceIsTakenOut() {
        assertEquals(
            "javasound on \"Headphones (<name>'s AirPods Pro)\", 44100 Hz",
            DeviceNames.anonymize("javasound on \"Headphones (Alex's AirPods Pro)\", 44100 Hz"),
        )
        // macOS uses the typographic apostrophe
        assertEquals("openal on \"<name>'s AirPods\"", DeviceNames.anonymize("openal on \"Jo’s AirPods\""))
        assertEquals("Speakers (2- <name>'s Buds2)", DeviceNames.anonymize("Speakers (2- Mia-Li's Buds2)"))
    }

    @Test
    fun ordinaryDeviceNamesStayAsTheyAre() {
        val plain = "javasound on \"Speakers (Realtek(R) Audio)\", 44100 Hz, 16 bit, stereo"
        assertEquals(plain, DeviceNames.anonymize(plain))
        assertEquals("Port Headphones", DeviceNames.anonymize("Port Headphones"))
    }
}
