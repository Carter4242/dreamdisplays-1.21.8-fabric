package com.dreamdisplays.media.source.youtube

/**
 * Who waits on each URL's shared `yt-dlp` fetch: every caller of [YtDlp.fetch] joins for as long as it waits. A resolve
 * that lost its race stops the fetch only when it is the one caller left ([stopIfOnly]); joining and that decision
 * share one lock, so a caller joining at the same moment is either counted (the fetch goes on) or joins after the stop
 * (and starts a fresh fetch), never the fetch being stopped.
 */
internal class FetchWaiters {
    private val waiting = HashMap<String, MutableList<Any>>()

    /** [owner] waits on [url]'s fetch from now on (owners are told apart by identity). */
    @Synchronized
    fun join(url: String, owner: Any) {
        waiting.getOrPut(url) { ArrayList(2) }.add(owner)
    }

    /** [owner] no longer waits on [url]'s fetch. */
    @Synchronized
    fun leave(url: String, owner: Any) {
        val owners = waiting[url] ?: return
        val i = owners.indexOfFirst { it === owner }
        if (i >= 0) owners.removeAt(i)
        if (owners.isEmpty()) waiting.remove(url)
    }

    /**
     * Runs [stop] (under the lock, so it must be quick) when [owner] is the only caller waiting on [url]; true when it
     * ran. Not when [owner] is not waiting (not there yet, or done): what is in flight then is someone else's.
     */
    @Synchronized
    fun stopIfOnly(url: String, owner: Any, stop: () -> Unit): Boolean {
        val owners = waiting[url] ?: return false
        if (owners.size != 1 || owners[0] !== owner) return false
        stop()
        return true
    }

    /** Callers waiting on [url] (tests). */
    @Synchronized
    internal fun count(url: String): Int = waiting[url]?.size ?: 0
}
