package com.dreamdisplays.media.source.youtube.process

import com.dreamdisplays.media.source.youtube.binary.YtDlpOutputParser
import com.dreamdisplays.media.source.youtube.cookie.YtCookieManager
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YtDlpClientRaceTest {
    private val ladder = YtDlpOutputParser.parseFormats(
        """{"title":"t","duration":10,"formats":[
            {"url":"https://a.example/360","vcodec":"avc1","acodec":"mp4a","ext":"mp4","height":360},
            {"url":"https://a.example/1080","vcodec":"avc1","acodec":"mp4a","ext":"mp4","height":1080}
        ]}""",
    )

    /** A child process that just sleeps, standing in for a `yt-dlp` client that hangs. */
    private fun sleeper(dir: Path): Process {
        val src = dir.resolve("Sleep.java")
        Files.writeString(src, "class Sleep { public static void main(String[] a) throws Exception { Thread.sleep(60_000); } }")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        return ProcessBuilder(java, src.toString()).start()
    }

    @Test
    fun `the winner returns at once and the clients still running are killed`() {
        val dir = Files.createTempDirectory("race")
        var loser: Process? = null
        val started = System.nanoTime()
        val result = runBlocking {
            YtDlpClientRace(YtCookieManager()).raceParallel("https://www.youtube.com/watch?v=x", ["slow", "fast"], {}) { _, client, onStarted ->
                if (client == "slow") {
                    val p = sleeper(dir).also { loser = it }
                    onStarted(p)
                    p.waitFor(60, TimeUnit.SECONDS)
                    throw IOException("killed")
                }
                Thread.sleep(1_500) // let the slow client get going first
                ladder
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertNotNull(result)
        assertEquals(2, result.size)
        assertTrue(elapsedMs < 15_000, "took $elapsedMs ms")
        val p = assertNotNull(loser)
        assertTrue(p.waitFor(5, TimeUnit.SECONDS))
        assertFalse(p.isAlive)
    }
}
