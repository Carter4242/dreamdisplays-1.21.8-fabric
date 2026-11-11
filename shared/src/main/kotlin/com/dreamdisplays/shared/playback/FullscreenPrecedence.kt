package com.dreamdisplays.shared.playback

/**
 * Which fullscreen session a player is shown when several target them: a player sees one session at a time, the newest
 * that targets them. A newer one takes them over whenever it reaches them (also when they walk into its radius later);
 * an older one never takes them from a newer one. Between sessions started at the same instant (restored after a
 * restart, all at 0) the one they already watch stays.
 */
object FullscreenPrecedence {
    /** A session that targets the player: its id and when it started. */
    data class Candidate(val sessionId: String, val startedAtMs: Long)

    /**
     * The session to show a player out of the [targeting] ones, or null when none targets them. [watching]: the
     * session they are shown now, if any.
     */
    fun pick(targeting: Collection<Candidate>, watching: String?): String? {
        val newest = targeting.maxOfOrNull { it.startedAtMs } ?: return null
        val tied = targeting.filter { it.startedAtMs == newest }
        return (tied.firstOrNull { it.sessionId == watching } ?: tied.minBy { it.sessionId }).sessionId
    }
}
