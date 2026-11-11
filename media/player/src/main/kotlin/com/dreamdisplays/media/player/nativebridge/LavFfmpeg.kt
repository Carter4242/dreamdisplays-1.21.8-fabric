package com.dreamdisplays.media.player.nativebridge

import com.dreamdisplays.media.runtime.download.FfmpegBuilds
import com.dreamdisplays.media.runtime.download.SourceLoader
import com.dreamdisplays.util.OsInfo
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

/**
 * Downloads and unpacks the `FFmpeg` shared libraries the in-process libav backend is linked against, from
 * FFmpeg-Builds ([FfmpegBuilds]). Only release branch [MAJOR] fits: the backend loads that branch's library majors.
 */
object LavFfmpeg {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** The `FFmpeg` release branch `dreamdisplays_lav` is built against (`ffmpeg-next` 9). */
    private const val MAJOR = 9

    private class Source(val arch: String, val extension: String, val libDir: String)

    /** Ensures [dir] contains `FFmpeg` libraries, downloading and unpacking them on first run. */
    fun ensure(dir: File): Boolean {
        if (hasFfmpeg(dir)) return true
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
            hasFfmpeg(dir)
        }.getOrElse { e ->
            logger.warn("Could not provision FFmpeg libraries (${e.javaClass.simpleName}: ${e.message}).")
            false
        }
    }

    private fun hasFfmpeg(dir: File): Boolean =
        dir.listFiles()?.any { it.isFile && it.name.lowercase().let { n -> "avcodec" in n && isSharedLibrary(n) } } == true

    private fun isSharedLibrary(name: String): Boolean = name.endsWith(".dll") || name.endsWith(".dylib") || ".so" in name

    private fun wantedEntry(entryName: String, libDir: String): Boolean {
        val parts = entryName.split('/')
        val leaf = parts.last()
        return parts.size >= 2 && parts[parts.size - 2] == libDir && isSharedLibrary(leaf.lowercase()) ||
                leaf.equals("LICENSE.txt", ignoreCase = true) && parts.size <= 2
    }
}
