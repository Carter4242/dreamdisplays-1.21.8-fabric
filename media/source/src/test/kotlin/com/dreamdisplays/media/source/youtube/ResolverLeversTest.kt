package com.dreamdisplays.media.source.youtube

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `client.disabledResolvers` inside YouTube resolving, where NewPipe and yt-dlp lean on each other. */
class ResolverLeversTest {
    @Test
    fun `nothing disabled keeps the usual pairing`() {
        assertTrue(ResolverLevers.newPipeInsideYtDlp(emptySet()))
        assertFalse(ResolverLevers.newPipeKeepsNonLadder(emptySet()))
    }

    @Test
    fun `newpipe disabled keeps it out of yt-dlp's resolve too`() {
        assertFalse(ResolverLevers.newPipeInsideYtDlp(setOf("newpipe")))
        assertFalse(ResolverLevers.newPipeKeepsNonLadder(setOf("newpipe")))
    }

    @Test
    fun `yt-dlp disabled lets NewPipe keep a video without a quality ladder`() {
        assertTrue(ResolverLevers.newPipeKeepsNonLadder(setOf("yt-dlp")))
        assertTrue(ResolverLevers.newPipeInsideYtDlp(setOf("yt-dlp")))
    }

    @Test
    fun `both disabled is the same as neither`() {
        val both = setOf("newpipe", "yt-dlp")
        assertTrue(ResolverLevers.newPipeInsideYtDlp(both))
        assertFalse(ResolverLevers.newPipeKeepsNonLadder(both))
    }
}
