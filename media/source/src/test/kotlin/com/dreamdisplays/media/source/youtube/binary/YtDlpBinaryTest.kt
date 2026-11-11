package com.dreamdisplays.media.source.youtube.binary

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YtDlpBinaryTest {
    @Test
    fun releaseTagIsReadFromTheLatestRedirect() {
        assertEquals("2026.10.26", YtDlpBinary.releaseTag("https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26"))
        assertNull(YtDlpBinary.releaseTag("https://github.com/yt-dlp/yt-dlp/releases"))
        assertNull(YtDlpBinary.releaseTag("https://github.com/yt-dlp/yt-dlp/releases/tag/../../evil"))
        assertNull(YtDlpBinary.releaseTag(null))
    }

    @Test
    fun pythonOlderThan311IsRejected() {
        assertFalse(YtDlpBinary.pythonVersionSupported("Python 3.9.6"))
        assertFalse(YtDlpBinary.pythonVersionSupported("Python 3.10.12"))
        assertFalse(YtDlpBinary.pythonVersionSupported("Python 2.7.18"))
        assertFalse(YtDlpBinary.pythonVersionSupported("command not found"))
    }

    @Test
    fun python311AndNewerIsAccepted() {
        assertTrue(YtDlpBinary.pythonVersionSupported("Python 3.11.0"))
        assertTrue(YtDlpBinary.pythonVersionSupported("Python 3.13.11\r\n"))
        assertTrue(YtDlpBinary.pythonVersionSupported("Python 4.0.0"))
    }

    @Test
    fun interpreterFailuresAreToldApartFromVideoFailures() {
        assertTrue(
            YtDlpBinary.looksLikeInterpreterFailure(
                "Traceback (most recent call last):\n  File \"<frozen runpy>\", line 198, in _run_module_as_main\n" +
                    "ImportError: You are using an unsupported version of Python. Only Python versions 3.11 and above " +
                    "are supported by yt-dlp",
            ),
        )
        assertTrue(YtDlpBinary.looksLikeInterpreterFailure("/usr/bin/python3: can't find '__main__' module in 'yt-dlp.pyz'"))
        assertFalse(YtDlpBinary.looksLikeInterpreterFailure("ERROR: [youtube] abc: Video unavailable"))
        assertFalse(YtDlpBinary.looksLikeInterpreterFailure(null))
    }

    @Test
    fun zipappCommandIsRecognized() {
        assertTrue(YtDlpBinary.isZipapp(["python3", "libs/yt-dlp/yt-dlp.pyz"]))
        assertFalse(YtDlpBinary.isZipapp(["libs/yt-dlp/yt-dlp_macos"]))
    }

    private val system = listOf("yt-dlp", "/usr/bin/yt-dlp")

    @Test
    fun aPresentBundledCopyBeatsAStaleSystemInstall() {
        val pick = YtDlpBinary.pickBinary(null, "libs/yt-dlp/yt-dlp", system, runs = { true }) { error("no download") }
        assertEquals("libs/yt-dlp/yt-dlp", pick)
    }

    @Test
    fun aBundledCopyThatCannotRunHereGivesWayToASystemInstall() {
        val bundled = "libs/yt-dlp/yt-dlp_linux"
        assertEquals(
            "/usr/bin/yt-dlp",
            YtDlpBinary.pickBinary(null, bundled, system, runs = { it != "yt-dlp" }, answers = { it != bundled }) { bundled },
        )
        // Also right after downloading it
        assertEquals(
            "/usr/bin/yt-dlp",
            YtDlpBinary.pickBinary(null, bundled, system, runs = { it == "/usr/bin/yt-dlp" }, answers = { it != bundled }) { bundled },
        )
        // No system install: kept, as before
        assertEquals(bundled, YtDlpBinary.pickBinary(null, bundled, system, runs = { it == bundled }, answers = { false }) { bundled })
    }

    @Test
    fun aMissingBundledCopyIsDownloadedEvenWithASystemInstall() {
        var downloaded = false
        val pick = YtDlpBinary.pickBinary(null, "libs/yt-dlp/yt-dlp", system, runs = { it in system || downloaded }) {
            downloaded = true
            "libs/yt-dlp/yt-dlp"
        }
        assertEquals("libs/yt-dlp/yt-dlp", pick)
        assertTrue(downloaded)
    }

    @Test
    fun theSystemInstallIsOnlyTheFallbackWhenTheDownloadFails() {
        val pick = YtDlpBinary.pickBinary(null, "libs/yt-dlp/yt-dlp", system, runs = { it == "/usr/bin/yt-dlp" }) {
            throw IOException("offline")
        }
        assertEquals("/usr/bin/yt-dlp", pick)
        assertFailsWith<IOException> {
            YtDlpBinary.pickBinary(null, "libs/yt-dlp/yt-dlp", system, runs = { false }) { throw IOException("offline") }
        }
    }

    @Test
    fun aRunnableOverrideWinsAndABrokenOneIsSkipped() {
        assertEquals("/opt/yt-dlp", YtDlpBinary.pickBinary("/opt/yt-dlp", "b", system, runs = { true }) { "b" })
        assertEquals("b", YtDlpBinary.pickBinary("/opt/yt-dlp", "b", system, runs = { it == "b" }) { "b" })
    }

    @Test
    fun aServerPreferringTheSystemInstallGetsItAndTheBundledCopyOtherwise() {
        assertEquals("/usr/bin/yt-dlp", YtDlpBinary.pickBinary(null, "b", system, runs = { it != "yt-dlp" }, preferSystem = true) { "b" })
        assertEquals("b", YtDlpBinary.pickBinary(null, "b", system, runs = { it == "b" }, preferSystem = true) { "b" })
    }
}
