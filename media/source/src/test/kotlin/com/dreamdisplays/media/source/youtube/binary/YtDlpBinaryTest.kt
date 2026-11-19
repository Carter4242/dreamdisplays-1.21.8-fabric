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

    /** The error every client got on python.org's macOS Python before "Install Certificates.command". */
    private val certFailure =
        "ERROR: [youtube] dQw4w9WgXcQ: Unable to download API page: [SSL: CERTIFICATE_VERIFY_FAILED] certificate " +
            "verify failed: unable to get local issuer certificate (_ssl.c:1000)"

    @Test
    fun certificateFailuresAreRecognized() {
        assertTrue(YtDlpBinary.looksLikeCertificateFailure(certFailure))
        // The requests handler's wording
        assertTrue(
            YtDlpBinary.looksLikeCertificateFailure(
                "ERROR: [youtube] abc: Unable to download webpage: HTTPSConnectionPool(host='www.youtube.com', port=443): " +
                    "Max retries exceeded (Caused by SSLError(SSLCertVerificationError(1, 'certificate verify failed')))",
            ),
        )
        // curl_cffi's
        assertTrue(YtDlpBinary.looksLikeCertificateFailure("ERROR: curl: (60) SSL certificate problem: unable to get local issuer certificate"))
        // A dropped connection is not a trust failure
        assertFalse(YtDlpBinary.looksLikeCertificateFailure("ERROR: [youtube] abc: [SSL: UNEXPECTED_EOF_WHILE_READING] EOF occurred"))
        assertFalse(YtDlpBinary.looksLikeCertificateFailure("ERROR: [youtube] abc: Video unavailable"))
        assertFalse(YtDlpBinary.looksLikeCertificateFailure(null))
    }

    @Test
    fun aCertificateFailureBlamesThePythonOnlyForYouTube() {
        assertEquals(YtDlpBinary.ZipappFailure.CERTIFICATE, YtDlpBinary.zipappFailure(certFailure, youtube = true))
        // Another site's certificate may really be bad: no switch
        assertNull(YtDlpBinary.zipappFailure(certFailure, youtube = false))
        val tooOld = "ImportError: You are using an unsupported version of Python. Only Python versions 3.11 and above"
        assertEquals(YtDlpBinary.ZipappFailure.INTERPRETER, YtDlpBinary.zipappFailure(tooOld, youtube = true))
        assertEquals(YtDlpBinary.ZipappFailure.INTERPRETER, YtDlpBinary.zipappFailure(tooOld, youtube = false))
        assertNull(YtDlpBinary.zipappFailure("ERROR: [youtube] abc: Video unavailable", youtube = true))
        assertNull(YtDlpBinary.zipappFailure(null, youtube = true))
    }

    private val zipapp = ["/Library/Frameworks/Python.framework/Versions/3.13/bin/python3", "libs/yt-dlp/yt-dlp.pyz"]

    @Test
    fun aCertificateFailureSwitchesTheSessionToTheBinary() {
        try {
            YtDlpBinary.resetForTest(zipapp)
            assertEquals("yt-dlp zipapp", YtDlpBinary.label())
            var provisioned = 0
            val binary = { provisioned++; "libs/yt-dlp/yt-dlp_macos" }
            assertTrue(YtDlpBinary.abandonZipapp(zipapp, certFailure, YtDlpBinary.ZipappFailure.CERTIFICATE, binary))
            assertEquals("yt-dlp binary", YtDlpBinary.label())
            assertEquals(1, YtDlpBinary.zipappSwitches)
            assertTrue("failed the SSL certificate check" in YtDlpBinary.note(), YtDlpBinary.note())
            assertTrue("switched to the binary" in YtDlpBinary.note(), YtDlpBinary.note())
            // Picking the command again (a changed source preference) keeps the binary for the session
            assertFalse(YtDlpBinary.zipappAllowed("auto"))
            assertFalse(YtDlpBinary.zipappAllowed("bundled"))
            // A client of the same race that failed on the zipapp too retries on the binary, provisioned once
            assertTrue(YtDlpBinary.abandonZipapp(zipapp, certFailure, YtDlpBinary.ZipappFailure.CERTIFICATE, binary))
            assertEquals(1, provisioned)
            assertEquals(1, YtDlpBinary.zipappSwitches)
        } finally {
            YtDlpBinary.resetForTest(null)
        }
    }

    @Test
    fun withNoBinaryToSwitchToTheZipappStays() {
        try {
            YtDlpBinary.resetForTest(zipapp)
            assertFalse(
                YtDlpBinary.abandonZipapp(zipapp, certFailure, YtDlpBinary.ZipappFailure.CERTIFICATE) { throw IOException("offline") },
            )
            assertEquals("yt-dlp zipapp", YtDlpBinary.label())
            assertEquals(0, YtDlpBinary.zipappSwitches)
            assertTrue(YtDlpBinary.zipappAllowed("auto"))
            assertEquals("", YtDlpBinary.note())
        } finally {
            YtDlpBinary.resetForTest(null)
        }
    }

    @Test
    fun aFailureOnAnOutdatedCommandRetriesOnTheCurrentOne() {
        try {
            YtDlpBinary.resetForTest(["libs/yt-dlp/yt-dlp"])
            // Not the command that failed: the current one is already the binary, so a retry may use it
            assertTrue(YtDlpBinary.abandonZipapp(zipapp, certFailure, YtDlpBinary.ZipappFailure.CERTIFICATE) { error("not provisioned") })
            assertEquals(0, YtDlpBinary.zipappSwitches)
            YtDlpBinary.resetForTest(null)
            assertFalse(YtDlpBinary.abandonZipapp(zipapp, certFailure, YtDlpBinary.ZipappFailure.CERTIFICATE) { error("not provisioned") })
        } finally {
            YtDlpBinary.resetForTest(null)
        }
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
