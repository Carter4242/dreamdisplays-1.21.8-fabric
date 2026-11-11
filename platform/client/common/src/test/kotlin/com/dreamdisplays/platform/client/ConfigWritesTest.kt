package com.dreamdisplays.platform.client

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigWritesTest {
    private val defaults = """
        mute-on-alt-tab: false
        displays-disabled-servers: ''
        new-display-volume: 0.25
        max-active-displays: 0
    """.trimIndent() + "\n"

    @Test
    fun `a config locked at start keeps the player's settings, and the one changed since`() {
        val onDisk = listOf(
            "mute-on-alt-tab: true",
            "displays-disabled-servers: 'mc.example.org'",
            "new-display-volume: 0.6",
            "max-active-displays: 4",
        )
        // Changed in this session while the file was unreadable: the volume
        val current = defaults.replace("new-display-volume: 0.25", "new-display-volume: 0.4")
        assertEquals(
            listOf(
                "mute-on-alt-tab: true",
                "displays-disabled-servers: 'mc.example.org'",
                "new-display-volume: 0.4",
                "max-active-displays: 4",
            ),
            ConfigWrites.merge(defaults, current, onDisk).lines().filter { it.isNotEmpty() },
        )
    }

    @Test
    fun `a setting the file lacks keeps the value in memory`() {
        val merged = ConfigWrites.merge(defaults, defaults, listOf("mute-on-alt-tab: true"))
        assertEquals(
            listOf("mute-on-alt-tab: true", "displays-disabled-servers: ''", "new-display-volume: 0.25", "max-active-displays: 0"),
            merged.lines().filter { it.isNotEmpty() },
        )
    }
}
