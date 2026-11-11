package com.dreamdisplays.media.player

import com.dreamdisplays.media.player.AudioEndPolicy.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AudioEndPolicyTest {
    private fun decide(
        live: Boolean = false, noAudioStream: Boolean = false, hadPcm: Boolean = true, stderrBlank: Boolean = true,
        nearEnd: Boolean = false, restartAllowed: Boolean = true,
    ) = AudioEndPolicy.onEnd(live, noAudioStream, hadPcm, stderrBlank, nearEnd) { restartAllowed }

    @Test
    fun `sound ending mid-video reopens only the sound, never the picture`() {
        // A 120 s movie whose audio track is 100 s, or any natural end of a file whose length is unknown
        assertEquals(Action.REOPEN_AUDIO, decide())
    }

    @Test
    fun `a reopen that finds nothing left to decode means the track is over`() {
        assertEquals(Action.FINISHED, decide(hadPcm = false))
        // Even near the end, and even with the restart budget gone: nothing to recover
        assertEquals(Action.FINISHED, decide(hadPcm = false, nearEnd = true, restartAllowed = false))
    }

    @Test
    fun `an empty end with an error logged is not taken for the end of the track`() {
        assertEquals(Action.REOPEN_AUDIO, decide(hadPcm = false, stderrBlank = false))
    }

    @Test
    fun `the last seconds of a known-length video defer to the video's own end`() {
        assertEquals(Action.DEFER_TO_VIDEO, decide(nearEnd = true))
    }

    @Test
    fun `live media and sources without sound keep the old failure handling`() {
        assertEquals(Action.FAILURE_PATH, decide(live = true))
        assertEquals(Action.FAILURE_PATH, decide(live = true, hadPcm = false))
        assertEquals(Action.FAILURE_PATH, decide(noAudioStream = true, hadPcm = false))
    }

    @Test
    fun `a sound that keeps ending goes to the full recovery once the restart budget is used up`() {
        assertEquals(Action.FAILURE_PATH, decide(restartAllowed = false))
    }

    @Test
    fun `the restart budget is only spent when a reopen is actually due`() {
        var asked = false
        AudioEndPolicy.onEnd(false, false, hadPcm = false, stderrBlank = true, nearEnd = false) { asked = true; true }
        AudioEndPolicy.onEnd(false, false, hadPcm = true, stderrBlank = true, nearEnd = true) { asked = true; true }
        assertFalse(asked)
    }
}
