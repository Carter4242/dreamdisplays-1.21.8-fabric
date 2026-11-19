package com.dreamdisplays.media.source.youtube.process

import com.dreamdisplays.media.source.youtube.binary.YtDlpBinary
import com.dreamdisplays.media.source.youtube.cookie.YtCookieManager
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A zipapp whose Python can't verify certificates, run as real processes (stand-in shell scripts): the resolve switches
 * to the binary and succeeds on the first call. POSIX only (the stand-ins are `sh` scripts).
 */
class YtDlpZipappFallbackTest {
    private val posix = !System.getProperty("os.name").orEmpty().startsWith("Windows")

    private fun script(path: Path, body: String): Path {
        Files.writeString(path, "#!/bin/sh\n$body\n")
        path.toFile().setExecutable(true)
        return path
    }

    /** A Python on which every `yt-dlp` call fails the certificate check; counts its fetches in `python-calls`. */
    private fun brokenPython(dir: Path): Path = script(
        dir.resolve("python3"),
        """
        shift
        for a in "${'$'}@"; do [ "${'$'}a" = "--version" ] && { echo 2026.11.01; exit 0; }; done
        echo call >> "${dir.resolve("python-calls")}"
        echo "ERROR: [youtube] dQw4w9WgXcQ: Unable to download API page: [SSL: CERTIFICATE_VERIFY_FAILED] certificate verify failed: unable to get local issuer certificate (_ssl.c:1000)" >&2
        exit 1
        """.trimIndent(),
    )

    /** A standalone `yt-dlp` that answers with a two-height ladder. */
    private fun workingBinary(dir: Path): Path = script(
        dir.resolve("yt-dlp"),
        """
        for a in "${'$'}@"; do [ "${'$'}a" = "--version" ] && { echo 2026.11.01; exit 0; }; done
        echo call >> "${dir.resolve("binary-calls")}"
        cat <<'JSON'
        {"title":"t","duration":10,"formats":[
        {"url":"https://a.example/360","vcodec":"avc1","acodec":"mp4a","ext":"mp4","height":360},
        {"url":"https://a.example/1080","vcodec":"avc1","acodec":"mp4a","ext":"mp4","height":1080}]}
        JSON
        """.trimIndent(),
    )

    private fun calls(dir: Path, name: String): Int =
        dir.resolve(name).takeIf { Files.exists(it) }?.let { Files.readAllLines(it).size } ?: 0

    private fun <T> withZipapp(block: (Path) -> T): T? {
        if (!posix) return null
        val dir = Files.createTempDirectory("ytcert")
        val pyz = dir.resolve("yt-dlp.pyz").also { Files.writeString(it, "") }
        // The binary the switch provisions: the override is what YtDlpBinary.resolve() picks first
        System.setProperty("dreamdisplays.ytdlp", workingBinary(dir).toString())
        try {
            YtDlpBinary.resetForTest([brokenPython(dir).toString(), pyz.toString()])
            return block(dir)
        } finally {
            System.clearProperty("dreamdisplays.ytdlp")
            YtDlpBinary.resetForTest(null)
        }
    }

    @Test
    fun `a certificate failure on the zipapp is retried on the binary within the same resolve`() {
        withZipapp { dir ->
            val streams = runBlocking { YtDlpClientRace(YtCookieManager()).resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ") {} }
            assertNotNull(streams)
            assertEquals(2, streams.size)
            assertEquals(1, calls(dir, "python-calls"))
            assertEquals(1, calls(dir, "binary-calls"))
            assertEquals("yt-dlp binary", YtDlpBinary.label())
            assertEquals(1, YtDlpBinary.zipappSwitches)
            // The next video goes straight to the binary
            runBlocking { YtDlpClientRace(YtCookieManager()).resolve("https://www.youtube.com/watch?v=jNQXAC9IVRw") {} }
            assertEquals(1, calls(dir, "python-calls"))
            assertEquals(2, calls(dir, "binary-calls"))
        }
    }

    @Test
    fun `a certificate failure on another site keeps the zipapp`() {
        withZipapp { dir ->
            val streams = runBlocking { YtDlpClientRace(YtCookieManager()).resolve("https://videos.example.org/clip.mp4") {} }
            assertNull(streams)
            assertTrue(calls(dir, "python-calls") >= 1)
            assertEquals(0, calls(dir, "binary-calls"))
            assertEquals("yt-dlp zipapp", YtDlpBinary.label())
            assertEquals(0, YtDlpBinary.zipappSwitches)
        }
    }
}
