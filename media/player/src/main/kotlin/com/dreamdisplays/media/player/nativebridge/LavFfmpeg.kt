package com.dreamdisplays.media.player.nativebridge

import com.dreamdisplays.media.runtime.download.FfmpegBuilds
import com.dreamdisplays.media.runtime.download.SourceLoader
import com.dreamdisplays.util.OsInfo
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

/**
 * Downloads and unpacks the `FFmpeg` shared libraries the in-process libav backend is linked against, from
 * FFmpeg-Builds ([FfmpegBuilds]). Only release branch [MAJOR] fits: the backend loads the exact library majors in
 * [LIBRARY_MAJORS], so another branch's files are never accepted as installed.
 */
object LavFfmpeg {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** The `FFmpeg` release branch `dreamdisplays_lav` is built against (`ffmpeg-next` 9). */
    private const val MAJOR = 9

    /** The library majors `FFmpeg` 9.x ships; a minor release never changes them. */
    internal val LIBRARY_MAJORS = mapOf(
        "avutil" to 61, "avcodec" to 63, "avformat" to 63, "swscale" to 10, "swresample" to 7,
    )

    /** Also in the archive (unpacked, not needed); only used to clear an older `FFmpeg`'s copies. */
    private val UNLOADED_MAJORS = mapOf("avfilter" to 12, "avdevice" to 63)

    private class Source(val arch: String, val extension: String, val libDir: String)

    /** Ensures [dir] contains `FFmpeg` libraries, downloading and unpacking them on first run. */
    fun ensure(dir: File): Boolean {
        if (installed(dir.list()?.toList().orEmpty())) {
            deleteOtherMajors(dir)
            return true
        }
        val source = when {
            OsInfo.isMac -> return false
            OsInfo.isWindows -> Source(if (OsInfo.isArm64) "winarm64" else "win64", "zip", "bin")
            else -> Source(if (OsInfo.isArm64) "linuxarm64" else "linux64", "tar.xz", "lib")
        }
        return runCatching {
            logger.info("FFmpeg $MAJOR libraries not found — downloading from FFmpeg-Builds...")
            val want = FfmpegBuilds.Want(source.arch, source.extension, FfmpegBuilds.Variant.LGPL_SHARED, MAJOR)
            // Loaded into the game's own process: verified against the release's checksum list
            fun fetch() {
                val found = FfmpegBuilds.locate(FfmpegBuilds.RELEASES, want)
                    ?: throw IOException("No source has FFmpeg $MAJOR shared libraries for ${source.arch}.")
                SourceLoader.downloadAndExtract(found.url, dir.toPath(), "FFmpeg ${found.build.branch} libraries", found.sha256) { name ->
                    File(name).name.takeIf { wantedEntry(name, source.libDir) }
                }
            }
            try {
                fetch()
            } catch (_: SourceLoader.ChecksumMismatchException) {
                fetch() // the rolling release swapped the file between its list and the download: read the list again
            }
            deleteOtherMajors(dir)
            installed(dir.list()?.toList().orEmpty())
        }.getOrElse { e ->
            logger.warn("Could not provision FFmpeg libraries (${e.javaClass.simpleName}: ${e.message}).")
            false
        }
    }

    /**
     * True when [names] (a directory listing) hold every library in [LIBRARY_MAJORS] at its major: `avcodec-63.dll`,
     * `libavcodec.so.63[.x.y]`, `libavcodec.63.dylib`. A partial unpack, a library removed by antivirus, or an older
     * `FFmpeg`'s set (`avcodec-62.dll`) is not installed.
     */
    internal fun installed(names: Collection<String>): Boolean {
        val lower = names.map { it.lowercase() }
        return LIBRARY_MAJORS.all { (family, major) -> lower.any { libraryMajor(it, family) == major } }
    }

    /** The major of [name] when it is a shared library of [family], else null. */
    internal fun libraryMajor(name: String, family: String): Int? {
        val f = Regex.escape(family)
        val m = Regex("""^$f-(\d+)\.dll$""").matchEntire(name)
            ?: Regex("""^lib$f\.so\.(\d+)(?:\.\d+)*$""").matchEntire(name)
            ?: Regex("""^lib$f\.(\d+)(?:\.\d+)*\.dylib$""").matchEntire(name)
            ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    /** Removes another `FFmpeg`'s libraries left by an older version, so they are not preloaded next to these. */
    private fun deleteOtherMajors(dir: File) {
        val majors = LIBRARY_MAJORS + UNLOADED_MAJORS
        dir.listFiles()?.forEach { file ->
            val name = file.name.lowercase()
            val stale = majors.any { (family, major) -> libraryMajor(name, family)?.let { it != major } == true }
            // Best effort: a library still loaded by another running game stays until a later start
            if (stale && file.delete()) logger.info("Removed old FFmpeg library ${file.name}.")
        }
    }

    private fun isSharedLibrary(name: String): Boolean = name.endsWith(".dll") || name.endsWith(".dylib") || ".so" in name

    private fun wantedEntry(entryName: String, libDir: String): Boolean {
        val parts = entryName.split('/')
        val leaf = parts.last()
        return parts.size >= 2 && parts[parts.size - 2] == libDir && isSharedLibrary(leaf.lowercase()) ||
                leaf.equals("LICENSE.txt", ignoreCase = true) && parts.size <= 2
    }
}
