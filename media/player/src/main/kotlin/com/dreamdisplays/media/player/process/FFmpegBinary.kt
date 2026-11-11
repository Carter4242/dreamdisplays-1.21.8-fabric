package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.player.util.daemon
import com.dreamdisplays.media.runtime.download.FfmpegBuilds
import com.dreamdisplays.media.runtime.download.SourceLoader
import com.dreamdisplays.util.OsInfo
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * `FFmpeg` binary downloader. Windows and Linux builds come from FFmpeg-Builds ([FfmpegBuilds]: release branch 9 where
 * any source still has it, else the nearest other branch); macOS builds from a fixed list of checksummed archives.
 * A cached build is only used while it actually runs; otherwise a system `FFmpeg` is preferred, then a fresh download.
 */
object FFmpegBinary {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val CACHE_ROOT = "./dreamdisplays/ffmpeg"

    /** The release branch this version was built and tested against, and the oldest one it still runs on. */
    private const val WANTED_MAJOR = 9
    private const val OLDEST_MAJOR = 8

    /**
     * Kept in each build folder by [provision]: `<sha256 of the binary> ok|fails`. A cached binary that does not run is
     * downloaded again only when it is not byte-for-byte that download, so a broken one is replaced once, not on
     * every start, and a replacement that ran is not fetched again over a passing failure.
     */
    private const val DOWNLOAD_RECORD = ".download"

    /** How long a failed resolve is not retried (every playback start asks for the binary). */
    private const val RETRY_AFTER_FAILURE_MS = 10_000L

    @Volatile
    private var cachedPath: String? = null

    @Volatile
    private var failedAtMs = 0L

    /** The [preferredSource] [cachedPath] was resolved for: a different preference resolves again. */
    @Volatile
    private var cachedFor: String? = null

    /**
     * Which binary comes first: `auto` / `bundled` (the downloaded build, an installed one if that fails) or `system`
     * (an installed one, the downloaded build if there is none). Set by the game (the server's `client.ffmpegSource`);
     * applies to processes started after a change.
     */
    @Volatile
    var preferredSource: () -> String = { "auto" }

    /** Where [getPath] found the binary: `bundled`, `system`, or `missing`; empty before it ran. */
    @Volatile
    var source: String = ""
        private set

    /** The bundled build's release branch (`9.0`, `master`), empty when none is in use. */
    @Volatile
    var branch: String = ""
        private set

    @Volatile
    private var versionProbe: Pair<String, String>? = null

    /** What `FFmpeg` runs here, for client diagnostics. The path is raw (the reporter scrubs it). */
    data class Info(
        val source: String,
        val path: String,
        val version: String,
        val decoders: List<String>,
        val filters: List<String>,
    )

    /** Describes the resolved binary without resolving one; blocks for a `-version` run once (call off-thread). */
    fun describe(): Info {
        val path = cachedPath ?: return Info(source.ifEmpty { "not probed" }, "", "", emptyList(), emptyList())
        val version = versionProbe?.takeIf { it.first == path }?.second ?: runCatching {
            val p = ProcessBuilder(path, "-hide_banner", "-version").redirectErrorStream(true).start()
            runCatching { p.outputStream.close() }
            val firstLine = p.inputStream.bufferedReader().use { r -> r.readLine().orEmpty() }
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
            firstLine.removePrefix("ffmpeg version ").substringBefore(" Copyright").trim().take(120)
        }.getOrElse { e -> "failed: ${e.message}" }.also { versionProbe = path to it }
        return Info(
            source = source,
            path = path,
            version = version,
            decoders = FFmpegCapabilities.optionalDecoders(path).sorted(),
            filters = FFmpegCapabilities.optionalFilters(path).sorted(),
        )
    }

    /** The binary [getPath] already resolved, or null; never downloads or probes (safe on the render thread). */
    fun getPathIfResolved(): String? = cachedPath

    /**
     * Returns the path to a usable `FFmpeg` binary, resolving and caching it on the first call (and on a new
     * [preferredSource]). A bundled binary that has since disappeared (antivirus quarantine, cache wiped while running)
     * is resolved again.
     */
    fun getPath(): String? {
        val preference = runCatching(preferredSource).getOrDefault("auto")
        current(preference)?.let { return it }
        synchronized(this) {
            current(preference)?.let { return it }
            if (cachedFor != null && cachedFor != preference) {
                logger.info("FFmpeg source preference is now {}; resolving the binary again.", preference)
                failedAtMs = 0L
            }
            if (cachedPath == null && failedAtMs != 0L && System.currentTimeMillis() - failedAtMs < RETRY_AFTER_FAILURE_MS) {
                return null
            }
            cachedPath = if (preference == "system") findSystemFfmpeg(quiet = true) ?: resolve() else resolve()
            cachedFor = preference
            failedAtMs = if (cachedPath == null) System.currentTimeMillis() else 0L
            return cachedPath
        }
    }

    private fun current(preference: String): String? {
        if (cachedFor != preference) return null
        val path = cachedPath ?: return null
        if (source != "bundled" || File(path).isFile) return path
        logger.warn("The FFmpeg binary at $path is gone; resolving it again.")
        cachedPath = null
        return null
    }

    /**
     * Resolves the `FFmpeg` binary in the background to minimize latency on first use, and probes
     * its optional filters and decoders while it is there — otherwise that probe spawns its own `ffmpeg -filters`
     * synchronously inside the first playback launch, right where latency is most visible.
     */
    fun prewarmAsync() {
        daemon({
            runCatching {
                val path = getPath()
                if (path != null) {
                    HwAccelBackend.probeDevice(path)
                    FFmpegCapabilities.hasFilter(path, "scale_vt")
                    FFmpegCapabilities.hasDecoder(path, "libvpx-vp9")
                    FFmpegCapabilities.hasLiveStamps(path)
                }
            }.onFailure { e -> logger.warn("Prewarm failed", e) }
        }, "FFmpeg-prewarm").start()
    }

    private fun resolve(): String? {
        val p = detectPlatform() ?: run {
            logger.warn("No bundled binary URL for this OS / arch; trying system FFmpeg.")
            return findSystemFfmpeg()
        }
        val cached = cachedBuilds(p)
        for (dir in cached) {
            val binary = File(dir, p.binaryName)
            if (runs(binary) == Run.FAILED) {
                logger.warn("Cached FFmpeg ${binary.absolutePath} does not run.")
                continue
            }
            logger.info("Using binary: ${binary.absolutePath}.")
            return bundled(binary, dir)
        }
        // Every cached build fails to run (quarantined, a libc it can't use, a no-exec game folder): a working system
        // FFmpeg beats downloading the same build again
        if (cached.isNotEmpty()) findSystemFfmpeg(quiet = true)?.let { return it }
        cached.firstOrNull { downloadRecord(it) != null }?.let { dir ->
            logger.warn(
                "FFmpeg in {} is the build downloaded for it ({} when checked) and does not run now; not downloading it again.",
                dir.name, downloadRecord(dir),
            )
            return bundled(File(dir, p.binaryName), dir)
        }
        return runCatching { provision(p) }.getOrElse { e ->
            logger.error("Download failed, falling back to system ffmpeg", e)
            null
        } ?: findSystemFfmpeg()
            ?: cached.firstOrNull()?.let { bundled(File(it, p.binaryName), it) } // nothing better: as before
    }

    private fun bundled(binary: File, dir: File): String {
        source = "bundled"
        branch = dir.name.substringAfterLast('-')
        return binary.absolutePath
    }

    /** Cached build folders of [p] holding its binary, the wanted branch first. */
    private fun cachedBuilds(p: Platform): List<File> =
        File(CACHE_ROOT).listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("${p.key}-") && File(it, p.binaryName).let { b -> b.isFile && b.length() > 0 } }
            .sortedWith(compareByDescending<File> { it.name.startsWith("${p.key}-$WANTED_MAJOR.") }.thenByDescending { it.name })

    /** Downloads and unpacks a build for [p]; returns the binary's path. Throws when no source has one that runs. */
    @Throws(IOException::class)
    private fun provision(p: Platform): String {
        val select = { name: String -> p.binaryName.takeIf { name.endsWith(p.entrySuffix) } }
        var lastError: IOException? = null
        // A second pass only after a checksum mismatch: the rolling `latest` can swap a file between list and download
        for (pass in 0..1) {
            for (candidate in p.candidates()) {
                val dir = File("$CACHE_ROOT/${p.key}-${candidate.branch}")
                val binary = File(dir, p.binaryName)
                try {
                    SourceLoader.downloadAndExtract(candidate.url, dir.toPath(), "FFmpeg ${candidate.branch}", candidate.sha256, select)
                    if (!binary.isFile || binary.length() == 0L) throw IOException("Extracted binary is missing or empty.")
                    SourceLoader.makeExecutable(binary.toPath())
                    val ok = runs(binary) != Run.FAILED
                    writeDownloadRecord(dir, binary, ok)
                    if (!ok) throw IOException("Downloaded FFmpeg ${candidate.branch} does not run here.")
                    deleteStaleBuilds(p.key, dir.name)
                    logger.info("FFmpeg ${candidate.branch} ready from ${candidate.url}.")
                    return bundled(binary, dir)
                } catch (e: IOException) {
                    logger.warn("FFmpeg from ${candidate.url} failed: ${e.message}")
                    lastError = e
                }
            }
            if (lastError !is SourceLoader.ChecksumMismatchException) break
        }
        throw lastError ?: IOException("No FFmpeg build found for ${p.key}.")
    }

    /** `ok` / `fails` when [dir]'s binary is still exactly the one [provision] downloaded and checked there, else null. */
    internal fun downloadRecord(dir: File): String? = runCatching {
        val (sha, result) = File(dir, DOWNLOAD_RECORD).readText().trim().split(' ', limit = 2)
        result.takeIf { SourceLoader.sha256(File(dir, binaryNameIn(dir)).toPath()).equals(sha, ignoreCase = true) }
    }.getOrNull()

    internal fun writeDownloadRecord(dir: File, binary: File, ok: Boolean) {
        runCatching {
            File(dir, DOWNLOAD_RECORD).writeText("${SourceLoader.sha256(binary.toPath())} ${if (ok) "ok" else "fails"}")
        }.onFailure { logger.debug("Could not record the FFmpeg download in {}: {}", dir, it.message) }
    }

    private fun binaryNameIn(dir: File): String = if (File(dir, "ffmpeg.exe").isFile) "ffmpeg.exe" else "ffmpeg"

    private enum class Run { OK, FAILED, SLOW }

    /** Runs `[binary] -version`: [Run.SLOW] when it hasn't answered in time (a first antivirus scan), not a failure. */
    private fun runs(binary: File): Run = try {
        val p = ProcessBuilder(binary.absolutePath, "-hide_banner", "-version").redirectErrorStream(true).start()
        runCatching { p.outputStream.close() }
        daemon({ runCatching { p.inputStream.transferTo(OutputStream.nullOutputStream()) } }, "FFmpeg-version-drain").start()
        when {
            !p.waitFor(15, TimeUnit.SECONDS) -> Run.SLOW.also { p.destroyForcibly() }
            p.exitValue() == 0 -> Run.OK
            else -> Run.FAILED
        }
    } catch (_: IOException) {
        Run.FAILED
    }

    private fun deleteStaleBuilds(platformKey: String, keep: String) {
        File(CACHE_ROOT).listFiles()
            ?.filter { it.isDirectory && it.name != keep && (it.name == platformKey || it.name.startsWith("$platformKey-")) }
            ?.forEach { stale ->
                if (stale.deleteRecursively()) logger.info("Removed stale FFmpeg build: ${stale.name}.")
            }
    }

    private fun findSystemFfmpeg(quiet: Boolean = false): String? {
        val candidates = ["ffmpeg", "/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg"]
        for (candidate in candidates) {
            runCatching {
                val p = ProcessBuilder(candidate, "-version").redirectErrorStream(true).start()
                daemon({ runCatching { p.inputStream.transferTo(OutputStream.nullOutputStream()) } }, "FFmpeg-version-drain")
                    .start()
                if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    logger.info("Using system ffmpeg: $candidate...")
                    source = "system"
                    branch = ""
                    return candidate
                }
                p.destroyForcibly()
            }
        }
        if (quiet) return null
        logger.error("FFmpeg not found (no download succeeded, no system binary).")
        source = "missing"
        branch = ""
        return null
    }

    private fun detectPlatform(): Platform? = when {
        OsInfo.isWindows -> if (OsInfo.isArm64) {
            Platform.builds("windows-aarch64", "winarm64", "zip", "ffmpeg.exe", "/bin/ffmpeg.exe")
        } else {
            Platform.builds("windows-x64", "win64", "zip", "ffmpeg.exe", "/bin/ffmpeg.exe")
        }
        OsInfo.isMac -> if (OsInfo.isArm) Platform.mac("macos-aarch64", MAC_ARM) else Platform.mac("macos-x64", MAC_X64)
        OsInfo.isArm64 -> Platform.builds("linux-aarch64", "linuxarm64", "tar.xz", "ffmpeg", "/bin/ffmpeg")
        OsInfo.isArm -> null // 32-bit ARM: no build is published
        else -> Platform.builds("linux-x64", "linux64", "tar.xz", "ffmpeg", "/bin/ffmpeg")
    }

    /** One archive to try: verified against [sha256] (never run unchecked), unpacked into `<platform>-<branch>`. */
    internal class Candidate(val url: String, val sha256: String, val branch: String)

    /**
     * macOS archives (no publisher checksum list exists, so each is pinned here; a changed file is refused and the next
     * one tried).
     */
    private val MAC_ARM = [
        Candidate("https://www.osxexperts.net/ffmpeg902arm.zip", "4597f0f3a397cc0027bfd03dc4dd9c3066be15943cc788370846b4305d178bcb", "9.0"),
        Candidate("https://www.osxexperts.net/ffmpeg9arm.zip", "d0c06c5c68ce48af3143b262f7a9118a7c9f67de1e237fcc24ffb14df9c67af9", "9.0"),
    ]
    private val MAC_X64 = [
        Candidate("https://evermeet.cx/ffmpeg/ffmpeg-9.0.2.zip", "4acc0be580f9b2788029eb7bd4d645ff87968911b0a62aeeb3940d42d54558d5", "9.0"),
        Candidate("https://www.osxexperts.net/ffmpeg80intel.zip", "2d24d22db78c87f394a5822867acd5c5dc5e762cd261a44bd26923f3a5af3e07", "8.0"),
    ]

    private class Platform(
        val key: String,
        val binaryName: String,
        val entrySuffix: String,
        val candidates: () -> List<Candidate>,
    ) {
        companion object {
            fun builds(key: String, arch: String, ext: String, binaryName: String, entrySuffix: String) =
                Platform(key, binaryName, entrySuffix) {
                    val want = FfmpegBuilds.Want(
                        arch, ext, FfmpegBuilds.Variant.GPL, WANTED_MAJOR, otherMajors = true, oldestMajor = OLDEST_MAJOR,
                    )
                    listOfNotNull(
                        FfmpegBuilds.locate(FfmpegBuilds.RELEASES, want)?.let { Candidate(it.url, it.sha256, it.build.branch) },
                    )
                }

            fun mac(key: String, pinned: List<Candidate>) = Platform(key, "ffmpeg", "ffmpeg") { pinned }
        }
    }
}
