package com.dreamdisplays.media.player.pipeline

import com.dreamdisplays.api.media.model.FramePixelFormat
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class LastFrameCacheTest {
    private fun replays(cache: LastFrameCache): Int {
        var n = 0
        cache.replay { _, _, _, _ -> n++ }
        return n
    }

    @Test
    fun `a cleared cache replays nothing until a new frame is stored`() {
        val cache = LastFrameCache()
        cache.store(ByteBuffer.allocateDirect(12), 2, 2, 12, FramePixelFormat.RGB24)
        assertEquals(1, replays(cache))
        cache.clear()
        // A popout or PiP opened now must not get the dropped picture
        assertEquals(0, replays(cache))
        // The next frame is stored at once, not held back by the store interval
        cache.store(ByteBuffer.allocateDirect(12), 2, 2, 12, FramePixelFormat.RGB24)
        assertEquals(1, replays(cache))
    }
}
