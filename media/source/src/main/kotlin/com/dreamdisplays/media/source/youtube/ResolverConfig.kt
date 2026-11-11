package com.dreamdisplays.media.source.youtube

import com.dreamdisplays.media.source.youtube.cookie.CookieSource

/**
 * Host-supplied configuration the media resolvers need (proxy, browser-cookie source). The platform
 * layer installs a [Provider] at startup; until then sane defaults apply. Keeps the resolver module
 * free of any dependency on the Minecraft client config.
 */
object ResolverConfig {
    interface Provider {
        /** `yt-dlp` proxy URL, or blank for none. */
        val ytdlpProxy: String

        /** Browser to import cookies from, or [CookieSource.NONE]. */
        val ytdlpCookieSource: CookieSource

        /** Resolvers the server took out (`client.disabledResolvers`, lowercase short names: `newpipe`, `yt-dlp`). */
        val disabledResolvers: Set<String> get() = emptySet()
    }

    private object Defaults : Provider {
        override val ytdlpProxy: String = ""

        override val ytdlpCookieSource: CookieSource = CookieSource.NONE
    }

    /**
     * The host must set this to a real provider at startup, before any resolver is used. Until then
     * the defaults apply.
     */
    @Volatile
    var provider: Provider = Defaults

    /** Current `yt-dlp` proxy URL, or blank for none. */
    val ytdlpProxy: String get() = provider.ytdlpProxy

    /** Current browser to import cookies from, or [CookieSource.NONE]. */
    val ytdlpCookieSource: CookieSource get() = provider.ytdlpCookieSource

    /** Whether `yt-dlp`'s YouTube resolve may use NewPipe ([ResolverLevers.newPipeInsideYtDlp]). */
    val newPipeInsideYtDlp: Boolean get() = ResolverLevers.newPipeInsideYtDlp(disabled())

    /** Whether NewPipe keeps a result without a quality ladder ([ResolverLevers.newPipeKeepsNonLadder]). */
    val newPipeKeepsNonLadder: Boolean get() = ResolverLevers.newPipeKeepsNonLadder(disabled())

    private fun disabled(): Set<String> = runCatching { provider.disabledResolvers }.getOrDefault(emptySet())
}

/**
 * How `client.disabledResolvers` reaches inside YouTube resolving, where NewPipe and `yt-dlp` lean on each other:
 * `yt-dlp`'s resolve asks NewPipe first, and NewPipe hands a video without a quality ladder to `yt-dlp`. Both disabled
 * is the same as neither (the registry then keeps every resolver).
 */
object ResolverLevers {
    const val NEWPIPE = "newpipe"
    const val YT_DLP = "yt-dlp"

    /** NewPipe may be asked inside `yt-dlp`'s resolve: unless NewPipe alone is disabled. */
    fun newPipeInsideYtDlp(disabled: Set<String>): Boolean = NEWPIPE !in disabled || YT_DLP in disabled

    /** NewPipe keeps a result without a quality ladder (360p only) rather than defer: `yt-dlp` alone is disabled. */
    fun newPipeKeepsNonLadder(disabled: Set<String>): Boolean = YT_DLP in disabled && NEWPIPE !in disabled
}
