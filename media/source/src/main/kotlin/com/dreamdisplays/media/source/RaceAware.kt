package com.dreamdisplays.media.source

import com.dreamdisplays.api.media.source.MediaSource

/** What a built-in resolver tells [DefaultMediaResolverRegistry]'s race beyond the public resolver interface. */
interface RaceAware {
    /** True when [source] is this resolver's alone: lower-priority resolvers only start if it fails. */
    fun claims(source: MediaSource): Boolean = false

    /** Another resolver won [source]: stop this one's work for it still running (its subprocesses). */
    fun cancel(source: MediaSource) {}
}
