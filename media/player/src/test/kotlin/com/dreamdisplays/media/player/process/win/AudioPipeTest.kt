package com.dreamdisplays.media.player.process.win

import com.dreamdisplays.media.player.process.ShareAudio
import com.dreamdisplays.util.OsInfo
import java.io.FileInputStream
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AudioPipeTest {
    @Test
    fun `a pipe closed before its writer started is gone, not left open for the game's lifetime`() {
        if (!OsInfo.isWindows) return
        val pipe = AudioPipe(ShareAudio.Format(48_000, 2, float = false), System.nanoTime())
        pipe.close()
        // With the handle still open, the pipe would still exist and accept this connection
        assertFailsWith<FileNotFoundException> { FileInputStream(pipe.path).close() }
        // Starting it afterwards does nothing
        pipe.start()
    }
}
