package com.dreamdisplays.media.player.nativebridge

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class NativeLibraryInstallTest {
    private val dir = Files.createTempDirectory("natives")
    private val target = dir.resolve("dreamdisplays_native.dll")
    private val fresh = "new library".toByteArray()

    private fun leftovers() = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    @Test
    fun `an older copy is replaced, not kept`() {
        Files.write(target, "old library from the previous version".toByteArray())
        assertEquals(target, NativeLibraryInstall.install(fresh, target))
        assertContentEquals(fresh, Files.readAllBytes(target))
        assertEquals(listOf("dreamdisplays_native.dll"), leftovers())
    }

    @Test
    fun `a missing copy is written and a current one left alone`() {
        assertEquals(target, NativeLibraryInstall.install(fresh, target))
        val stamp = Files.getLastModifiedTime(target)
        assertEquals(target, NativeLibraryInstall.install(fresh, target) { _, _ -> throw IOException("must not move") })
        assertEquals(stamp, Files.getLastModifiedTime(target))
    }

    @Test
    fun `a locked older copy is loaded from a content-named file instead`() {
        Files.write(target, "old".toByteArray())
        val locked: (java.nio.file.Path, java.nio.file.Path) -> Unit = { from, to ->
            if (to == target) throw AccessDeniedException(to.toString())
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
        val used = NativeLibraryInstall.install(fresh, target, locked)!!
        assertEquals(NativeLibraryInstall.alternateFor(target, fresh), used)
        assertContentEquals(fresh, Files.readAllBytes(used))
        assertContentEquals("old".toByteArray(), Files.readAllBytes(target))
        // Next launch, still locked: the same file again, no new one
        assertEquals(used, NativeLibraryInstall.install(fresh, target, locked))
        assertEquals(2, leftovers().size)
        // Once the lock is gone the plain name wins and the content-named copy goes
        assertEquals(target, NativeLibraryInstall.install(fresh, target))
        assertFalse(Files.exists(used))
    }

    @Test
    fun `nothing writable gives null`() {
        Files.write(target, "old".toByteArray())
        assertNull(NativeLibraryInstall.install(fresh, target) { _, to -> throw AccessDeniedException(to.toString()) })
        assertEquals(listOf("dreamdisplays_native.dll"), leftovers())
    }
}
