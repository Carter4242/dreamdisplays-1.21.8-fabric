package com.dreamdisplays.platform.client.render

import com.dreamdisplays.platform.client.render.ThumbnailCacheSweep.Entry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ThumbnailCacheSweepTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    @Test
    fun `expired thumbnails and old write leftovers go, fresh ones stay`() {
        val entries = listOf(
            Entry("old.jpg", now - 8 * day, 10), Entry("fresh.jpg", now - day, 10),
            Entry("stray.jpg.tmp", now - 2 * 60 * 60 * 1000, 10), Entry("writing.jpg.tmp", now - 1000, 10),
        )
        assertEquals(listOf("old.jpg", "stray.jpg.tmp"), ThumbnailCacheSweep.evictions(entries, now, 7 * day, 1_000))
    }

    @Test
    fun `over the size cap the oldest go first`() {
        val entries = listOf(Entry("c.jpg", now - 1, 40), Entry("a.jpg", now - 3, 40), Entry("b.jpg", now - 2, 40))
        assertEquals(listOf("a.jpg"), ThumbnailCacheSweep.evictions(entries, now, 7 * day, 80))
        assertEquals(listOf("a.jpg", "b.jpg"), ThumbnailCacheSweep.evictions(entries, now, 7 * day, 79))
        assertEquals(emptyList(), ThumbnailCacheSweep.evictions(entries, now, 7 * day, 120))
    }

    @Test
    fun `the sweep deletes on disk and copes with a missing folder`() {
        val dir = Files.createTempDirectory("thumbs").toFile()
        val old = java.io.File(dir, "old.jpg").apply { writeText("x"); setLastModified(now - 8 * day) }
        val fresh = java.io.File(dir, "fresh.jpg").apply { writeText("x"); setLastModified(now - day) }
        assertEquals(1, ThumbnailCacheSweep.sweep(dir, now, 7 * day, 1_000))
        assertEquals(false, old.exists())
        assertEquals(true, fresh.exists())
        assertEquals(0, ThumbnailCacheSweep.sweep(java.io.File(dir, "missing"), now, 7 * day, 1_000))
    }
}
