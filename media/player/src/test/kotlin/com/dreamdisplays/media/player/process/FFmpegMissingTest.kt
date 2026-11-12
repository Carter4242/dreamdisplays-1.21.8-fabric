package com.dreamdisplays.media.player.process

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FFmpegMissingTest {
    @Test
    fun `an installed FFmpeg is looked for on PATH, then in Homebrew, MacPorts and system folders`() {
        assertEquals(
            ["ffmpeg", "/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/opt/local/bin/ffmpeg", "/usr/bin/ffmpeg"],
            FFmpegBinary.SYSTEM_CANDIDATES,
        )
    }

    @Test
    fun `a Mac with no FFmpeg is told how to install one`() {
        assertTrue("brew install ffmpeg" in FFmpegBinary.missingMessage(mac = true))
        assertFalse("brew" in FFmpegBinary.missingMessage(mac = false))
    }
}
