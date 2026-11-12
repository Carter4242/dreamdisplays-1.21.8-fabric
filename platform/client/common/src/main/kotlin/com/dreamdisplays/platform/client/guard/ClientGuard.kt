package com.dreamdisplays.platform.client.guard

import com.dreamdisplays.platform.client.displays.ClientErrorReporter
import org.slf4j.LoggerFactory

/**
 * The [FailureGuard] every game-facing callback of this mod runs under: screens, HUD, world render, tick, input
 * mixins and packet receivers. Failures go to the client log and, for servers that take reports, to
 * [ClientErrorReporter].
 */
object ClientGuard {
    private val logger = LoggerFactory.getLogger("DreamDisplays/Guard")

    val guard = FailureGuard(
        log = { message, error -> if (error != null) logger.error(message, error) else logger.error(message) },
        report = { site, error -> ClientErrorReporter.reportContained(site, error) },
    )

    /** Runs [block] for [site]; a throw is logged and reported, and [fallback] returned instead. */
    inline fun <T> call(site: String, fallback: T, block: () -> T): T = guard.call(site, fallback, block)

    /** Runs [block] for [site]; a throw is logged and reported, and the callback simply does nothing more. */
    inline fun run(site: String, block: () -> Unit) {
        guard.call(site, Unit, block)
    }
}
