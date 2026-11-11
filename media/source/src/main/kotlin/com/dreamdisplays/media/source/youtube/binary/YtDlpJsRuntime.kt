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
 * When none is installed, Deno is downloaded from its GitHub releases (checked against the checksum published with
 * each asset) into `libs/deno`. Never from a server-supplied URL.
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

    /** Where the downloaded Deno lives, next to the bundled `yt-dlp`. */
    val dir: Path = Path.of("libs", "deno")

    private val binaryName: String get() = if (OsInfo.isWindows) "deno.exe" else "deno"

    @Volatile
    private var selected: Runtime? = null

    @Volatile
    private var detected = false

    /** `not probed`, `system`, `downloaded`, `downloading`, `missing` (with [note] saying why). */
    @Volatile
    private var state = "not probed"

    @Volatile
    private var note = ""

    @Volatile
    private var downloadFailedAtMs = 0L

    @Volatile
    private var downloading = false

    /**
     * Arguments to add to every `yt-dlp` call: `--js-runtimes name:path` for the runtime in use, none while there is
     * none. Detects installed runtimes the first time (a few `--version` runs); never waits for a Deno download, which
     * starts in the background when nothing is installed.
     */
    fun args(): List<String> = argsFor(current())

    /** The runtime in use, detecting it the first time; starts a Deno download when there is none. */
    fun current(): Runtime? {
        if (detected) return selected ?: run { maybeDownload(); null }
        synchronized(this) {
            if (!detected) {
                selected = detect()
                detected = true
                selected?.let {
                    state = it.source
                    logger.info("yt-dlp JavaScript runtime: {} {} ({}, {}).", it.name, it.version, it.source, it.path)
                }
            }
        }
        return selected ?: run { maybeDownload(); null }
    }

    /** For the client environment report: `deno 2.9.8, <path>`, and where it came from (or why there is none). */
    fun describe(): Pair<String, String> {
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

    /** The previously downloaded Deno, when it still runs. */
    private fun downloaded(quiet: Boolean = false): Runtime? {
        val binary = dir.resolve(binaryName).toAbsolutePath()
        if (!Files.isRegularFile(binary)) return null
        val version = versionOutput(binary.toString(), timeoutSeconds = 60)?.let { parseVersion("deno", it) }
        if (version == null || !supported("deno", version)) {
            if (!quiet) logger.warn("Downloaded Deno at {} does not run (or is too old); downloading it again.", binary)
            return null
        }
        return Runtime("deno", binary.toString(), version.joinToString("."), "downloaded")
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

    private fun maybeDownload() {
        if (downloading) return
        if (downloadFailedAtMs != 0L && System.currentTimeMillis() - downloadFailedAtMs < RETRY_AFTER_FAILURE_MS) return
        synchronized(this) {
            if (downloading || selected != null) return
            downloading = true
            state = "downloading"
            note = ""
        }
        DreamCoroutines.clientIo.launch {
            try {
                val runtime = provision()
                selected = runtime
                state = "downloaded"
                note = ""
                logger.info("yt-dlp JavaScript runtime: deno {} (downloaded, {}).", runtime.version, runtime.path)
            } catch (e: Exception) {
                downloadFailedAtMs = System.currentTimeMillis()
                state = "missing"
                note = "Deno download failed: ${e.message?.take(160)}"
                logger.warn("No JavaScript runtime for yt-dlp; Deno download failed: {}", e.message)
            } finally {
                downloading = false
            }
        }
    }

    /** Downloads Deno (latest release, else [PINNED_TAG]), unpacks it in place atomically and checks that it runs. */
    @Throws(IOException::class)
    private fun provision(): Runtime {
        val asset = denoAsset(OsInfo.isWindows, OsInfo.isMac, OsInfo.isArm64, OsInfo.isArm)
            ?: throw IOException("Deno publishes no build for ${System.getProperty("os.name")} ${System.getProperty("os.arch")}.")
        val bases = listOfNotNull(latestBase(), "$RELEASES/download/$PINNED_TAG/").distinct()
        var last: IOException? = null
        for (base in bases) {
            try {
                // The zip holds just the binary; each asset has `<asset>.sha256sum` beside it
                SourceLoader.downloadAndExtractVerified(base + asset, "$base$asset.sha256sum", dir, "Deno") { entry ->
                    binaryName.takeIf { entry == binaryName }
                }
                val binary = dir.resolve(binaryName)
                if (!Files.isRegularFile(binary)) throw IOException("$asset holds no $binaryName.")
                if (!OsInfo.isWindows) SourceLoader.makeExecutable(binary)
                return downloaded(quiet = true) ?: throw IOException("Deno from $base does not run here.")
            } catch (e: IOException) {
                logger.warn("Deno from {} failed: {}", base + asset, e.message)
                last = e
            }
        }
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
