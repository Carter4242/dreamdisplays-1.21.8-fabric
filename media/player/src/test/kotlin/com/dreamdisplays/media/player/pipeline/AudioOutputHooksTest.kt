package com.dreamdisplays.media.player.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioOutputHooksTest {
    private val mixers = listOf(
        "Primary Sound Driver" to "primary",
        "Speakers (Realtek(R) Audio)" to "speakers",
        "Headphones (Realtek(R) Audio)" to "headphones",
        "Headset Earphone (HyperX Cloud" to "hyperx-truncated",
        "Port Speakers (Realtek(R) Audio" to "port",
    )

    @Test
    fun matchesOpenAlNameToJavaName() {
        assertEquals("headphones", AudioOutputHooks.bestMatch("OpenAL Soft on Headphones (Realtek(R) Audio)", mixers))
    }

    @Test
    fun matchesJavaNameCutShort() {
        assertEquals(
            "hyperx-truncated",
            AudioOutputHooks.bestMatch("OpenAL Soft on Headset Earphone (HyperX Cloud Alpha Wireless)", mixers),
        )
    }

    @Test
    fun noMatchMeansDefault() {
        assertNull(AudioOutputHooks.bestMatch("OpenAL Soft on Some USB DAC", mixers))
        assertNull(AudioOutputHooks.bestMatch("", mixers))
    }
}
