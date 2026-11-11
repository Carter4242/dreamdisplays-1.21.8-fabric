package com.dreamdisplays.media.source.youtube.process

import com.dreamdisplays.media.runtime.diagnostics.ResolveTrail
import com.dreamdisplays.media.runtime.system.Processes
import com.dreamdisplays.media.source.youtube.binary.YtDlpBinary
import com.dreamdisplays.media.source.youtube.binary.YtDlpOutputParser
import com.dreamdisplays.media.source.youtube.cookie.YtCookieManager
import com.dreamdisplays.media.source.youtube.model.YtStream
import com.dreamdisplays.media.source.youtube.model.YtStreams
import com.dreamdisplays.media.source.youtube.YtResolvePlan
import com.dreamdisplays.util.DreamCoroutines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Runs and races `yt-dlp` subprocesses for a single fetch, using [cookies] for the cookie/proxy
 * path. Extracted from [com.dreamdisplays.media.source.youtube.YtDlp] to isolate the subprocess
 * mechanics from its caching concerns.
 */
class YtDlpClientRace(private val cookies: YtCookieManager) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Per-invocation `yt-dlp` wait (keep short to surface failures fast). */
        const val FETCH_TIMEOUT_SECONDS: Long = 25L

        private const val MAX_REMEMBERED_ERRORS = 64
        private const val MAX_WARNED_VIDEOS = 256
    }

    /** Last `yt-dlp` error output per video, so a total failure can say why (see [YtDlpErrors]). */
    private val lastErrors = ConcurrentHashMap<String, String>()

    /** Videos whose first `yt-dlp` failure was already logged at WARN; later ones stay at DEBUG. */
    private val warnedVideos = ConcurrentHashMap.newKeySet<String>()

    /** Takes (and forgets) the last error `yt-dlp` printed for [videoUrl]. */
    fun takeLastError(videoUrl: String): String? = lastErrors.remove(videoUrl)

    private fun rememberError(videoUrl: String, client: String?, e: Throwable, abandoned: Boolean) {
        // A process killed because another source already won is not a failure worth a warning
        if (abandoned) return
        logFailure(videoUrl, client, e)
        ResolveTrail.record(videoUrl, YtDlpBinary.label(), client ?: "cookies", ok = false, error = e.message ?: e.javaClass.simpleName)
        e.message?.let { ResolveTrail.recordStderr(videoUrl, it) }
        val message = e.message ?: return
        if (lastErrors.size >= MAX_REMEMBERED_ERRORS) lastErrors.clear()
        // Keep an error that explains the failure over a later, vaguer one from another client
        lastErrors.merge(videoUrl, message) { old, new ->
            if (YtDlpErrors.classify(old) != null && YtDlpErrors.classify(new) == null) old else new
        }
    }

    private fun recordSuccess(videoUrl: String, client: String?, streams: List<YtStream>) {
        val usable = YtStreams.usable(streams)
        ResolveTrail.record(
            videoUrl, YtDlpBinary.label(), client ?: "cookies", ok = usable,
            error = if (usable) null else "no usable streams (${streams.size} formats)",
        )
    }

    /** Logs a client failure: the first one per video at WARN (so a broken `yt-dlp` shows up in any log), later ones at DEBUG. */
    private fun logFailure(videoUrl: String, client: String?, e: Throwable) {
        val message = e.message?.take(300)
        if (warnedVideos.size >= MAX_WARNED_VIDEOS) warnedVideos.clear()
        if (warnedVideos.add(videoUrl)) {
            logger.warn("yt-dlp client {} failed for {}: {}", client ?: "cookies", videoUrl, message)
        } else {
            logger.debug("yt-dlp client {} failed for {}: {}", client ?: "cookies", videoUrl, message)
        }
    }

    /**
     * Resolves [videoUrl] via `yt-dlp` following [plan]: its primary client alone first (one request, fast; by default
     * `visionos`, the only client that isn't PO-token gated), then its fallback clients in parallel if that fails.
     * [onProcess] receives every spawned subprocess so the caller can kill stragglers once it no longer needs this branch.
     */
    suspend fun resolve(
        videoUrl: String,
        plan: YtResolvePlan = YtResolvePlan.DEFAULT,
        onProcess: (Process) -> Unit,
    ): List<YtStream>? {
        lastErrors.remove(videoUrl) // an earlier fetch's errors must not explain this one
        if (!cookies.disabledByConfig()) {
            return runCatchingClient(videoUrl, null, onProcess)?.takeIf { YtStreams.usable(it) }
        }
        plan.primaryClient?.let { primary ->
            runCatchingClient(videoUrl, primary, onProcess)?.takeIf { YtStreams.usable(it) }?.let { return it }
        }
        if (plan.fallbackClients.isEmpty()) return null
        return raceParallel(videoUrl, plan.fallbackClients, onProcess)
    }

    private suspend fun runCatchingClient(videoUrl: String, client: String?, onProcess: (Process) -> Unit): List<YtStream>? {
        val result = runCatching { runClientFetch(videoUrl, client, onStarted = onProcess) }
        result.exceptionOrNull()?.let { e -> rememberError(videoUrl, client, e, abandoned = !currentCoroutineContext().isActive) }
        result.getOrNull()?.let { recordSuccess(videoUrl, client, it) }
        return result.getOrNull()
    }

    internal suspend fun raceParallel(
        videoUrl: String,
        clients: List<String?>,
        onProcess: (Process) -> Unit,
        fetch: (videoUrl: String, client: String?, onStarted: (Process) -> Unit) -> List<YtStream> =
            { url, client, onStarted -> runClientFetch(url, client, onStarted = onStarted) },
    ): List<YtStream>? {
        val processes = CopyOnWriteArrayList<Process>()
        val results = CopyOnWriteArrayList<List<YtStream>>()
        val winner = CompletableDeferred<List<YtStream>>()
        val remaining = AtomicInteger(clients.size)
        val ioContext = DreamCoroutines.clientIo.coroutineContext.minusKey(Job)

        val runRace: suspend () -> List<YtStream> = {
            coroutineScope {
                for (client in clients) {
                    launch(ioContext) {
                        runCatching {
                            val streams = fetch(videoUrl, client) { proc ->
                                processes.add(proc)
                                onProcess(proc)
                                if (winner.isCompleted) Processes.destroyTree(proc)
                            }
                            recordSuccess(videoUrl, client, streams)
                            if (YtStreams.usable(streams)) results.add(streams)
                            if (YtStreams.usable(streams) && YtStreams.offersQualityLadder(streams)) {
                                winner.complete(streams)
                            }
                        }.onFailure { e ->
                            if (e is CancellationException) throw e
                            rememberError(videoUrl, client, e, abandoned = !isActive || winner.isCompleted)
                        }.also { result ->
                            if (result.exceptionOrNull() is CancellationException) return@launch

                            if (remaining.decrementAndGet() == 0) {
                                winner.complete(bestResult(results))
                            }
                        }
                    }
                }
                winner.await().also {
                    // The losers block in Process.waitFor, which cancellation cannot interrupt, and coroutineScope
                    // waits for every child: without this the winning result sat until the slowest client finished
                    // or timed out (up to FETCH_TIMEOUT_SECONDS). One started after this point kills itself (above).
                    processes.forEach { runCatching { Processes.destroyTree(it) } }
                }
            }
        }

        val finalResult = try {
            withTimeoutOrNull((FETCH_TIMEOUT_SECONDS + 10).seconds) { runRace() } ?: run {
                logger.warn("yt-dlp race for $videoUrl did not settle.")
                []
            }
        } finally {
            processes.forEach { runCatching { Processes.destroyTree(it) } }
        }

        return finalResult.takeIf { it.isNotEmpty() }
    }

    private fun bestResult(results: List<List<YtStream>>): List<YtStream> =
        results.maxWithOrNull(
            compareBy<List<YtStream>> { if (YtStreams.offersQualityLadder(it)) 1 else 0 }
                .thenBy { YtStreams.distinctHeights(it).size }
        ) ?: []

    @Throws(IOException::class)
    private fun runClientFetch(
        videoUrl: String,
        client: String?,
        retriedOnBinary: Boolean = false,
        onStarted: (Process) -> Unit,
    ): List<YtStream> {
        val base = YtDlpBinary.resolveCommand()
        val cmd = ArrayList<String>()
        cmd.addAll(base)
        val tempCookies = cookies.appendArgs(cmd)

        val hasCookieArg = cmd.any { it == "--cookies" || it == "--cookies-from-browser" }
        if (!hasCookieArg && client != null) {
            cmd.addAll(["--extractor-args", "youtube:player_client=$client"])
        }

        cmd.addAll(
            [
                "--force-ipv4",
                "-J", "--no-playlist", "--no-warnings", "--no-check-formats",
                "--ignore-config", "--no-mark-watched",
                "--extractor-retries", "0",
                "--socket-timeout", "8",
                "--",
                videoUrl,
            ]
        )
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(false)
        val process = pb.start()
        onStarted(process)
        runCatching { process.outputStream.close() }
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutReader = Processes.collector(process.inputStream, stdout, "YtDlp-stdout")
        val stderrReader = Processes.collector(process.errorStream, stderr, "YtDlp-stderr")
        stdoutReader.start()
        stderrReader.start()
        try {
            if (!process.waitFor(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Processes.destroyTree(process)
                stdoutReader.join(2_000)
                stderrReader.join(2_000)
                throw IOException("Timed out for url: $videoUrl (client=${client ?: "cookies"}).")
            }
            stdoutReader.join(5_000)
            stderrReader.join(5_000)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for yt-dlp.", e)
        } finally {
            if (tempCookies != null) runCatching { Files.deleteIfExists(tempCookies) }
        }
        if (process.exitValue() != 0) {
            val output = stderr.toString().trim()
            // The zipapp's Python can't run this yt-dlp (too old, broken install): every call would fail the same
            // way within a second, so switch to the standalone binary for the session and try this one again on it.
            if (!retriedOnBinary && YtDlpBinary.isZipapp(base) && YtDlpBinary.looksLikeInterpreterFailure(output) &&
                YtDlpBinary.abandonZipapp(base, output)
            ) {
                ResolveTrail.record(videoUrl, "yt-dlp zipapp", client ?: "cookies", ok = false, error = "${base[0]}: $output")
                ResolveTrail.recordStderr(videoUrl, output)
                return runClientFetch(videoUrl, client, retriedOnBinary = true, onStarted = onStarted)
            }
            throw IOException("Exited with code ${process.exitValue()}: $output.")
        }
        return YtDlpOutputParser.parseFormats(stdout.toString())
    }
}
