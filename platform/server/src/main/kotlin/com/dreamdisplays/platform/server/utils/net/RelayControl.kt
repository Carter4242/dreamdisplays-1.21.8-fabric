package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.core.share.RelayLeaseSync
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.util.net.DreamHttpClient
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
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

    @Volatile
    private var lastPushFailed = false

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
     * Plugin disable, after every share ended: pushes the (now empty) set once more from the calling thread, so a
     * reload or stop cuts the streams at once rather than at the lease's lapse, and stops the push thread.
     */
    fun shutdown() {
        val (s, w) = synchronized(lock) {
            val pair = sync to worker
            sync = null
            worker = null
            pair
        }
        if (w != null) {
            w.shutdown()
            runCatching { w.awaitTermination(2, TimeUnit.SECONDS) }
            w.shutdownNow()
        }
        if (s != null && s.needsRenewal()) runCatching { s.push() }
    }

    private fun started(): Pair<RelayLeaseSync, ScheduledExecutorService> = synchronized(lock) {
        val s = sync ?: RelayLeaseSync(RelayLeaseSync.newEpoch(), send = ::post).also { sync = it }
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

    private fun post(body: String): Boolean {
        val live = PaperServer.config.settings.live
        var problem: String? = null
        val ok = runCatching {
            DreamHttpClient.execute(
                live.relay_control_url,
                DreamHttpClient.RequestOptions(
                    method = "PUT",
                    headers = DreamHttpClient.headersOf(
                        "Authorization" to "Bearer ${live.relay_control_token}",
                        "User-Agent" to "DreamDisplays relay leases",
                    ),
                    body = body.toByteArray(StandardCharsets.UTF_8),
                    contentType = "application/json",
                    connectTimeoutMs = 3_000L,
                    readTimeoutMs = 4_000L,
                    writeTimeoutMs = 4_000L,
                    callTimeoutMs = 6_000L,
                    followRedirects = false,
                ),
            ).also { if (!it.isSuccessful) problem = "it answered ${it.code} ${it.message}" }.isSuccessful
        }.getOrElse {
            // The body carries publish keys: never log it (the URL and the exception carry none)
            problem = it.toString()
            false
        }
        // Once per outage, not every renewal
        if (!ok && !lastPushFailed) logger.warn("Could not update the relay lease service ({}): new shares are refused until it answers.", problem)
        if (ok && lastPushFailed) logger.info("The relay lease service answers again.")
        lastPushFailed = !ok
        return ok
    }
}
