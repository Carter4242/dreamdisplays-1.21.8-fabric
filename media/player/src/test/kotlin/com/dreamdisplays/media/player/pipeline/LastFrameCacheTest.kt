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

    @Test
    fun `the newest frame is kept at most once per interval, in one reused buffer`() {
        var now = 0L
        val cache = LastFrameCache(storeIntervalNanos = 100L, nanoTime = { now })
        fun frame(v: Byte) = ByteBuffer.allocateDirect(12).apply { put(0, v) }
        fun first(): Byte { var b: Byte = -1; cache.replay { buf, _, _, _ -> b = buf.get(0) }; return b }
        cache.store(frame(1), 2, 2, 12, FramePixelFormat.RGB24)
        now = 50L
        cache.store(frame(2), 2, 2, 12, FramePixelFormat.RGB24)
        assertEquals(1, first(), "Within the interval the copy is skipped.")
        now = 150L
        cache.store(frame(3), 2, 2, 12, FramePixelFormat.RGB24)
        assertEquals(3, first())
    }

    @Test
    fun `a raw planar frame is converted only when a sink asks for it`() {
        var conversions = 0
        val cache = LastFrameCache(toSinkFrame = { _, size, w, h ->
            conversions++
            assertEquals(6, size)
            ByteBuffer.allocateDirect(w * h * 4)
        })
        val planar = ByteBuffer.allocateDirect(6) // 2x2 I420
        planar.position(3)
        cache.store(planar, 2, 2, 6, FramePixelFormat.R8, raw = true)
        assertEquals(3, planar.position(), "The reader's buffer must be left as it was.")
        assertEquals(0, conversions)
        var got: FramePixelFormat? = null
        cache.replay { buf, w, h, fmt -> got = fmt; assertEquals(16, buf.remaining()); assertEquals(2, w); assertEquals(2, h) }
        assertEquals(FramePixelFormat.RGBA32, got)
        assertEquals(1, conversions)
    }
}
