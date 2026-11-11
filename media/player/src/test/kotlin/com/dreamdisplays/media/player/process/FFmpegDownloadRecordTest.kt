package com.dreamdisplays.media.player.process

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FFmpegDownloadRecordTest {
    @Test
    fun `a downloaded build is remembered only while its binary is unchanged`() {
        val dir = Files.createTempDirectory("ffmpeg-win64-9.0").toFile()
        val binary = File(dir, "ffmpeg.exe").apply { writeText("build bytes") }
        assertNull(FFmpegBinary.downloadRecord(dir), "a build with no record is replaced once")

        FFmpegBinary.writeDownloadRecord(dir, binary, ok = false)
        assertEquals("fails", FFmpegBinary.downloadRecord(dir), "the same bytes are not downloaded again")
        FFmpegBinary.writeDownloadRecord(dir, binary, ok = true)
        assertEquals("ok", FFmpegBinary.downloadRecord(dir))

        binary.writeText("truncated") // corrupted or swapped since: replaced again
        assertNull(FFmpegBinary.downloadRecord(dir))
        dir.deleteRecursively()
    }
}
