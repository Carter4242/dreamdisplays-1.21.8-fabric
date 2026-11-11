package com.dreamdisplays.core.sync

/**
 * Cleanup for the `ddsync=N` query parameter older server builds appended to a display's URL to force every
 * viewer to reload it. Clients now align without reloading, so stored URLs are cleaned of it on load.
 */
object ResyncMarker {
    private val PARAM = Regex("""ddsync=\d+""")

    /** [url] without any `ddsync=N` query parameter; other parameters and the fragment are kept as they were. */
    fun strip(url: String): String {
        val hashAt = url.indexOf('#')
        val base = if (hashAt >= 0) url.substring(0, hashAt) else url
        val fragment = if (hashAt >= 0) url.substring(hashAt) else ""
        val queryAt = base.indexOf('?')
        if (queryAt < 0) return url
        val params = base.substring(queryAt + 1).split('&')
        val kept = params.filterNot { PARAM.matches(it) }
        if (kept.size == params.size) return url
        val path = base.substring(0, queryAt)
        val query = kept.filter { it.isNotEmpty() }.joinToString("&")
        return path + (if (query.isEmpty()) "" else "?$query") + fragment
    }
}
