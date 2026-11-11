package com.dreamdisplays.media.runtime.download

import com.dreamdisplays.media.runtime.system.Processes
import com.dreamdisplays.util.net.DreamHttpClient
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.io.path.deleteIfExists
import kotlin.io.path.name
import kotlin.io.path.outputStream

/** Downloads and unpacks the external tools fetched on first use (`FFmpeg`, its shared libraries, `yt-dlp`). */
object SourceLoader {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val USER_AGENT = "DreamDisplays-bootstrap"
    private const val MIB = 1_048_576.0

    /** A download whose bytes do not hash to what its release publishes. */
    class ChecksumMismatchException(message: String) : IOException(message)

    /** Downloads [url] to [dest]; with [expectedSha256], [dest] only appears when the bytes hash to it. */
    @Throws(IOException::class)
    fun download(url: String, dest: Path, label: String, readTimeoutMs: Long = 300_000, expectedSha256: String? = null) {
        Files.createDirectories(dest.parent)
        val part = dest.resolveSibling("${dest.name}.part")
        logger.info("Downloading $label from $url...")
        var lastLoggedPct = -10
        try {
            DreamHttpClient.downloadToFile(
                url, part,
                DreamHttpClient.RequestOptions(
                    headers = DreamHttpClient.headersOf("User-Agent" to USER_AGENT),
                    connectTimeoutMs = 15_000,
                    readTimeoutMs = readTimeoutMs,
                ),
            ) { downloaded, total ->
                val pct = if (total > 0) (downloaded * 100 / total).toInt() / 10 * 10 else return@downloadToFile
                if (pct < lastLoggedPct + 10) return@downloadToFile
                lastLoggedPct = pct
                logger.info("Downloading $label... $pct% (${"%.1f".format(downloaded / MIB)} / ${"%.1f".format(total / MIB)} MB).")
            }
            if (expectedSha256 != null) {
                val actual = sha256(part)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    throw ChecksumMismatchException("$label from $url has SHA-256 $actual, expected $expectedSha256.")
                }
            }
            Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            part.deleteIfExists()
        }
    }

    /**
     * Downloads [url] to [dest] verified against the checksum list at [sumsUrl] (`sha256sum` format, published in the
     * same release). Refuses the file when the list is unreadable or does not name it.
     */
    @Throws(IOException::class)
    fun downloadVerified(url: String, sumsUrl: String, dest: Path, label: String, readTimeoutMs: Long = 300_000) {
        verified(url, sumsUrl) { sha -> download(url, dest, label, readTimeoutMs, sha) }
    }

    /** [downloadAndExtract], with the archive verified against [sumsUrl] like [downloadVerified]. */
    @Throws(IOException::class)
    fun downloadAndExtractVerified(
        url: String, sumsUrl: String, destDir: Path, label: String, select: (entryName: String) -> String?,
    ): Int = verified(url, sumsUrl) { sha -> downloadAndExtract(url, destDir, label, sha, select) }

    /**
     * Runs [attempt] with the SHA-256 [sumsUrl] lists for [url]'s file name. A mismatch is retried once with a fresh
     * list: a rolling release (BtbN's `latest`) can swap its files between the two requests.
     */
    private fun <T> verified(url: String, sumsUrl: String, attempt: (sha256: String) -> T): T {
        val name = url.substringBefore('?').substringAfterLast('/')
        var mismatch: ChecksumMismatchException? = null
        repeat(2) {
            val sha = sha256Sums(sumsUrl)[name] ?: throw IOException("$sumsUrl lists no checksum for $name.")
            try {
                return attempt(sha)
            } catch (e: ChecksumMismatchException) {
                logger.warn(e.message ?: "Checksum mismatch for $name.")
                mismatch = e
            }
        }
        throw mismatch!!
    }

    /** The `sha256sum` list at [url] (file name to lower-case SHA-256). */
    fun sha256Sums(url: String): Map<String, String> =
        parseSha256Sums(
            DreamHttpClient.readText(
                url,
                DreamHttpClient.RequestOptions(
                    headers = DreamHttpClient.headersOf("User-Agent" to USER_AGENT),
                    connectTimeoutMs = 15_000,
                    readTimeoutMs = 30_000,
                ),
            ),
        )

    /**
     * File name to lower-case SHA-256 from `sha256sum` output (`<hex>  <name>`, or `<hex> *<name>` in binary mode), or
     * from PowerShell `Get-FileHash` list output (`Hash : <HEX>` then `Path : C:\...\<name>`, Deno's Windows files).
     */
    internal fun parseSha256Sums(text: String): Map<String, String> =
        text.lineSequence().mapNotNull { line ->
            val m = SUM_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
            m.groupValues[2] to m.groupValues[1].lowercase()
        }.toMap() + POWERSHELL_HASH.findAll(text).associate { m ->
            m.groupValues[2].trim().substringAfterLast('\\').substringAfterLast('/') to m.groupValues[1].lowercase()
        }

    private val SUM_LINE = Regex("""([0-9a-fA-F]{64})\s+\*?(\S.*)""")
    private val POWERSHELL_HASH = Regex("""(?m)^Hash\s*:\s*([0-9a-fA-F]{64})\s*\r?\n\s*Path\s*:\s*(\S[^\r\n]*)""")

    fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Extracts the entries. */
    @Throws(IOException::class)
    fun extract(archive: Path, destDir: Path, select: (entryName: String) -> String?): Int {
        Files.createDirectories(destDir)
        var count = 0
        val root = destDir.toAbsolutePath().normalize()
        fun write(name: String, input: InputStream) {
            val target = select(name) ?: return
            val dest = root.resolve(target).normalize()
            if (!dest.startsWith(root) || dest == root) throw IOException("Archive entry escapes $destDir: $name")
            // Unpacked next to the target and moved into place whole: an interrupted unpack (game closed, disk
            // full, corrupt archive) must never leave a truncated binary that later runs pass as installed.
            val part = dest.resolveSibling("${dest.name}.part")
            try {
                part.outputStream().buffered().use { input.transferTo(it) }
                Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                part.deleteIfExists()
            }
            count++
        }
        Files.newInputStream(archive).buffered().use { raw ->
            if (archive.name.endsWith(".zip")) {
                ZipInputStream(raw).use { zip ->
                    generateSequence { zip.nextEntry }
                        .filter { !it.isDirectory && !it.name.startsWith("__MACOSX/") }
                        .forEach { write(it.name, zip) }
                }
            } else {
                TarArchiveInputStream(XZCompressorInputStream(raw)).use { tar ->
                    generateSequence { tar.nextEntry }.filter { it.isFile }.forEach { write(it.name, tar) }
                }
            }
        }
        return count
    }

    /**
     * Downloads the archive at [url], extracts it with [select] into [destDir], deletes the archive, and
     * returns the entry count.
     */
    @Throws(IOException::class)
    fun downloadAndExtract(
        url: String, destDir: Path, label: String, expectedSha256: String? = null, select: (entryName: String) -> String?,
    ): Int {
        val archive = destDir.resolve("_download" + if (url.endsWith(".zip")) ".zip" else ".tar.xz")
        try {
            download(url, archive, label, expectedSha256 = expectedSha256)
            return extract(archive, destDir, select).also { logger.info("$label ready ($it files unpacked).") }
        } finally {
            archive.deleteIfExists()
        }
    }

    /** Marks [file] executable and drops the macOS quarantine flag so it can be spawned. */
    fun makeExecutable(file: Path) {
        Processes.markExecutable(file)
        Processes.removeMacQuarantine(file)
    }
}
