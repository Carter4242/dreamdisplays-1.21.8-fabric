package com.dreamdisplays.media.player.policy

import kotlin.test.Test
import kotlin.test.assertEquals

class FailureStagesTest {
    @Test
    fun `http status is read from ffmpeg and yt-dlp output`() {
        assertEquals(403, FailureStages.httpStatus("[https @ 0x7f] HTTP error 403 Forbidden"))
        assertEquals(403, FailureStages.httpStatus("Error opening input: Server returned 403 Forbidden (access denied)"))
        assertEquals(429, FailureStages.httpStatus("ERROR: [youtube] abc: HTTP Error 429: Too Many Requests"))
        assertEquals(404, FailureStages.httpStatus(null, "", "status code: 404"))
        assertEquals(0, FailureStages.httpStatus("frame=  403 fps=30 q=-0.0 size=N/A"))
        assertEquals(0, FailureStages.httpStatus("HTTP 200 OK"))
    }

    @Test
    fun `stream stages`() {
        assertEquals(FailureStages.HTTP_STATUS, FailureStages.streamStage("Server returned 403 Forbidden", firstFrameSeen = true))
        assertEquals(FailureStages.OPEN_STREAM, FailureStages.streamStage("Invalid data found when processing input", false))
        assertEquals(FailureStages.DECODE, FailureStages.streamStage("Invalid data found when processing input", true))
    }

    @Test
    fun `cause chain messages are joined`() {
        val e = RuntimeException("outer", IllegalStateException("inner: HTTP Error 403"))
        assertEquals(403, FailureStages.httpStatus(FailureStages.messages(e)))
    }
}
