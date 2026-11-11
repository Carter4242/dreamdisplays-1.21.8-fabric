package com.dreamdisplays.media.source.youtube.binary

import com.dreamdisplays.media.runtime.download.SourceLoader
import com.dreamdisplays.media.runtime.system.Processes
import com.dreamdisplays.util.DreamCoroutines
import com.dreamdisplays.util.OsInfo
import com.dreamdisplays.util.net.DreamHttpClient
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The JavaScript runtime `yt-dlp` solves YouTube's player challenges with. Without one some videos ("made for kids")
 * resolve to nothing and others lose formats. `yt-dlp` only looks for Deno on its own, and only on `PATH` (which a
 * game launcher often trims), so an installed Deno, Node or Bun is found here and passed as `--js-runtimes name:path`.
 *
 * When none is installed, Deno (about 40 MB) is downloaded only once `yt-dlp` fails in a way one fixes
 * ([requestDownload], [needsRuntime]), never at launch: from its GitHub releases (checked against the checksum
 * published with each asset) into `libs/deno`, never from a server-supplied URL. A download that does not run here is
 * remembered ([DOWNLOAD_RECORD]) and not fetched again. The server can turn all of it off ([policy]).
 */
object YtDlpJsRuntime {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** A runtime that answered `--version` with a version `yt-dlp` supports. [source] is `system` or `downloaded`. */
    data class Runtime(val name: String, val path: String, val version: String, val source: String)

    /** `yt-dlp`'s preference order, highest first (QuickJS is left out: far slower, and rarely installed). */
    internal val PRIORITY = ["deno", "node", "bun"]

    /** The oldest version of each runtime `yt-dlp` accepts (`yt_dlp/utils/_jsruntime.py`, 2026.10.26). */
    internal val MIN_VERSIONS: Map<String, List<Int>> = mapOf(
        "deno" to [2, 3, 0],
        "node" to [22, 0, 0],
        "bun" to [1, 2, 11],
    )

    private const val RELEASES = "https://github.com/denoland/deno/releases"

    /** Used when `latest` can't be read or its assets aren't all uploaded yet; still checked against its published sums. */
    private const val PINNED_TAG = "v2.9.8"

    /** A failed Deno download (about 40 MB) is not tried again for this long. */
    private const val RETRY_AFTER_FAILURE_MS = 30L * 60L * 1_000L

    /**
     * Kept beside the downloaded Deno: `<sha256 of the binary> ok|fails`, whether it ran when downloaded. A Deno that
     * does not run now and is still that download is not downloaded again (the same bytes fail the same way).
     */
    private const val DOWNLOAD_RECORD = ".download"

    /**
     * `auto` (an installed runtime, else Deno downloaded when `yt-dlp` needs one) or `off` (no runtime at all: `yt-dlp`
     * is told to use none, not even one it would find itself, and nothing is downloaded). Set by the game (the server's
     * `client.ytDlpJsRuntime`); applies to `yt-dlp` runs started after a change.
     */
    @Volatile
    var policy: () -> String = { "auto" }

    private fun turnedOff(): Boolean = runCatching(policy).getOrDefault("auto") == "off"

    /** What `yt-dlp` gets while the server turned runtimes off. */
    internal val NO_RUNTIMES: List<String> = ["--no-js-runtimes"]

    /** Where the downloaded Deno lives, next to the bundled `yt-dlp`. */
    val dir: Path = Path.of("libs", "deno")

    private val binaryName: String get() = if (OsInfo.isWindows) "deno.exe" else "deno"

    @Volatile
    private var selected: Runtime? = null

    @Volatile
    private var detected = false

    /** `not probed`, `system`, `downloaded`, `downloading`, `none`, `missing` (with [note] saying why). */
    @Volatile
    private var state = "not probed"

    /** The downloaded Deno does not run on this machine (see [DOWNLOAD_RECORD]): never downloaded again. */
    @Volatile
    private var unrunnable = false

    @Volatile
    private var note = ""

    @Volatile
    private var downloadFailedAtMs = 0L

    @Volatile
    private var downloading = false

    /**
     * Arguments to add to every `yt-dlp` call: `--js-runtimes name:path` for the runtime in use, none while there is
     * none, [NO_RUNTIMES] while the server turned runtimes off. Detects installed runtimes the first time (a few
     * `--version` runs); never downloads anything.
     */
    fun args(): List<String> = if (turnedOff()) NO_RUNTIMES else argsFor(current())

    /**
     * The runtime in use (null while the server turned runtimes off), detecting it the first time. One whose file has
     * gone since (an antivirus quarantine, an uninstall) is looked for again. Never downloads anything.
     */
    fun current(): Runtime? {
        if (turnedOff()) return null
        selected?.let { r ->
            if (File(r.path).isFile) return r
            forget(r)
        }
        if (detected) return selected
        synchronized(this) {
            if (!detected) {
                selected = detect()
                detected = true
                selected?.let {
                    state = it.source
                    logger.info("yt-dlp JavaScript runtime: {} {} ({}, {}).", it.name, it.version, it.source, it.path)
                } ?: run {
                    if (state == "not probed") state = "none"
                }
            }
        }
        return selected
    }

    private fun forget(gone: Runtime) {
        synchronized(this) {
            if (selected != gone) return
            logger.warn("yt-dlp JavaScript runtime {} at {} is gone; looking for one again.", gone.name, gone.path)
            selected = null
            detected = false
            state = "not probed"
        }
    }

    /**
     * `yt-dlp` failed with [output] for want of a JavaScript runtime ([needsRuntime]) and there is none: downloads Deno in
     * the background, unless the server turned runtimes off, Deno is known not to run here, or a download failed in the
     * last [RETRY_AFTER_FAILURE_MS]. True while a download is under way (started now or before).
     */
    fun requestDownload(output: String): Boolean {
        if (turnedOff() || current() != null) return false
        if (downloading) return true
        if (unrunnable) return false
        if (downloadFailedAtMs != 0L && System.currentTimeMillis() - downloadFailedAtMs < RETRY_AFTER_FAILURE_MS) return false
        val reason = output.lineSequence().lastOrNull { it.isNotBlank() }?.trim()?.take(160).orEmpty()
        logger.info("yt-dlp needs a JavaScript runtime ({}); downloading Deno.", reason)
        return startDownload()
    }

    /** True while a Deno download is under way: a video that needs it plays once it is in. */
    val isDownloading: Boolean get() = downloading

    /**
     * True when `yt-dlp`'s error [output] says a JavaScript runtime is missing: its own runtime and challenge messages,
     * or "This video is not available", which is what it answers for a "made for kids" video without one (those play
     * only through a client that needs the runtime).
     */
    internal fun needsRuntime(output: String?): Boolean {
        val text = output?.lowercase() ?: return false
        return NEEDS_RUNTIME.any { it in text }
    }

    private val NEEDS_RUNTIME = [
        "this video is not available",
        "javascript runtime",
        "js runtime",
        "challenge solving failed",
        "signature solving failed",
    ]

    /** For the client environment report: `deno 2.9.8, <path>`, and where it came from (or why there is none). */
    fun describe(): Pair<String, String> {
        if (turnedOff()) return "" to "off (server)"
        val r = selected
        val source = when {
            r != null -> r.source
            note.isNotEmpty() -> "$state ($note)"
            else -> state
        }
        return (r?.let { "${it.name} ${it.version}, ${it.path}" } ?: "") to source
    }

    internal fun argsFor(runtime: Runtime?): List<String> =
        runtime?.let { ["--js-runtimes", "${it.name}:${it.path}"] } ?: []

    private fun detect(): Runtime? {
        val tooOld = ArrayList<String>()
        val found = select(
            candidates(System.getenv(), System.getProperty("user.home").orEmpty(), OsInfo.isWindows),
            probe = ::versionOutput,
            tooOld = tooOld,
        )
        if (found != null) return found
        downloaded()?.let { return it }
        if (tooOld.isNotEmpty()) note = "too old for yt-dlp: ${tooOld.joinToString(", ")}"
        return null
    }

    /**
     * The previously downloaded Deno, when it still runs. One that does not and is still exactly what was downloaded
     * ([DOWNLOAD_RECORD]) marks Deno as not running here, so it is not downloaded again.
     */
    private fun downloaded(): Runtime? {
        val binary = dir.resolve(binaryName).toAbsolutePath()
        if (!Files.isRegularFile(binary)) return null
        runs(binary)?.let { return it }
        val record = runCatching { Files.readString(dir.resolve(DOWNLOAD_RECORD)) }.getOrNull()
        val sha = runCatching { SourceLoader.sha256(binary) }.getOrNull()
        if (recordedResult(record, sha) != null) {
            unrunnable = true
            state = "missing"
            note = "the downloaded Deno does not run here"
            logger.warn("Downloaded Deno at {} does not run here; it is not downloaded again.", binary)
        } else {
            logger.warn("Downloaded Deno at {} does not run; it is downloaded again if yt-dlp needs a runtime.", binary)
        }
        return null
    }

    /** [binary] as a [Runtime] when it runs and is a Deno `yt-dlp` accepts, else null. */
    private fun runs(binary: Path): Runtime? {
        val version = versionOutput(binary.toString(), timeoutSeconds = 60)?.let { parseVersion("deno", it) }
            ?.takeIf { supported("deno", it) } ?: return null
        return Runtime("deno", binary.toString(), version.joinToString("."), "downloaded")
    }

    /** `ok` / `fails` from a [DOWNLOAD_RECORD] [record] when it is about the binary whose SHA-256 is [sha], else null. */
    internal fun recordedResult(record: String?, sha: String?): String? {
        if (record == null || sha == null) return null
        val parts = record.trim().split(' ', limit = 2)
        if (parts.size != 2 || !parts[0].equals(sha, ignoreCase = true)) return null
        return parts[1].trim().takeIf { it == "ok" || it == "fails" }
    }

    private fun writeRecord(binary: Path, ok: Boolean) {
        runCatching {
            Files.writeString(dir.resolve(DOWNLOAD_RECORD), "${SourceLoader.sha256(binary)} ${if (ok) "ok" else "fails"}")
        }.onFailure { logger.debug("Could not record the Deno download: {}", it.message) }
    }

    /**
     * Every place a runtime may be, highest priority first: each [PRIORITY] name on `PATH` (from [env]), then the
     * usual install folders, which a launcher-started game often lacks on `PATH` (Homebrew, `~/.deno/bin`, nvm).
     */
    internal fun candidates(env: Map<String, String>, home: String, windows: Boolean): List<Pair<String, String>> {
        val sep = if (windows) ";" else ":"
        val pathDirs = (env["PATH"] ?: env["Path"] ?: env.entries.firstOrNull { it.key.equals("PATH", true) }?.value)
            .orEmpty().split(sep).map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        val localAppData = env["LOCALAPPDATA"].orEmpty()
        val programFiles = env["ProgramFiles"] ?: "C:\\Program Files"
        fun join(dir: String, file: String) = if (windows) "${dir.trimEnd('\\', '/')}\\$file" else "${dir.trimEnd('/')}/$file"
        val unixCommon = ["/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/snap/bin"]
        val out = ArrayList<Pair<String, String>>()
        for (name in PRIORITY) {
            val exe = if (windows) "$name.exe" else name
            val dirs = ArrayList(pathDirs)
            if (home.isNotEmpty()) dirs += join(home, ".$name${if (windows) "\\bin" else "/bin"}") // ~/.deno/bin, ~/.bun/bin
            if (windows) {
                if (name == "node") dirs += join(programFiles, "nodejs")
                if (localAppData.isNotEmpty()) dirs += join(localAppData, "Microsoft\\WinGet\\Links")
                if (home.isNotEmpty()) dirs += join(home, "scoop\\shims")
                if (name == "node" && localAppData.isNotEmpty()) dirs += join(localAppData, "Volta\\bin")
            } else {
                dirs += unixCommon
                if (name == "node" && home.isNotEmpty()) {
                    dirs += join(home, ".volta/bin")
                    // nvm: newest installed version first
                    File(join(home, ".nvm/versions/node")).listFiles()?.filter { it.isDirectory }
                        ?.sortedByDescending { d -> parseVersion("node", d.name)?.let { it[0] * 1_000_000L + it[1] * 1_000L + it[2] } ?: -1L }
                        ?.forEach { dirs += join(home, ".nvm/versions/node/${it.name}/bin") }
                }
            }
            dirs.distinct().forEach { d -> out += name to join(d, exe) }
        }
        return out.distinctBy { it.second.lowercase() }
    }

    /**
     * The first of [candidates] (name to path) that exists ([exists]) and whose [probe] (`--version` output, null when it
     * doesn't run) names a version `yt-dlp` supports. Runtimes found but too old are added to [tooOld].
     */
    internal fun select(
        candidates: List<Pair<String, String>>,
        probe: (path: String) -> String?,
        exists: (path: String) -> Boolean = { File(it).isFile },
        tooOld: MutableList<String> = ArrayList(),
    ): Runtime? {
        for ((name, path) in candidates) {
            if (!exists(path)) continue
            val version = probe(path)?.let { parseVersion(name, it) } ?: continue
            if (!supported(name, version)) {
                tooOld += "$name ${version.joinToString(".")}"
                continue
            }
            return Runtime(name, path, version.joinToString("."), "system")
        }
        return null
    }

    /** The version in a runtime's `--version` output (`deno 2.9.8 (stable...)`, `v24.1.0`, `1.2.19`). */
    internal fun parseVersion(name: String, output: String): List<Int>? {
        val line = output.trim().lineSequence().firstOrNull()?.trim() ?: return null
        val pattern = when (name) {
            "deno" -> Regex("""^deno (\d+)\.(\d+)\.(\d+)""")
            "node" -> Regex("""^v?(\d+)\.(\d+)\.(\d+)""")
            else -> Regex("""^(\d+)\.(\d+)\.(\d+)""")
        }
        val m = pattern.find(line) ?: return null
        return m.groupValues.drop(1).map { it.toInt() }
    }

    internal fun supported(name: String, version: List<Int>): Boolean {
        val min = MIN_VERSIONS[name] ?: return false
        for (i in min.indices) {
            val v = version.getOrElse(i) { 0 }
            if (v != min[i]) return v > min[i]
        }
        return true
    }

    /** Deno's release asset for this OS / arch, or null where none is published (32-bit ARM). */
    internal fun denoAsset(windows: Boolean, mac: Boolean, arm64: Boolean, arm: Boolean): String? = when {
        windows -> if (arm64) "deno-aarch64-pc-windows-msvc.zip" else "deno-x86_64-pc-windows-msvc.zip"
        mac -> if (arm) "deno-aarch64-apple-darwin.zip" else "deno-x86_64-apple-darwin.zip"
        arm64 -> "deno-aarch64-unknown-linux-gnu.zip"
        arm -> null
        else -> "deno-x86_64-unknown-linux-gnu.zip"
    }

    private fun startDownload(): Boolean {
        synchronized(this) {
            if (downloading) return true
            if (selected != null) return false
            downloading = true
            state = "downloading"
            note = ""
        }
        DreamCoroutines.clientIo.launch {
            try {
                val runtime = provision()
                selected = runtime
                detected = true
                state = "downloaded"
                note = ""
                logger.info("yt-dlp JavaScript runtime: deno {} (downloaded, {}).", runtime.version, runtime.path)
            } catch (e: Exception) {
                if (!unrunnable) downloadFailedAtMs = System.currentTimeMillis()
                state = "missing"
                note = if (unrunnable) "the downloaded Deno does not run here" else "Deno download failed: ${e.message?.take(160)}"
                logger.warn("No JavaScript runtime for yt-dlp; {}", note)
            } finally {
                downloading = false
            }
        }
        return true
    }

    /** Downloads Deno (latest release, else [PINNED_TAG]), unpacks it in place atomically and checks that it runs. */
    @Throws(IOException::class)
    private fun provision(): Runtime {
        val asset = denoAsset(OsInfo.isWindows, OsInfo.isMac, OsInfo.isArm64, OsInfo.isArm)
            ?: throw IOException("Deno publishes no build for ${System.getProperty("os.name")} ${System.getProperty("os.arch")}.")
        val bases = listOfNotNull(latestBase(), "$RELEASES/download/$PINNED_TAG/").distinct()
        var last: IOException? = null
        var ranNone = true
        for (base in bases) {
            try {
                // The zip holds just the binary; each asset has `<asset>.sha256sum` beside it
                SourceLoader.downloadAndExtractVerified(base + asset, "$base$asset.sha256sum", dir, "Deno") { entry ->
                    binaryName.takeIf { entry == binaryName }
                }
                val binary = dir.resolve(binaryName).toAbsolutePath()
                if (!Files.isRegularFile(binary)) throw IOException("$asset holds no $binaryName.")
                if (!OsInfo.isWindows) SourceLoader.makeExecutable(binary)
                val runtime = runs(binary)
                writeRecord(binary, ok = runtime != null)
                return runtime ?: throw IOException("Deno from $base does not run here.")
            } catch (e: IOException) {
                logger.warn("Deno from {} failed: {}", base + asset, e.message)
                if (e.message?.endsWith("does not run here.") != true) ranNone = false
                last = e
            }
        }
        // Every release tried downloaded and failed to run: the same files would fail the same way next time
        if (ranNone && last != null) unrunnable = true
        throw last ?: IOException("No Deno release to download.")
    }

    /** `.../releases/download/<tag>/` of the newest Deno release, or null when the tag can't be read. */
    private fun latestBase(): String? {
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
        return YtDlpBinary.releaseTag(location)?.let { "$RELEASES/download/$it/" }
    }

    /** `[path] --version` output, or null when it doesn't run or fails. */
    private fun versionOutput(path: String, timeoutSeconds: Long = 15): String? = runCatching {
        val p = ProcessBuilder(path, "--version").redirectErrorStream(true).start()
        runCatching { p.outputStream.close() }
        val sink = StringBuilder()
        val reader = Processes.collector(p.inputStream, sink, "JsRuntime-probe").also { it.start() }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            Processes.destroyTree(p)
            return@runCatching null
        }
        reader.join(2_000)
        sink.toString().takeIf { p.exitValue() == 0 }
    }.getOrNull()
}
