package com.dreamdisplays.media.player.policy

/**
 * Classifies playback failures for client error reports: the HTTP status a failure carried and the stage it
 * happened in. Stage values match `ErrorStage` wire values in the protocol.
 */
object FailureStages {
    const val RESOLVE = "resolve"
    const val OPEN_STREAM = "open_stream"
    const val DECODE = "decode"
    const val AUDIO = "audio"
    const val HTTP_STATUS = "http_status"
    const val STALL = "stall"
    const val SEEK = "seek"
    const val RESUME = "resume"

    private val STATUS_LABELLED = Regex(
        """(?i)\b(?:HTTP(?:\s+Error)?|status(?:\s+code)?|response\s+code|returned|server\s+returned)\s*[:=]?\s*([1-5]\d\d)\b""",
    )
    private val STATUS_REASON = Regex(
        """\b([45]\d\d)\s+(?:Forbidden|Not Found|Too Many Requests|Gone|Unauthorized|Bad Request|Bad Gateway|""" +
            """Service Unavailable|Internal Server Error|Gateway Time-?out|Request Timeout)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** The first HTTP error status (400..599) mentioned in [texts], or 0. */
    fun httpStatus(vararg texts: String?): Int {
        for (text in texts) {
            if (text.isNullOrEmpty()) continue
            for (regex in listOf(STATUS_REASON, STATUS_LABELLED)) {
                regex.findAll(text).map { it.groupValues[1].toInt() }.firstOrNull { it in 400..599 }?.let { return it }
            }
        }
        return 0
    }

    /** Messages of [error] and its causes, joined (for status matching). */
    fun messages(error: Throwable?): String =
        generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(6)
            .mapNotNull { it.message }.joinToString(" | ")

    /**
     * Stage of a stream that ended badly: the host answering with an HTTP error, the stream never producing a frame
     * (opening failed), or a failure mid-stream (decode).
     */
    fun streamStage(stderr: String, firstFrameSeen: Boolean): String = when {
        httpStatus(stderr) != 0 || RetryPolicy.isRefused(stderr) || RetryPolicy.isNotFound(stderr) -> HTTP_STATUS
        !firstFrameSeen -> OPEN_STREAM
        else -> DECODE
    }
}
