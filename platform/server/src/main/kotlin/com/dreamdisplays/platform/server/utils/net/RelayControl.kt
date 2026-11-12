package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.core.share.RelayLeaseSync
import com.dreamdisplays.platform.server.PaperServer
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Tells the relay's lease service (`[live] relay_control_url`) which shares run, so the relay only lets a running
 * share publish (with its own key) or be watched, and cuts a share's stream the moment it ends here, even when the
 * sharer's `FFmpeg` keeps going (a crashed or killed game). Pushes go out on one background thread, in order: on
 * every change and every [RelayLeaseSync.RENEW_MS] while anything is leased. When this server stops pushing (crash,
 * network cut), the service lets the set lapse after [RelayLeaseSync.DEFAULT_TTL_MS] and cuts it.
 */
@PaperOnly
@NullMarked
object RelayControl {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val RETRY_DELAY_MS = 500L

    private val lock = Any()
    private var worker: ScheduledExecutorService? = null
    private var sync: RelayLeaseSync? = null
    private var http: RelayHttp? = null

    @Volatile
    private var lastPushFailed = false

    /** When a push last went out (or was queued by [keepWarm]). */
    @Volatile
    private var lastSentMs = 0L

    /** True when a lease service is configured: shares then start only once it knows them. */
    val enabled: Boolean get() = PaperServer.config.settings.live.relayLeased

    /**
     * The shares that run now (stream id to publish key). [onAcked] runs on the push thread once the service took a
     * set containing them (true) or could not be reached (false). Without a lease service it runs at once with true.
     */
    fun set(leases: Map<String, String>, onAcked: ((Boolean) -> Unit)? = null) {
        if (!enabled) {
            onAcked?.invoke(true)
            return
        }
        val (s, w) = started()
        s.set(leases, onAcked)
        runCatching { w.execute(::pushNow) }.onFailure { onAcked?.invoke(false) }
    }

    /**
     * Keeps the HTTPS connection to the lease service open while nothing is leased but someone online may share: the
     * current set (empty) is pushed again every [WARM_EVERY_MS], inside the client's idle limit (the JDK client closes
     * a connection idle for 30 s) and the service's (5 minutes). A share's start waits for its lease, and a cold
     * connection added a TCP and TLS handshake to it (measured: 0.46 s to the relay, against 0.06 s warm).
     */
    fun keepWarm() {
        if (!enabled || System.currentTimeMillis() - lastSentMs < WARM_EVERY_MS) return
        lastSentMs = System.currentTimeMillis()
        val (s, w) = started()
        runCatching { w.execute { runCatching { s.warm() } } }
    }

    private const val WARM_EVERY_MS = 25_000L

    /**
     * Plugin disable, after every share ended: pushes the (now empty) set once more, so a reload or stop cuts the
     * streams at once rather than at the lease's lapse, and stops the push thread and the HTTP client's threads. The
     * push runs on the push thread (after any push still in flight, so they stay in order) and is waited for at most
     * [SHUTDOWN_WAIT_MS]: an unreachable service never holds the calling (main) thread for a full HTTP timeout; it lets
     * the set lapse instead.
     */
    fun shutdown() {
        val (s, w, h) = synchronized(lock) {
            val taken = Triple(sync, worker, http)
            sync = null
            worker = null
            http = null
            taken
        }
        if (w != null) {
            if (s != null && s.needsRenewal()) runCatching { w.execute { runCatching { s.push() } } }
            w.shutdown()
            runCatching { w.awaitTermination(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS) }
            w.shutdownNow()
        }
        h?.close()
    }

    private const val SHUTDOWN_WAIT_MS = 1_500L

    private fun started(): Pair<RelayLeaseSync, ScheduledExecutorService> = synchronized(lock) {
        val h = http ?: RelayHttp().also { http = it }
        val s = sync ?: RelayLeaseSync(RelayLeaseSync.newEpoch(), send = { body -> post(h, body) }).also { sync = it }
        val w = worker ?: Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "DreamDisplays relay leases").apply { isDaemon = true }
        }.also {
            worker = it
            it.scheduleWithFixedDelay(::renew, RelayLeaseSync.RENEW_MS, RelayLeaseSync.RENEW_MS, TimeUnit.MILLISECONDS)
        }
        s to w
    }

    /** One change: push it, and once more shortly after a failure (a start waiting on it is refused after that). */
    private fun pushNow() {
        val s = synchronized(lock) { sync } ?: return
        if (!s.dirty()) return
        if (s.push()) return
        Thread.sleep(RETRY_DELAY_MS)
        if (s.dirty()) s.push()
    }

    private fun renew() {
        val s = synchronized(lock) { sync } ?: return
        if (s.needsRenewal()) runCatching { s.push() }
    }

    private fun post(http: RelayHttp, body: String): Boolean {
        lastSentMs = System.currentTimeMillis()
        val live = PaperServer.config.settings.live
        val problem = try {
            http.put(live.relay_control_url, live.relay_control_token, body)
        } catch (e: Exception) {
            // The body carries publish keys: never log it (the URL and the exception carry none)
            e.toString()
        }
        val ok = problem == null
        // Once per outage, not every renewal
        if (!ok && !lastPushFailed) logger.warn("Could not update the relay lease service ({}): new shares are refused until it answers.", problem)
        if (ok && lastPushFailed) logger.info("The relay lease service answers again.")
        lastPushFailed = !ok
        return ok
    }
}

/**
 * The lease service's HTTPS client. The JDK's, not `DreamHttpClient`'s `OkHttp`: `OkHttp` runs its timeouts on
 * `Okio`'s watchdog thread, which nothing can stop and which outlives the last request by a minute, so a plugin
 * unloaded within a minute of a push (during or right after a share, or after a warm-up) left that thread holding its
 * classes. This client's threads end in [close].
 */
@PaperOnly
@NullMarked
private class RelayHttp {
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "DreamDisplays relay HTTP").apply { isDaemon = true }
    }
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
        .followRedirects(HttpClient.Redirect.NEVER)
        .executor(executor)
        .build()

    /** PUTs [body] to [url]: null when the service took it (2xx), otherwise what it answered. Throws when unreachable. */
    fun put(url: String, token: String, body: String): String? {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(ANSWER_TIMEOUT_MS))
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "DreamDisplays relay leases")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        val code = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        return if (code in 200..299) null else "it answered $code"
    }

    /** Stops the client's threads, cutting a push still in flight. */
    fun close() {
        // shutdownNow and awaitTermination came in Java 21; on older Java the client's thread ends once it is unreachable
        runCatching { HttpClient::class.java.getMethod("shutdownNow").invoke(client) }
        runCatching {
            HttpClient::class.java.getMethod("awaitTermination", Duration::class.java)
                .invoke(client, Duration.ofMillis(CLOSE_WAIT_MS))
        }
        executor.shutdownNow()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000L

        /** Until the service's answer starts; the JSON pushed is tiny. */
        const val ANSWER_TIMEOUT_MS = 6_000L
        const val CLOSE_WAIT_MS = 500L
    }
}
