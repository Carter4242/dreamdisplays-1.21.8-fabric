package com.dreamdisplays.media.source.youtube.process

import com.dreamdisplays.api.media.model.DreamMediaException
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YtDlpErrorsTest {
    @Test
    fun signInRequiredIsFatal() {
        val e = YtDlpErrors.classify(
            "Exited with code 1: ERROR: [youtube] p63zkG1FB2w: Please sign in. Use --cookies-from-browser or --cookies for the authentication.",
        )
        assertIs<DreamMediaException.Unknown>(e)
        assertTrue(e.isFatal)
        assertTrue("signed-in account" in e.message!!)
    }

    @Test
    fun botCheckIsRetryable() {
        val e = YtDlpErrors.classify("ERROR: [youtube] x: Sign in to confirm you're not a bot.")
        assertIs<DreamMediaException.Network>(e)
        assertTrue(!e.isFatal)
    }

    @Test
    fun ageAndPrivate() {
        assertTrue("adults" in YtDlpErrors.classify("ERROR: Sign in to confirm your age")!!.message!!)
        assertIs<DreamMediaException.NotFound>(YtDlpErrors.classify("ERROR: [youtube] x: Private video"))
    }

    @Test
    fun ytDlpGeoBlockIsFatal() {
        val e = YtDlpErrors.classify(
            "ERROR: [youtube] x: The uploader has not made this video AVAILABLE IN YOUR COUNTRY",
        )
        assertIs<DreamMediaException.GeoBlocked>(e)
        assertTrue(e.isFatal)
    }

    @Test
    fun newPipeGeoBlockIsFatal() {
        val e = YtDlpErrors.classify("This video is not available in client's country.")
        assertIs<DreamMediaException.GeoBlocked>(e)
        assertTrue(e.isFatal)
        assertTrue("your country" in e.message!!)
    }

    @Test
    fun unknownOutputIsNull() {
        assertNull(YtDlpErrors.classify("Exited with code 1: ERROR: something odd"))
        assertNull(YtDlpErrors.classify(null))
    }
}
