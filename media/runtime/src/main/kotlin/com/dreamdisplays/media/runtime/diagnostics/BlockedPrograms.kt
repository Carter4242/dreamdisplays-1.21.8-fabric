package com.dreamdisplays.media.runtime.diagnostics

import org.slf4j.LoggerFactory

/**
 * Notes when Windows refuses to start one of the mod's programs (FFmpeg, yt-dlp) because of an application control
 * policy, which Smart App Control applies to unsigned programs: every video then fails, and the player should be told
 * how to fix it. Call [note] wherever such a program fails to start; the client shows [take] once.
 */
object BlockedPrograms {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var blocked: String? = null

    @Volatile
    private var shown = false

    /** Records [program] as blocked when [error] says an application control policy stopped it. Any thread. */
    fun note(program: String, error: Throwable?) {
        if (blocked != null || !isPolicyBlock(error?.message)) return
        blocked = program
        logger.warn("Windows blocked {} with an application control policy (Smart App Control): videos cannot play.", program)
    }

    /** The blocked program's name, the first time it is asked for after one was noted; null otherwise. */
    fun take(): String? {
        val name = blocked ?: return null
        if (shown) return null
        shown = true
        return name
    }

    /** True for Windows' "An Application Control policy has blocked this file" (error 4551). */
    fun isPolicyBlock(message: String?): Boolean =
        message != null && (POLICY_ERROR.containsMatchIn(message) || "Application Control policy" in message)

    private val POLICY_ERROR = Regex("""error=4551(?!\d)""")
}
