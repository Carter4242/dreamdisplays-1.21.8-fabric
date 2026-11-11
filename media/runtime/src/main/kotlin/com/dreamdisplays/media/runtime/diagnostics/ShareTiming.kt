package com.dreamdisplays.media.runtime.diagnostics

import org.slf4j.LoggerFactory

/**
 * Debug lines that time a screen share's start from end to end, on the sharer and on every viewer:
 * `[dd-share-t] <wall clock ms> <stage> <detail>`. Wall-clock milliseconds, so lines from several machines (and the
 * relay's own log) line up as far as their clocks agree.
 */
object ShareTiming {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun mark(stage: String, detail: String = "") {
        if (logger.isDebugEnabled) logger.debug("[dd-share-t] {} {} {}", System.currentTimeMillis(), stage, detail)
    }
}
