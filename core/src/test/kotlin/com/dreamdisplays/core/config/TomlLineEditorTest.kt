package com.dreamdisplays.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TomlLineEditorTest {
    private val config = """
        |# Top comment
        |[display]
        |# Display size limits
        |min_width = 1   # smallest
        |max_width = 32
        |
        |seek_hold_seconds = 3
        |
        |[policy.sync]
        |# Playback sync
        |# dead_band_ms = 40         # drift below this is left alone
        |# max_rate_pct = 1.0
        |
        |[policy.audio]
        |# radius_blocks = 96        # sound fades to silence at this distance
        |
        |[custom_media]
        |enabled = true
        |""".trimMargin()

    @Test
    fun `replaces an active value and keeps its comment`() {
        val out = TomlLineEditor.set(config, "display", "min_width", "2")
        assertEquals(config.replace("min_width = 1   # smallest", "min_width = 2   # smallest"), out)
    }

    @Test
    fun `uncomments a documented example in the right table`() {
        val out = TomlLineEditor.set(config, "policy.sync", "dead_band_ms", "30")
        assertEquals(
            config.replace("# dead_band_ms = 40         # drift", "dead_band_ms = 30         # drift"),
            out,
        )
        val longer = TomlLineEditor.set(config, "policy.audio", "radius_blocks", "48.5")
        assertEquals(
            config.replace("# radius_blocks = 96        # sound", "radius_blocks = 48.5      # sound"),
            longer,
        )
    }

    @Test
    fun `adds a missing key at the end of its table`() {
        val out = TomlLineEditor.set(config, "display", "resume_hold_seconds", "0")
        assertEquals(config.replace("seek_hold_seconds = 3\n", "seek_hold_seconds = 3\nresume_hold_seconds = 0\n"), out)
    }

    @Test
    fun `adds a key to a table that only has comments`() {
        val text = "[a]\n# nothing yet\n\n[b]\nx = 1\n"
        assertEquals("[a]\n# nothing yet\ny = 2\n\n[b]\nx = 1\n", TomlLineEditor.set(text, "a", "y", "2"))
    }

    @Test
    fun `a new key goes after the last key, not after comments introducing the next table`() {
        val text = "[display]\na = 1\n\n# About the policy tables\n[policy.sync]\n"
        assertEquals(
            "[display]\na = 1\nb = 2\n\n# About the policy tables\n[policy.sync]\n",
            TomlLineEditor.set(text, "display", "b", "2"),
        )
    }

    @Test
    fun `appends a missing table`() {
        val out = TomlLineEditor.set(config, "policy.quality", "max_height", "720")
        assertEquals(config.trimEnd() + "\n\n[policy.quality]\nmax_height = 720\n", out)
    }

    @Test
    fun `does not touch the same key in another table`() {
        val out = TomlLineEditor.set(config, "custom_media", "enabled", "false")
        assertEquals(config.replace("[custom_media]\nenabled = true", "[custom_media]\nenabled = false"), out)
        val sync = TomlLineEditor.set(config, "policy.sync", "enabled", "false")
        assertEquals(config.replace("# max_rate_pct = 1.0\n", "# max_rate_pct = 1.0\nenabled = false\n"), sync)
    }

    @Test
    fun `unset comments the active line out and is a no-op otherwise`() {
        val set = TomlLineEditor.set(config, "policy.sync", "dead_band_ms", "30")
        val unset = TomlLineEditor.unset(set, "policy.sync", "dead_band_ms")
        assertEquals(set.replace("dead_band_ms = 30", "# dead_band_ms = 30"), unset)
        assertEquals(config, TomlLineEditor.unset(config, "policy.sync", "dead_band_ms"))
        assertEquals(config, TomlLineEditor.unset(config, "nope", "x"))
    }

    @Test
    fun `keeps CRLF line endings`() {
        val crlf = config.replace("\n", "\r\n")
        val out = TomlLineEditor.set(crlf, "display", "max_width", "40")
        assertEquals(crlf.replace("max_width = 32", "max_width = 40"), out)
    }

    @Test
    fun `hash inside a string is not a comment`() {
        val text = "[display]\nparticles_color = \"#00FFFF\" # colour\n"
        assertEquals(
            "[display]\nparticles_color = \"#FF0000\" # colour\n",
            TomlLineEditor.set(text, "display", "particles_color", TomlLineEditor.encode("#FF0000")),
        )
    }

    @Test
    fun `encodes values as TOML`() {
        assertEquals("true", TomlLineEditor.encode(true))
        assertEquals("3", TomlLineEditor.encode(3))
        assertEquals("96.0", TomlLineEditor.encode(96.0))
        assertEquals("0.25", TomlLineEditor.encode(0.25))
        assertEquals("\"syn\\\"ced\"", TomlLineEditor.encode("syn\"ced"))
        assertFailsWith<IllegalArgumentException> { TomlLineEditor.encode(listOf(1)) }
    }
}
