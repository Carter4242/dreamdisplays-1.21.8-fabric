package com.dreamdisplays.media.player.nativebridge

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Puts a bundled native library on disk where it can be loaded, replacing a copy an older version left there. */
internal object NativeLibraryInstall {
    /**
     * Returns where [bytes] can be loaded from: [target], written or replaced as needed. When [target] holds other bytes
     * that can't be replaced (Windows keeps a loaded library locked, e.g. by a second game still on the old version),
     * the bytes go to `<name>-<sha8>.<ext>` beside it instead. Null when neither can be written.
     */
    fun install(bytes: ByteArray, target: Path, move: (Path, Path) -> Unit = ::replace): Path? {
        val parent = target.toAbsolutePath().parent ?: return null
        runCatching { Files.createDirectories(parent) }.onFailure { return null }
        if (holds(target, bytes)) {
            deleteAlternates(target)
            return target
        }
        if (put(bytes, target, move)) {
            deleteAlternates(target)
            return target
        }
        val alternate = alternateFor(target, bytes)
        if (holds(alternate, bytes) || put(bytes, alternate, move)) return alternate
        return null
    }

    /** `<name>-<first 8 hex of sha256>.<ext>` beside [target]. */
    internal fun alternateFor(target: Path, bytes: ByteArray): Path {
        val name = target.fileName.toString()
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).take(4).joinToString("") { "%02x".format(it) }
        return target.resolveSibling("$base-$sha$ext")
    }

    private fun holds(file: Path, bytes: ByteArray): Boolean =
        Files.isRegularFile(file) && runCatching { Files.size(file) == bytes.size.toLong() && Files.readAllBytes(file).contentEquals(bytes) }
            .getOrDefault(false)

    private fun put(bytes: ByteArray, dest: Path, move: (Path, Path) -> Unit): Boolean {
        val tmp = runCatching { Files.createTempFile(dest.toAbsolutePath().parent, dest.fileName.toString(), ".tmp") }
            .getOrElse { return false }
        return try {
            Files.write(tmp, bytes)
            move(tmp, dest)
            true
        } catch (_: IOException) {
            false
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    /** Content-named copies from a time the plain name was locked; best effort (one may still be loaded). */
    private fun deleteAlternates(target: Path) {
        val name = target.fileName.toString()
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val pattern = Regex("${Regex.escape(base)}-[0-9a-f]{8}${Regex.escape(ext)}")
        runCatching {
            Files.list(target.toAbsolutePath().parent).use { files ->
                files.filter { pattern.matches(it.fileName.toString()) }.forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    // File.renameTo never replaces an existing file on Windows; this does (unless the file is locked)
    private fun replace(from: Path, to: Path) {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
