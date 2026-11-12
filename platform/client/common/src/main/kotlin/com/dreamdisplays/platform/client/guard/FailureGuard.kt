package com.dreamdisplays.platform.client.guard

/**
 * Contains what a callback the game makes into this mod throws (a screen's input or render handler, a HUD, world
 * render or tick hook), so a bug there can never take the game down. Nothing is swallowed silently: every failure is
 * logged, with its full trace the first time a site fails with a given error and then at most once per
 * [logIntervalMs] with a count of the repeats, and handed to [report] (which rate-limits what it sends on).
 *
 * Errors the JVM cannot recover from ([isFatal]) are rethrown untouched.
 */
class FailureGuard(
    private val log: (message: String, error: Throwable?) -> Unit,
    private val report: (site: String, error: Throwable) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logIntervalMs: Long = 10_000L,
) {
    private class Seen(var lastLogMs: Long, var repeats: Int = 0)

    private val seen = HashMap<String, Seen>()

    /** Runs [block]; if it throws, records the failure at [site] and returns [fallback]. */
    inline fun <T> call(site: String, fallback: T, block: () -> T): T =
        try {
            block()
        } catch (t: Throwable) {
            if (isFatal(t)) throw t
            failed(site, t)
            fallback
        }

    /** Runs [block]; true when it completed, false when it threw (and the failure was recorded). */
    inline fun attempt(site: String, block: () -> Unit): Boolean = call(site, false) { block(); true }

    /** Records that [site] threw [error]: logs it (rate-limited) and reports it. Never throws. */
    fun failed(site: String, error: Throwable) {
        try {
            logFailure(site, error)
        } catch (_: Throwable) {
        }
        try {
            report(site, error)
        } catch (t: Throwable) {
            try {
                log("Could not report a contained failure at $site: $t", null)
            } catch (_: Throwable) {
            }
        }
    }

    private fun logFailure(site: String, error: Throwable) {
        val now = clock()
        val key = "$site|${error.javaClass.name}|${error.message}"
        val (first, repeats) = synchronized(seen) {
            val entry = seen[key]
            when {
                entry == null -> {
                    if (seen.size >= MAX_TRACKED) seen.clear()
                    seen[key] = Seen(now)
                    true to 0
                }

                now - entry.lastLogMs >= logIntervalMs -> {
                    val repeats = entry.repeats
                    entry.lastLogMs = now
                    entry.repeats = 0
                    false to repeats + 1
                }

                else -> {
                    entry.repeats++
                    return
                }
            }
        }
        if (first) log("Dream Displays contained an error in $site; the game keeps running.", error)
        else log("Dream Displays contained the same error in $site again ($repeats times since last logged): $error", null)
    }

    companion object {
        private const val MAX_TRACKED = 256

        /** Errors after which nothing can be trusted to keep running; these are never contained. */
        fun isFatal(t: Throwable): Boolean = t is VirtualMachineError && t !is StackOverflowError

        /**
         * [error] and its innermost cause as at most [maxLines] lines, top frames first: the part of a trace that says
         * where it broke, small enough for an error report.
         */
        fun compactTrace(error: Throwable, maxLines: Int = 14): String {
            val lines = ArrayList<String>()
            fun add(t: Throwable, frames: Int, prefix: String) {
                lines += prefix + t.toString()
                t.stackTrace.take(frames).forEach { lines += "  at $it" }
            }
            var root = error
            val visited = HashSet<Throwable>()
            while (root.cause != null && visited.add(root)) root = root.cause!!
            if (root === error) {
                add(error, maxLines - 1, "")
            } else {
                val top = (maxLines - 2) / 2
                add(error, top, "")
                add(root, maxLines - 2 - top, "Caused by: ")
            }
            return lines.take(maxLines).joinToString("\n")
        }
    }
}

/**
 * When a screen that keeps failing closes: a render or tick that throws [limit] times in a row (a bug that hits every
 * frame), or an init that throws once (the screen is half built). A failed click or key press only loses that event.
 */
class ScreenFailures(private val limit: Int = 3) {
    /** True once the screen should close; it then draws nothing more and closes on its next tick. */
    var closeRequested: Boolean = false
        private set

    private var renderStreak = 0
    private var tickStreak = 0

    fun rendered(ok: Boolean) {
        renderStreak = if (ok) 0 else renderStreak + 1
        if (renderStreak >= limit) closeRequested = true
    }

    fun ticked(ok: Boolean) {
        tickStreak = if (ok) 0 else tickStreak + 1
        if (tickStreak >= limit) closeRequested = true
    }

    fun initialized(ok: Boolean) {
        if (!ok) closeRequested = true
    }

    /** The screen was built again (resize, reopen): start over. */
    fun reset() {
        closeRequested = false
        renderStreak = 0
        tickStreak = 0
    }
}
