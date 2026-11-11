package com.dreamdisplays.media.source

import com.dreamdisplays.api.media.source.MediaMetadata
import com.dreamdisplays.api.media.source.MediaResolverService
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.source.ResolvedMedia
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/** `client.disabledResolvers`: a named resolver is left out, unless nothing else would take the link. */
class DisabledResolversTest {
    private val source = MediaSource.YouTube("dQw4w9WgXcQ")

    private open class Counting(override val priority: Int) : MediaResolverService {
        val calls = AtomicInteger()
        override fun canResolve(source: MediaSource): Boolean = true
        override fun resolve(source: MediaSource): ResolvedMedia {
            calls.incrementAndGet()
            return ResolvedMedia([], MediaMetadata.UNKNOWN, isLive = false, isSeekable = false)
        }
    }

    private class AlphaResolver : Counting(10)
    private class BetaResolver : Counting(5)

    @Test
    fun `names are the short report names`() {
        assertEquals("alpha", DefaultMediaResolverRegistry.resolverName(AlphaResolver()))
    }

    @Test
    fun `a disabled resolver is skipped`() {
        val alpha = AlphaResolver()
        val beta = BetaResolver()
        val registry = DefaultMediaResolverRegistry { setOf("alpha") }.apply { register(alpha); register(beta) }
        registry.resolve(source)
        assertEquals(0, alpha.calls.get())
        assertEquals(1, beta.calls.get())
    }

    @Test
    fun `a link nothing else takes still gets every resolver`() {
        val alpha = AlphaResolver()
        val beta = BetaResolver()
        assertEquals(listOf<MediaResolverService>(alpha, beta), DefaultMediaResolverRegistry.usableResolvers([alpha, beta], setOf("alpha", "beta")))
        assertEquals(listOf<MediaResolverService>(beta), DefaultMediaResolverRegistry.usableResolvers([alpha, beta], setOf("alpha")))
        assertEquals(listOf<MediaResolverService>(alpha, beta), DefaultMediaResolverRegistry.usableResolvers([alpha, beta], emptySet()))
    }
}
