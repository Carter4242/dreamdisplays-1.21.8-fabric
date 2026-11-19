package com.dreamdisplays.media.runtime.diagnostics

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockedProgramsTest {
    @Test
    fun `Windows' application control refusal is recognised`() {
        assertTrue(
            BlockedPrograms.isPolicyBlock(
                "Cannot run program \"C:/dd/ffmpeg.exe\": CreateProcess error=4551, " +
                    "An Application Control policy has blocked this file",
            ),
        )
        assertTrue(BlockedPrograms.isPolicyBlock("CreateProcess error=4551"))
    }

    @Test
    fun `other start failures are not`() {
        assertFalse(BlockedPrograms.isPolicyBlock(null))
        assertFalse(BlockedPrograms.isPolicyBlock("Cannot run program \"ffmpeg\": error=2, No such file or directory"))
        assertFalse(BlockedPrograms.isPolicyBlock("CreateProcess error=5, Access is denied"))
        assertFalse(BlockedPrograms.isPolicyBlock("CreateProcess error=45510"))
    }

    @Test
    fun `a block is handed out once`() {
        BlockedPrograms.note("FFmpeg", IOException("Cannot run program: error=2, No such file or directory"))
        BlockedPrograms.note("yt-dlp", IOException("CreateProcess error=4551, An Application Control policy has blocked this file"))
        assertTrue(BlockedPrograms.take() == "yt-dlp")
        assertTrue(BlockedPrograms.take() == null)
    }
}
