package com.dreamdisplays.core.share

import java.net.URI
import java.util.*

/** `[live] relay_control_url` / `relay_control_token`: the relay lease service the server tells which shares run. */
object RelayControlConfig {
    /** Why [url] / [token] cannot be used, or null when they can (or when [url] is empty: no lease service). */
    fun problem(url: String, token: String): String? {
        if (url.isBlank()) return null
        val uri = runCatching { URI(url.trim()) }.getOrNull()
            ?: return "relay_control_url is not a valid URL"
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            return "relay_control_url must be an https:// URL (it carries the publish keys)"
        }
        if (token.length < 16 || token.any { it.isWhitespace() || it.isISOControl() }) {
            return "relay_control_token must be at least 16 characters, no spaces"
        }
        return null
    }
}

/**
 * Keeps a relay lease service told which shares run, so the relay refuses (and cuts) any stream whose share is over
 * even when the sharer's `FFmpeg` keeps publishing. The service holds one set per server: `{streamId: publishKey}`,
 * replaced whole by every push. A set nobody renews within [ttlMs] lapses (the service then cuts all of it), so
 * [push] is called on every change and at least every [RENEW_MS] while anything is leased.
 *
 * Not thread-bound: [set] may be called from any thread; [push] must only ever run on one thread at a time (the
 * caller's single worker), so pushes reach the service in order.
 *
 * @param epoch random per plugin start: the service drops a push older than one it already took from this epoch.
 * @param send posts one JSON body; true when the service took it (2xx).
 */
class RelayLeaseSync(
    private val epoch: String,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val send: (String) -> Boolean,
) {
    private var wanted: Map<String, String> = emptyMap()
    private var wantedVersion = 0L
    private var ackedVersion = 0L
    private var seq = 0L
    private var failuresInRow = 0
    private val waiters = ArrayList<Pair<Long, (Boolean) -> Unit>>()

    /**
     * The shares that run now (stream id to publish key). [onAcked] runs (on the pushing thread) once a push that
     * contains this set or a newer one was taken (true), or once pushes failed [MAX_FAILURES_BEFORE_REFUSING] times in
     * a row (false).
     */
    fun set(leases: Map<String, String>, onAcked: ((Boolean) -> Unit)? = null) {
        leases.forEach { (id, key) ->
            require(STREAM_ID.matches(id)) { "bad stream id" }
            require(KEY.matches(key)) { "bad publish key" }
        }
        synchronized(this) {
            wanted = LinkedHashMap(leases)
            wantedVersion++
            if (onAcked != null) waiters += wantedVersion to onAcked
        }
    }

    /** True when the service has not taken the newest set yet. */
    @Synchronized
    fun dirty(): Boolean = ackedVersion < wantedVersion

    /** True while the service must keep hearing from this server: something is leased, or the last change is unsent. */
    @Synchronized
    fun needsRenewal(): Boolean = wanted.isNotEmpty() || dirty()

    /**
     * [push] only to keep the connection to the service open while nothing waits on it: a failure here does not count
     * toward refusing a start (a start still gets its own push and retry before it is refused).
     */
    fun warm(): Boolean = push(countFailure = false)

    /** Sends the newest set once. Returns whether the service took it. */
    fun push(): Boolean = push(countFailure = true)

    private fun push(countFailure: Boolean): Boolean {
        val (body, version) = synchronized(this) {
            seq++
            body(wanted, seq) to wantedVersion
        }
        val ok = runCatching { send(body) }.getOrDefault(false)
        val done: List<Pair<(Boolean) -> Unit, Boolean>> = synchronized(this) {
            if (ok) {
                failuresInRow = 0
                if (version > ackedVersion) ackedVersion = version
                takeWaiters(version).map { it to true }
            } else if (countFailure) {
                failuresInRow++
                if (failuresInRow >= MAX_FAILURES_BEFORE_REFUSING) takeWaiters(version).map { it to false } else emptyList()
            } else {
                emptyList()
            }
        }
        done.forEach { (callback, result) -> runCatching { callback(result) } }
        return ok
    }

    private fun takeWaiters(upTo: Long): List<(Boolean) -> Unit> {
        val taken = waiters.filter { it.first <= upTo }
        waiters.removeAll(taken)
        return taken.map { it.second }
    }

    /** The JSON the service takes: `{"epoch":..,"seq":..,"ttlMs":..,"shares":{"<id>":"<key>",..}}`. */
    internal fun body(leases: Map<String, String>, seq: Long): String = buildString {
        append("{\"epoch\":\"").append(epoch).append("\",\"seq\":").append(seq)
        append(",\"ttlMs\":").append(ttlMs).append(",\"shares\":{")
        leases.entries.forEachIndexed { i, (id, key) ->
            if (i > 0) append(',')
            append('"').append(id).append("\":\"").append(key).append('"')
        }
        append("}}")
    }

    companion object {
        /** A lease set the service hears nothing about for this long lapses (its streams are cut). */
        const val DEFAULT_TTL_MS: Long = 20_000L

        /** How often a non-empty set is pushed again although nothing changed. */
        const val RENEW_MS: Long = 3_000L

        /** A start waiting on the service is refused after this many failed pushes in a row. */
        const val MAX_FAILURES_BEFORE_REFUSING: Int = 2

        /** Stream ids are the server's own `dd` + lower-case letters and digits. */
        val STREAM_ID = Regex("[a-z0-9]{1,64}")

        /** Publish keys: URL-safe base64 / hex. */
        val KEY = Regex("[A-Za-z0-9_-]{16,128}")

        /** A fresh random epoch for one plugin start. */
        fun newEpoch(): String = UUID.randomUUID().toString().replace("-", "")
    }
}
