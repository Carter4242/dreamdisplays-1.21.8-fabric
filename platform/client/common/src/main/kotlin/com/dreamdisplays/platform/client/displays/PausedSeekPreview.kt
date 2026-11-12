package com.dreamdisplays.platform.client.displays

/**
 * A paused display that is seeked shows the frame it was seeked to: the player opens its stream there and holds it on
 * the first frame ([com.dreamdisplays.media.player.MediaPlayer.warmUpPaused]). A seek while paused used to move only
 * the player's clock, so the picture stayed on the frame it paused on until it played again.
 *
 * Decides when. A paused seek is not handed to the player at once: it is [request]ed, and once a client tick [poll]
 * hands out the latest one, at most one opening at a time and no more often than [minIntervalNanos]. So a dragged seek
 * bar or a scrubbed replay opens the stream once for where it ended up, instead of re-opening it for every step on the
 * way. Client thread.
 */
internal class PausedSeekPreview(private val minIntervalNanos: Long = MIN_INTERVAL_NANOS) {
    /** A seek to carry out: to [targetNanos], announced upstream if [fire], and shown by opening the stream if [open]. */
    data class Seek(val targetNanos: Long, val fire: Boolean, val open: Boolean)

    private var target = NONE
    private var fire = false
    private var generation = 0L
    private var waitingSinceNanos = Long.MIN_VALUE
    private var lastOpenNanos = Long.MIN_VALUE

    /**
     * A seek to [targetNanos] while paused, at [nowNanos], on the player of media [generation]. [fire]: the player's own
     * seek, to announce upstream. A seek that is not the player's own (a timeline correction) never replaces one that
     * is still waiting: the player asked to go there, and the server will be told.
     */
    fun request(targetNanos: Long, fire: Boolean, nowNanos: Long, generation: Long) {
        if (target != NONE && this.generation != generation) cancel()
        if (target != NONE && this.fire && !fire) return
        if (target == NONE) waitingSinceNanos = nowNanos
        target = targetNanos.coerceAtLeast(0L)
        this.fire = fire
        this.generation = generation
    }

    /** Where the waiting seek goes, or null: the base for a relative seek, and what a seek bar shows meanwhile. */
    fun pendingTarget(generation: Long): Long? = target.takeIf { it != NONE && this.generation == generation }

    /**
     * The waiting seek, to carry out now without opening anything: the display is about to play (a resume carries
     * its seek out first, so the play starts there). Null when none waits for the player of media [generation].
     */
    fun takePending(generation: Long): Seek? {
        val t = target
        if (t == NONE) return null
        if (this.generation != generation) {
            cancel()
            return null
        }
        return take(t, open = false)
    }

    /** A new video: whatever was asked for no longer applies. */
    fun cancel() {
        target = NONE
        fire = false
    }

    val pending: Boolean get() = target != NONE

    /**
     * The seek to carry out now, or null to wait. [eligible]: the display may still show a held frame (paused,
     * seekable, not live and not private); when it no longer may (it plays now), the seek is carried out at once
     * without opening anything. [busy]: an opening is under way; it is waited for. [showingNanos]: where a held stream
     * stands, null when none is held: a target it already shows needs nothing (or only the announcement). An opening that takes
     * longer than [MAX_WAIT_NANOS] is not waited for any longer: the player then re-opens at the new target itself.
     */
    /** Resuming: a waiting seek is carried out with [seek] first, then [play] runs, so the play starts there. */
    inline fun resume(generation: Long, seek: (Seek) -> Unit, play: () -> Unit) {
        takePending(generation)?.let(seek)
        play()
    }

    fun poll(nowNanos: Long, eligible: Boolean, busy: Boolean, showingNanos: Long?, generation: Long): Seek? {
        val t = target
        if (t == NONE) return null
        if (this.generation != generation) {
            cancel() // another video came in meanwhile: the seek was for the old one
            return null
        }
        if (!eligible) return take(t, open = false)
        if (showingNanos != null && kotlin.math.abs(showingNanos - t) <= SAME_FRAME_NANOS) {
            // Already held there: nothing to do, unless the seek must still be announced
            if (fire) return take(t, open = false)
            cancel()
            return null
        }
        if (busy && nowNanos - waitingSinceNanos < MAX_WAIT_NANOS) return null
        if (lastOpenNanos != Long.MIN_VALUE && nowNanos - lastOpenNanos < minIntervalNanos) return null
        lastOpenNanos = nowNanos
        return take(t, open = true)
    }

    private fun take(t: Long, open: Boolean): Seek {
        val s = Seek(t, fire, open)
        target = NONE
        fire = false
        return s
    }

    companion object {
        private const val NONE = Long.MIN_VALUE

        /** Longest a seek waits for an opening under way. */
        const val MAX_WAIT_NANOS = 1_500_000_000L

        /** At most this often (about 6 a second while scrubbing). */
        const val MIN_INTERVAL_NANOS = 150_000_000L

        /** A held stream this close to the target already shows its frame (under one frame at 24 fps). */
        const val SAME_FRAME_NANOS = 40_000_000L
    }
}
