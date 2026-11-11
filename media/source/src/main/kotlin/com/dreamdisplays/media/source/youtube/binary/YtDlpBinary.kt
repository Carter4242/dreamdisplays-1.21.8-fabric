package com.dreamdisplays.media.source.youtube.binary

import com.dreamdisplays.media.runtime.download.SourceLoader
import com.dreamdisplays.media.runtime.system.Processes
import com.dreamdisplays.util.DreamCoroutines
import com.dreamdisplays.util.OsInfo
import com.dreamdisplays.util.net.DreamHttpClient
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

/**
 * Provisioning of the `yt-dlp` executable: locating a system install, falling back to the bundled
 * copy, downloading from GitHub as a last resort, and weekly background self-updates of the bundled
 * binary.
 */
object YtDlpBinary {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Directory holding the bundled binary and its cookie files; shared with [YtCookieManager]. */
    val bundledDir: Path = Path.of("libs", "yt-dlp")

    private val CANDIDATE_PATHS = arrayOf(
        "yt-dlp",
        "/opt/homebrew/bin/yt-dlp",
        "/usr/local/bin/yt-dlp",
        "/usr/bin/yt-dlp",
        "C:\\Program Files\\yt-dlp\\yt-dlp.exe",
    )

    private const val RELEASES = "https://github.com/yt-dlp/yt-dlp/releases"
    private const val DOWNLOAD_BASE = "$RELEASES/latest/download/"
    private const val SUMS_ASSET = "SHA2-256SUMS"
    private const val ZIPAPP_ASSET = "yt-dlp"
    private const val ZIPAPP_NAME = "yt-dlp.pyz"

    /**
     * Oldest Python the current `yt-dlp` runs on: its `__init__` raises ImportError on anything older. macOS ships
     * 3.9 as `/usr/bin/python3`, so a looser gate picks an interpreter on which every call dies at once.
     */
    private const val MIN_PYTHON_MINOR = 11

    /** Interpreters tried in order; the first new enough wins (a Homebrew 3.12 beats an older `python3` on PATH). */
    private val PYTHON_CANDIDATES: Array<String> =
        if (OsInfo.isWindows) arrayOf("python", "python3", "py")
        else arrayOf(
            "python3", "/opt/homebrew/bin/python3", "/usr/local/bin/python3", "/usr/bin/python3",
            "python3.14", "python3.13", "python3.12", "python3.11", "python",
        )

    /** Output of a Python that refuses to run this `yt-dlp` (or a zipapp it can't load). */
    private val INTERPRETER_FAILURE = Regex(
        "Only Python versions|unsupported version of Python|ImportError|ModuleNotFoundError|SyntaxError|" +
            "ZipImportError|BadZipFile|can't find '__main__' module|No module named",
        RegexOption.IGNORE_CASE,
    )

    private const val BINARY_REFRESH_MS: Long = 1L * 24L * 60L * 60L * 1_000L

    @Volatile
    private var resolved: String? = null
    private val updateChecked = atomic(false)

    /**
     * Which `yt-dlp` comes first: `auto` / `bundled` (the zipapp or the bundled binary, downloaded when missing; an
     * installed one only if neither can be had) or `system` (an installed one, else the bundled binary). Set by the game (the server's `client.ytDlpSource`); the `dreamdisplays.ytdlp` override still
     * wins. A change applies to resolves started after it.
     */
    @Volatile
    var preferredSource: () -> String = { "auto" }

    /** The [preferredSource] [resolved] / [resolvedCommand] were picked for. */
    @Volatile
    private var resolvedFor: String? = null

    private fun preference(): String = runCatching(preferredSource).getOrDefault("auto")

    /** Drops the picked command when the preference changed since it was picked. Under the lock. */
    private fun forgetIfPreferenceChanged(preference: String) {
        if (resolvedFor == null || resolvedFor == preference) return
        logger.info("yt-dlp source preference is now {}; picking yt-dlp again.", preference)
        resolved = null
        resolvedCommand = null
        resolvedFor = null
    }

    @Volatile
    private var resolvedCommand: List<String>? = null

    @Volatile
    private var pythonProbe: String? = null
    private val zipappUpdateChecked = atomic(false)

    /** `python --version` of the interpreter picked for the zipapp. */
    @Volatile
    private var pythonVersionText: String = ""

    /** Interpreters found but too old for `yt-dlp` (`python3 3.9.6`), for diagnostics. */
    private val skippedPythons = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** Why the zipapp fast path is not in use, for diagnostics. */
    @Volatile
    private var zipappNote: String = ""

    @Volatile
    private var versionProbe: Pair<List<String>, String>? = null

    /** What runs `yt-dlp` here, for client diagnostics. Paths are raw (the reporter scrubs them). */
    data class Info(
        val mode: String,
        val version: String,
        val path: String,
        val python: String,
        val pythonVersion: String,
        val note: String,
    )

    /** Step label for resolve reports: `yt-dlp zipapp`, `yt-dlp binary`, or `yt-dlp` before the command is known. */
    fun label(): String = resolvedCommand?.let { if (isZipapp(it)) "yt-dlp zipapp" else "yt-dlp binary" } ?: "yt-dlp"

    /**
     * Describes the `yt-dlp` in use without provisioning one: blocks for a `--version` run the first time (call it off
     * the client thread). Before the first fetch the mode is `not probed`.
     */
    fun describe(): Info {
        val command = resolvedCommand
        val note = buildList {
            if (zipappNote.isNotEmpty()) add(zipappNote)
            if (skippedPythons.isNotEmpty()) add("too old for yt-dlp: ${skippedPythons.joinToString(", ")}")
        }.joinToString("; ")
        if (command == null) return Info("not probed", "", "", "", "", note)
        val zipapp = isZipapp(command)
        val mode = when {
            override() != null -> "override"
            zipapp -> "zipapp"
            else -> "binary"
        }
        val version = versionProbe?.takeIf { it.first == command }?.second ?: runCatching {
            val (exit, out) = run(command + "--version", 20)
            val text = if (exit == 0) out.trim().lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
            else "failed (exit ${exit ?: "timeout"}): ${out.trim().lineSequence().lastOrNull { it.isNotBlank() }?.take(120)}"
            text.take(160)
        }.getOrElse { e -> "failed: ${e.message}" }.also { versionProbe = command to it }
        return Info(
            mode = mode,
            version = version,
            path = if (zipapp) command[1] else command[0],
            python = if (zipapp) command[0] else "",
            pythonVersion = if (zipapp) pythonVersionText.removePrefix("Python ").trim() else "",
            note = note,
        )
    }

    /**
     * Returns the path to a usable `yt-dlp` binary: the `dreamdisplays.ytdlp` override, else the bundled copy (downloaded
     * when missing; it updates itself), else a system install. A system `yt-dlp` only comes after the bundled one unless
     * [preferredSource] is `system`: a distro or package-manager copy is often far behind YouTube and never updated by us.
     */
    @Throws(IOException::class)
    fun resolve(): String {
        val preference = preference()
        resolved?.takeIf { resolvedFor == preference }?.let { return it }
        synchronized(this) {
            forgetIfPreferenceChanged(preference)
            resolved?.let { return it }
            resolvedFor = preference

            val bundled = bundledDir.resolve(bundledBinaryName())
            val pick = pickBinary(
                override(),
                bundled.toString(),
                CANDIDATE_PATHS.asList(),
                ::canExecute,
                preferSystem = preference == "system",
                answers = ::answersVersion,
                provisionBundled = { download(bundled) },
            )
            resolved = pick
            if (pick == bundled.toString()) maybeSelfUpdate(bundled)
            return pick
        }
    }

    /**
     * The order [resolve] tries: [override] when it runs, the [bundled] copy when present, a fresh download of it
     * ([provisionBundled]), and only then the [system] installs ([preferSystem]: those first). Throws the download's
     * error when nothing runs.
     *
     * The bundled copy must also [answers] (`--version`): a release binary that cannot run here (musl, NixOS, a no-exec
     * folder) gives way to a system install that runs, and is kept only when there is none (as before).
     */
    internal fun pickBinary(
        override: String?,
        bundled: String,
        system: List<String>,
        runs: (String) -> Boolean,
        preferSystem: Boolean = false,
        answers: (String) -> Boolean = { true },
        provisionBundled: () -> String,
    ): String {
        if (override != null && runs(override)) return override
        if (preferSystem) system.firstOrNull(runs)?.let { return it }
        fun orSystem(path: String): String {
            if (answers(path)) return path
            system.firstOrNull(runs)?.let {
                logger.warn("Bundled yt-dlp {} does not run here; using system {}.", path, it)
                return it
            }
            return path
        }
        if (runs(bundled)) return orSystem(bundled)
        val failure = try {
            return orSystem(provisionBundled())
        } catch (e: IOException) {
            e
        }
        system.firstOrNull(runs)?.let {
            logger.warn("Bundled yt-dlp unavailable ({}); using system {}.", failure.message, it)
            return it
        }
        throw failure
    }

    /**
     * Returns the command prefix used to launch yt-dlp — `[python3, .../yt-dlp.pyz]` for the fast
     * zipapp path, or a single-element `[binaryPath]` for the PyInstaller binary. Callers append the
     * `yt-dlp` arguments to this list.
     */
    @Throws(IOException::class)
    fun resolveCommand(): List<String> {
        val preference = preference()
        resolvedCommand?.takeIf { resolvedFor == preference }?.let { return it }
        synchronized(this) {
            forgetIfPreferenceChanged(preference)
            resolvedCommand?.let { return it }
            // An installed yt-dlp first: no zipapp (that is the mod's own copy)
            val zipapp = if (override() != null || preference == "system") null else zipappCommand()
            val command = zipapp ?: [resolve()]
            resolvedCommand = command
            resolvedFor = preference
            return command
        }
    }

    /** First `yt-dlp` release with `--js-runtimes`; an older one refuses every call that passes it. */
    private val JS_RUNTIMES_SINCE = [2025, 11, 12]

    /**
     * `--js-runtimes` arguments for the runtime [YtDlpJsRuntime] found, when [command] is a `yt-dlp` new enough to take
     * them (an installed copy can be older); none otherwise. Reads the version once per command.
     */
    fun jsRuntimeArgs(command: List<String>): List<String> {
        val args = YtDlpJsRuntime.args()
        if (args.isEmpty()) return args
        val version = versionProbe?.takeIf { it.first == command }?.second ?: runCatching {
            val (exit, out) = run(command + "--version", 30)
            if (exit == 0) out.trim().lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty().take(160) else null
        }.getOrNull()?.also { versionProbe = command to it }
        return if (takesJsRuntimes(version)) args else []
    }

    /** True when `yt-dlp --version` printed [version] (`2026.10.26`) for a release that has `--js-runtimes`. */
    internal fun takesJsRuntimes(version: String?): Boolean {
        val parts = version?.let { Regex("""^(\d{4})\.(\d{1,2})\.(\d{1,2})""").find(it.trim()) }?.groupValues?.drop(1)
            ?.map { it.toInt() } ?: return false
        for (i in parts.indices) if (parts[i] != JS_RUNTIMES_SINCE[i]) return parts[i] > JS_RUNTIMES_SINCE[i]
        return true
    }

    private fun override(): String? =
        System.getProperty("dreamdisplays.ytdlp")?.ifEmpty { null } ?: System.getenv("DREAMDISPLAYS_YTDLP")?.ifEmpty { null }

    /** True when [command] (from [resolveCommand]) runs the zipapp through Python rather than a standalone binary. */
    fun isZipapp(command: List<String>): Boolean = command.size == 2 && command[1].endsWith(ZIPAPP_NAME)

    /** True when `yt-dlp` output says its Python can't run it at all, as opposed to a failure on one video. */
    fun looksLikeInterpreterFailure(output: String?): Boolean =
        output != null && INTERPRETER_FAILURE.containsMatchIn(output)

    /**
     * Drops the zipapp fast path [failed] for the rest of the session after it failed with [output], switching to the
     * standalone binary. Returns true when the current command is now a different one (so a retry can use it).
     */
    fun abandonZipapp(failed: List<String>, output: String): Boolean {
        synchronized(this) {
            if (resolvedCommand != failed) return resolvedCommand != null
            val reason = output.lineSequence().lastOrNull { it.isNotBlank() }?.trim()?.take(200)
            logger.warn("yt-dlp zipapp can't run on {} ({}); using the standalone yt-dlp binary instead.", failed[0], reason)
            zipappNote = "zipapp failed on ${failed[0]} ($reason); switched to the binary"
            resolvedCommand = runCatching { [resolve()] }.onFailure { e ->
                logger.warn("Standalone yt-dlp binary unavailable: ${e.message}.")
            }.getOrNull() ?: return false
            return true
        }
    }

    private fun zipappCommand(): List<String>? {
        val python = usablePython() ?: run {
            zipappNote = "no Python 3.$MIN_PYTHON_MINOR+ found for the zipapp"
            return null
        }
        val pyz = provisionZipapp() ?: run {
            zipappNote = "zipapp download failed"
            return null
        }
        // A new enough interpreter can still fail to run it (a newer yt-dlp raising its floor, a broken install)
        if (!zipappRuns(python, pyz)) return null
        logger.info("yt-dlp fast path active: {} {}.", python, pyz.fileName)
        return [python, pyz.toString()]
    }

    private fun zipappRuns(python: String, pyz: Path): Boolean {
        val (exit, output) = runCatching { run([python, pyz.toString(), "--version"], 20) }.getOrElse { e ->
            null to (e.message ?: e.javaClass.simpleName)
        }
        if (exit == 0) {
            versionProbe = [python, pyz.toString()] to output.trim().lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
            return true
        }
        zipappNote = "zipapp does not run on $python (exit ${exit ?: "timeout"}: " +
            "${output.trim().lineSequence().lastOrNull { it.isNotBlank() }?.take(160)})"
        logger.warn(
            "yt-dlp zipapp doesn't run on {} (exit {}: {}); using the standalone yt-dlp binary.",
            python, exit ?: "timeout", output.trim().lineSequence().lastOrNull { it.isNotBlank() }?.take(200),
        )
        return false
    }

    /** Runs [cmd], returning its exit code (null on timeout) and combined output. */
    private fun run(cmd: List<String>, timeoutSeconds: Long): Pair<Int?, String> {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        runCatching { p.outputStream.close() }
        val sink = StringBuilder()
        val reader = Processes.collector(p.inputStream, sink, "YtDlp-probe").also { it.start() }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            Processes.destroyTree(p)
            return null to sink.toString()
        }
        reader.join(2_000)
        return p.exitValue() to sink.toString()
    }

    private fun usablePython(): String? {
        pythonProbe?.let { return it.ifEmpty { null } }
        for (candidate in PYTHON_CANDIDATES) {
            if (pythonVersionOk(candidate)) {
                pythonProbe = candidate
                return candidate
            }
        }
        pythonProbe = ""
        return null
    }

    private fun pythonVersionOk(exe: String): Boolean {
        return runCatching {
            val (exit, out) = run([exe, "--version"], 10)
            if (exit != 0) return false
            val ok = pythonVersionSupported(out)
            if (ok) {
                pythonVersionText = out.trim().take(40)
            } else {
                logger.debug("Skipping {} for the yt-dlp zipapp: {}", exe, out.trim().take(80))
                val entry = "$exe ${out.trim().removePrefix("Python ").take(20)}"
                if (entry !in skippedPythons && skippedPythons.size < 8) skippedPythons.add(entry)
            }
            ok
        }.getOrDefault(false)
    }

    /** True when `python --version` printed [versionOutput] for an interpreter that can run `yt-dlp`. */
    internal fun pythonVersionSupported(versionOutput: String): Boolean {
        val m = Regex("""Python (\d+)\.(\d+)""").find(versionOutput) ?: return false
        val major = m.groupValues[1].toInt()
        val minor = m.groupValues[2].toInt()
        return major > 3 || (major == 3 && minor >= MIN_PYTHON_MINOR)
    }

    private fun provisionZipapp(): Path? {
        val pyz = bundledDir.resolve(ZIPAPP_NAME)
        if (Files.isRegularFile(pyz)) {
            maybeRefreshZipapp(pyz)
            return pyz
        }
        return runCatching {
            downloadAsset(pyz, ZIPAPP_ASSET, executable = false)
            pyz
        }.onFailure { e ->
            logger.warn("yt-dlp zipapp download failed; falling back to the bundled binary: ${e.message}.")
        }.getOrNull()
    }

    private fun maybeRefreshZipapp(pyz: Path) {
        if (!zipappUpdateChecked.compareAndSet(expect = false, update = true)) return
        val age = runCatching {
            System.currentTimeMillis() - Files.getLastModifiedTime(pyz).toMillis()
        }.getOrNull() ?: return
        if (age < BINARY_REFRESH_MS) return
        DreamCoroutines.clientIo.launch {
            runCatching {
                logger.debug("Bundled yt-dlp.pyz is ${age / 86_400_000L} days old, refreshing...")
                downloadAsset(pyz, ZIPAPP_ASSET, executable = false)
                logger.debug("yt-dlp.pyz refresh finished.")
            }.onFailure { e ->
                logger.warn("yt-dlp.pyz refresh failed: ${e.message}.")
            }
        }
    }

    private fun maybeSelfUpdate(bundled: Path) {
        if (!updateChecked.compareAndSet(expect = false, update = true)) return
        val age = runCatching {
            System.currentTimeMillis() - Files.getLastModifiedTime(bundled).toMillis()
        }.getOrNull() ?: return
        if (age < BINARY_REFRESH_MS) return
        DreamCoroutines.clientIo.launch {
            runCatching {
                logger.debug("Bundled yt-dlp is ${age / 86_400_000L} days old, running self-update...")
                val p = ProcessBuilder(bundled.toString(), "-U", "--no-warnings")
                    .redirectErrorStream(true).start()
                runCatching { p.outputStream.close() }
                Processes.drainAsync(p.inputStream)
                if (!p.waitFor(120, TimeUnit.SECONDS)) {
                    Processes.destroyTree(p)
                    return@launch
                }
                runCatching { Files.setLastModifiedTime(bundled, FileTime.fromMillis(System.currentTimeMillis())) }
                logger.debug("yt-dlp self-update finished.")
            }.onFailure { e ->
                logger.warn("yt-dlp self-update failed: ${e.message}")
            }
        }
    }

    private fun bundledBinaryName(): String = if (OsInfo.isWindows) "yt-dlp.exe" else "yt-dlp"

    private fun downloadAssetName(): String = when {
        OsInfo.isWindows -> "yt-dlp.exe"
        OsInfo.isMac -> "yt-dlp_macos"
        OsInfo.isArm64 -> "yt-dlp_linux_aarch64"
        OsInfo.isArm -> "yt-dlp_linux_armv7l"
        else -> "yt-dlp_linux"
    }

    @Throws(IOException::class)
    private fun download(target: Path): String {
        downloadAsset(target, downloadAssetName(), executable = true)
        logger.debug("Ready to work.")
        return target.toString()
    }

    @Throws(IOException::class)
    private fun downloadAsset(target: Path, asset: String, executable: Boolean) {
        // Checked against the SHA2-256SUMS published with the same release; a mismatch keeps the current copy
        val base = releaseBase()
        SourceLoader.downloadVerified(base + asset, base + SUMS_ASSET, target, asset, readTimeoutMs = 120_000)
        if (executable && !OsInfo.isWindows) SourceLoader.makeExecutable(target)
    }

    /**
     * `.../releases/download/<tag>/` of the newest release, so an asset and its checksum list always come from one
     * release even when a new one is published in between; `latest/download/` when the tag can't be read.
     */
    private fun releaseBase(): String {
        val location = runCatching {
            DreamHttpClient.peekRedirectLocation(
                "$RELEASES/latest",
                DreamHttpClient.RequestOptions(
                    headers = DreamHttpClient.headersOf("User-Agent" to "DreamDisplays-bootstrap"),
                    connectTimeoutMs = 15_000,
                    readTimeoutMs = 30_000,
                ),
            )
        }.getOrNull()
        return releaseTag(location)?.let { "$RELEASES/download/$it/" } ?: DOWNLOAD_BASE
    }

    /** The tag a `releases/latest` redirect [location] names (`.../releases/tag/2026.10.26`), or null. */
    internal fun releaseTag(location: String?): String? =
        location?.substringAfter("/releases/tag/", "")?.substringBefore('?')?.trim('/')
            ?.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) }

    /** True when [path] runs and answers `--version` (a binary that is present and executable may still not run). */
    private fun answersVersion(path: String): Boolean = runCatching {
        val pb = ProcessBuilder(path, "--version")
        pb.redirectErrorStream(true)
        val p = pb.start()
        runCatching { p.outputStream.close() }
        Processes.drainAsync(p.inputStream)
        // A first run can be slow (a PyInstaller unpack, an antivirus scan): only a clear failure counts
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            Processes.destroyTree(p)
            return@runCatching true
        }
        p.exitValue() == 0
    }.getOrDefault(false)

    private fun canExecute(path: String): Boolean {
        return runCatching {
            val f = File(path)
            if (f.isAbsolute || File.separator in path) return f.isFile && f.canExecute()
            val pb = ProcessBuilder(path, "--version")
            pb.redirectErrorStream(true)
            val p = pb.start()
            runCatching { p.outputStream.close() }
            Processes.drainAsync(p.inputStream)
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                Processes.destroyTree(p)
                return false
            }
            p.exitValue() == 0
        }.getOrDefault(false)
    }
}
