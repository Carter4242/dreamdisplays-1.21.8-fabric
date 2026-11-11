package com.dreamdisplays.media.runtime.download

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SourceLoaderExtractTest {
    private val dir: Path = Files.createTempDirectory("extract")

    private fun zip(vararg entries: Pair<String, ByteArray>, truncateTo: Int? = null): Path {
        val file = dir.resolve("a.zip")
        ZipOutputStream(Files.newOutputStream(file)).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
            }
        }
        if (truncateTo != null) Files.write(file, Files.readAllBytes(file).copyOf(truncateTo))
        return file
    }

    @Test
    fun `a complete archive unpacks the selected entries`() {
        val payload = ByteArray(10_000) { it.toByte() }
        val out = dir.resolve("out")
        assertEquals(1, SourceLoader.extract(zip("pkg/bin/ffmpeg.exe" to payload), out) { n -> "ffmpeg.exe".takeIf { n.endsWith("/bin/ffmpeg.exe") } })
        assertContentEquals(payload, out.resolve("ffmpeg.exe").readBytes())
        assertEquals(["ffmpeg.exe"], out.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `a broken archive leaves no partial binary behind`() {
        // Random bytes do not deflate, so the entry's data is mostly in the part the truncation removes
        val payload = ByteArray(200_000).also { java.util.Random(1).nextBytes(it) }
        val out = dir.resolve("out")
        assertFailsWith<IOException> {
            SourceLoader.extract(zip("bin/ffmpeg.exe" to payload, truncateTo = 100_000), out) { "ffmpeg.exe" }
        }
        assertFalse(out.resolve("ffmpeg.exe").exists())
        assertEquals([], out.listDirectoryEntries())
    }

    @Test
    fun `an entry may not be written outside the target directory`() {
        assertFailsWith<IOException> {
            SourceLoader.extract(zip("x" to byteArrayOf(1)), dir.resolve("out")) { "../escaped" }
        }
        assertFalse(dir.resolve("escaped").exists())
    }
}
