package com.dreamdisplays.platform.server.utils

import com.dreamdisplays.api.media.source.LiveStreamUrls
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Filter
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.filter.AbstractFilter
import org.slf4j.LoggerFactory

/**
 * Keeps the relay publish credentials out of the server log. The server logs every command a player types
 * ("<name> issued server command: /display config live.relay_publish_url rtmps://...?user=...&pass=..."); this
 * root-logger filter drops any line that mentions `relay_publish_url` with credentials in it and logs it again
 * with them redacted ([LiveStreamUrls.redactCredentials]). The same for `relay_control_token` (the relay lease
 * service's bearer token: the value after the key is replaced). Lines that mention neither key are never touched.
 */
@PaperOnly
object SecretLogFilter {
    private val logger = LoggerFactory.getLogger(javaClass)

    private const val MARKER = "relay_publish_url"
    private const val TOKEN_MARKER = "relay_control_token"
    const val REDACTED = "<redacted>"

    /** `relay_control_token <value>` / `relay_control_token = "<value>"`: the value. */
    private val TOKEN_VALUE = Regex("""(?i)(relay_control_token["']?\s*(?:=\s*)?["']?)(?!<redacted>)[^\s"']+""")

    /** [message] with relay credentials redacted (publish URL query credentials, the lease service token). */
    fun redact(message: String): String {
        var out = message
        if (out.contains(MARKER, ignoreCase = true)) out = LiveStreamUrls.redactCredentials(out)
        if (out.contains(TOKEN_MARKER, ignoreCase = true)) out = TOKEN_VALUE.replace(out) { "${it.groupValues[1]}$REDACTED" }
        return out
    }

    private object Redactor : AbstractFilter(Filter.Result.NEUTRAL, Filter.Result.NEUTRAL) {
        override fun filter(event: LogEvent?): Filter.Result {
            if (event == null) return Filter.Result.NEUTRAL
            val message = event.message?.formattedMessage ?: return Filter.Result.NEUTRAL
            if (!message.contains(MARKER, ignoreCase = true) && !message.contains(TOKEN_MARKER, ignoreCase = true)) {
                return Filter.Result.NEUTRAL
            }
            val redacted = redact(message)
            // Already clean (or this is the redacted copy logged below): let it through
            if (redacted == message) return Filter.Result.NEUTRAL
            LogManager.getLogger(event.loggerName).log(event.level, redacted, event.thrown)
            return Filter.Result.DENY
        }
    }

    @Volatile
    private var installed = false

    /** Adds the filter to the root logger config (once). Never throws: no log4j core means nothing to do. */
    fun install() {
        if (installed) return
        runCatching {
            val root = (LogManager.getContext(false) as LoggerContext).configuration.rootLogger
            Redactor.start()
            root.addFilter(Redactor)
            installed = true
        }.onFailure { logger.warn("Could not install the log filter for relay credentials: {}", it.toString()) }
    }

    /** Removes the filter again (plugin disable), so a reloaded plugin does not leave a stale class behind. */
    fun uninstall() {
        if (!installed) return
        runCatching {
            (LogManager.getContext(false) as LoggerContext).configuration.rootLogger.removeFilter(Redactor)
            Redactor.stop()
        }
        installed = false
    }
}
