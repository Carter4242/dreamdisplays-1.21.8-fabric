package com.dreamdisplays.media.player.managers

/**
 * Notices a session whose picture keeps playing while its sound has stopped: the audio side should be writing
 * ([expecting]), the video is still decoding frames, yet no PCM reached the line for [thresholdNanos]. Any dead
 * audio half (a feeder that died without reporting, a line left stopped, a start gate that never opened) looks
 * like this, so the caller restarts the audio side. Fires at most once per [retryNanos]. Pure; one caller thread.
 */
internal class AudioStallDetector(
    private val thresholdNanos: Long = 1_500_000_000L,
    private val retryNanos: Long = 10_000_000_000L,
) {
    private var wasExpecting = false
    private var armedAtNanos = 0L
    private var lastFiredNanos = Long.MIN_VALUE

    /**
     * True when the audio side should be restarted now. [lastAudioNanos]: when PCM last reached the line (0 when
     * never); [lastVideoNanos]: when the last video frame was decoded.
     */
    fun check(now: Long, expecting: Boolean, lastAudioNanos: Long, lastVideoNanos: Long): Boolean {
        if (!expecting) {
            wasExpecting = false
            return false
        }
        if (!wasExpecting) {
            // Measure from when sound was due (a resume, an un-park), never from a write made before a pause
            wasExpecting = true
            armedAtNanos = now
        }
        val silentSince = maxOf(lastAudioNanos, armedAtNanos)
        if (now - silentSince < thresholdNanos) return false
        // No video either: a whole-session stall, which the frame watchdog handles
        if (now - lastVideoNanos >= thresholdNanos) return false
        if (lastFiredNanos != Long.MIN_VALUE && now - lastFiredNanos < retryNanos) return false
        lastFiredNanos = now
        armedAtNanos = now // Give the restarted half its own full threshold
        return true
    }
}
