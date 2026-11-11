package com.dreamdisplays.media.source.diagnostics

import com.dreamdisplays.media.runtime.diagnostics.PlaybackDiagnostics
import com.dreamdisplays.media.runtime.diagnostics.ResolveTrail
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolveTrailTest {
    @BeforeTest
    fun enable() {
        PlaybackDiagnostics.listener = {}
    }

    @AfterTest
    fun disable() {
        PlaybackDiagnostics.listener = null
    }

    @Test
    fun `steps are shared by every form of a youtube link`() {
        val id = "trailTest01"
        ResolveTrail.record("https://www.youtube.com/watch?v=$id", "newpipe", ok = false, error = "no quality ladder")
        ResolveTrail.record("https://youtu.be/$id", "yt-dlp binary", "visionos", ok = true)
        val steps = ResolveTrail.steps("https://www.youtube.com/watch?v=$id&t=30")
        assertEquals(listOf("newpipe", "yt-dlp binary"), steps.map { it.source })
        assertEquals("visionos", steps[1].client)
    }

    @Test
    fun `nothing is recorded while no reporter listens`() {
        PlaybackDiagnostics.listener = null
        ResolveTrail.record("https://example.com/off.mp4", "direct", ok = false, error = "x")
        assertTrue(ResolveTrail.steps("https://example.com/off.mp4").isEmpty())
    }

    @Test
    fun `short error keeps the telling line`() {
        val python39 = "Exited with code 1: Traceback (most recent call last):\n  File \"yt-dlp.pyz/__main__.py\"\n" +
            "ImportError: You are using an unsupported version of Python. Only Python versions 3.10 and above are supported by yt-dlp."
        assertTrue(ResolveTrail.shortError(python39).contains("ImportError: You are using an unsupported version of Python"))
        assertEquals("ERROR: [youtube] x: Sign in to confirm you're not a bot", ResolveTrail.shortError("ERROR: [youtube] x: Sign in to confirm you're not a bot"))
    }
}
