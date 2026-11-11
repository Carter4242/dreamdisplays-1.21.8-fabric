package com.dreamdisplays.media.player.policy

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.source.MediaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryPolicyTest {
    private val ffmpeg403 = "[in#0 @ 000002e00d9f4c80] Error opening input: Server returned 403 Forbidden (access denied)\n" +
        "Error opening input file http://127.0.0.1:50123/0123456789abcdef0123456789abcdef.\n" +
        "Error opening input files: Server returned 403 Forbidden (access denied)"

    @Test
    fun refusedStreamIsRetriedAsARefusal() {
        val decision = assertNotNull(RetryPolicy().evaluate(ffmpeg403, normalEos = false, isLive = false))
        assertTrue(decision.invalidateCache)
        assertTrue(decision.refused)
    }

    @Test
    fun goneStreamInvalidatesWithoutCountingAsRefusal() {
        val decision = assertNotNull(
            RetryPolicy().evaluate("HTTP error 404 Not Found", normalEos = false, isLive = false),
        )
        assertTrue(decision.invalidateCache)
        assertFalse(decision.refused)
    }

    @Test
    fun statusDigitsInsideOtherTextAreNotAStatus() {
        assertFalse(RetryPolicy.isRefused("itag=4031&clen=1403200 decoding error"))
        assertFalse(RetryPolicy.isNotFound("Option headers not found."))
        assertTrue(RetryPolicy.isRefused("HTTP error 403"))
    }

    @Test
    fun retriesStopAfterTheBudget() {
        val policy = RetryPolicy(maxRetries = 3)
        repeat(3) {
            assertNotNull(policy.evaluate(ffmpeg403, normalEos = false, isLive = false))
            policy.nextDelay()
        }
        assertNull(policy.evaluate(ffmpeg403, normalEos = false, isLive = false))
    }

    @Test
    fun exhaustedRefusalIsARetryableNetworkFailure() {
        val e = RetryPolicy.exhaustedError(ffmpeg403, normalEos = false, MediaSource.YouTube("b65MoVwANq4"))
        assertIs<DreamMediaException.Network>(e)
        assertFalse(e.isFatal)
        assertEquals("YouTube refused the video stream (403). Try again in a minute.", e.message)

        val other = RetryPolicy.exhaustedError(ffmpeg403, normalEos = false, null)
        assertIs<DreamMediaException.Network>(other)
        assertTrue("403" in other.message!!)
    }

    @Test
    fun exhaustedConnectionLossStaysNetworkAndDecoderFailureStaysFatal() {
        assertIs<DreamMediaException.Network>(
            RetryPolicy.exhaustedError("Connection reset by peer", normalEos = false, null),
        )
        val fatal = RetryPolicy.exhaustedError("Invalid data found when processing input", normalEos = false, null)
        assertIs<DreamMediaException.Decode>(fatal)
        assertTrue(fatal.isFatal)
    }
}
