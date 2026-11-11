package com.dreamdisplays.core.karaoke

/**
 * Places one singer's voice frames on a steady 20 ms grid in server time, so they can be re-sent without the network
 * jitter they arrived with.
 *
 * The client numbers its mic frames, but only counts frames it sends: voice activation and push-to-talk send bursts,
 * and the numbering pauses in between. So the grid is anchored per burst: within a burst, frame `q` was captured
 * `(q - base) * 20 ms` after the first one, and `arrival - (q - base) * 20 ms` (its *offset*) is the same for every
 * frame plus that frame's jitter. The lowest offset seen is the best estimate of the jitter-free arrival.
 *
 * A listener's client starts playing a burst when its first frame arrives and then plays at its own audio rate, so
 * the first frame sets the timing of the whole burst. Hence the anchor is not the first packet's arrival: it stays
 * open (the minimum offset so far) until the first frame of the burst is actually sent ([estimate] with `lock`), and
 * is then fixed. Frames arriving meanwhile improve it. What the minimum still misses at that point is learned from
 * past bursts (the median excess of the anchor at lock over the minimum over the first [windowFrames] frames) and
 * subtracted. Once locked, the anchor only moves if the whole recent window arrives later than it by more than
 * [reanchorNanos] (the singer's path got slower, or their client dropped capture time), since frames that keep
 * arriving late would otherwise all be sent late.
 *
 * Not thread-safe; the caller synchronises.
 */
class BurstAnchor(
    private val frameNanos: Long = KaraokeTiming.FRAME_MS * 1_000_000L,
    private val windowFrames: Int = 50,
    private val newBurstGapNanos: Long = 200_000_000L,
    private val reanchorNanos: Long = 40_000_000L,
    private val historySize: Int = 16,
    private val keptBursts: Int = 32,
) {
    private class Burst(val id: Int, var baseSeq: Long) {
        var lastSeq = baseSeq - 1
        var bestOffset = Long.MAX_VALUE
        var lockedOffset: Long? = null
        var bestAtLock = 0L
        var firstWindowMin = Long.MAX_VALUE
        var framesSeen = 0
        var framesSinceReanchor = 0
        var ended = false
        var learned = false
        val window = ArrayDeque<Long>()
    }

    private var nextId = 1
    private var current: Burst? = null
    private var previous: Burst? = null
    private val history = ArrayDeque<Long>()

    /** The last frame no burst could place, and how many such frames came in a row, numbered one after another. */
    private var orphanSeq = Long.MIN_VALUE
    private var orphanRun = 0

    /**
     * Recent bursts, newest last, so [estimate] still knows a burst whose frames are held back for longer than the
     * bursts after it last (voice activation sends a burst per phrase, often shorter than a listener's delay).
     */
    private val kept = ArrayDeque<Burst>()

    /** Bursts started so far. */
    var bursts: Int = 0
        private set

    /** Times a locked anchor had to move later. */
    var reanchors: Int = 0
        private set

    /**
     * Records frame [seq] arriving at [arrivalNanos] ([stop] for the empty frame that ends a burst). Returns the id of
     * the burst it belongs to, for [estimate], or -1 for a straggler too old to place.
     */
    fun onFrame(arrivalNanos: Long, seq: Long, stop: Boolean = false): Int {
        val burst = burstFor(arrivalNanos, seq) ?: return -1
        val offset = arrivalNanos - (seq - burst.baseSeq) * frameNanos
        if (seq > burst.lastSeq) burst.lastSeq = seq
        burst.framesSeen++
        burst.framesSinceReanchor++
        if (offset < burst.bestOffset) burst.bestOffset = offset
        if (burst.framesSeen <= windowFrames && offset < burst.firstWindowMin) burst.firstWindowMin = offset
        burst.window.addLast(offset)
        while (burst.window.size > windowFrames) burst.window.removeFirst()
        if (burst.framesSeen >= windowFrames) learn(burst)
        reanchorIfLate(burst)
        if (stop) {
            burst.ended = true
            learn(burst)
        }
        return burst.id
    }

    /**
     * The jitter-free arrival estimate of frame [seq] of burst [burstId], in the clock of `arrivalNanos`, or null when
     * the burst is no longer known. [lock] fixes the burst's anchor (call it when the first frame is actually sent).
     */
    fun estimate(burstId: Int, seq: Long, lock: Boolean): Long? {
        val burst = find(burstId) ?: return null
        val offset = burst.lockedOffset ?: run {
            val open = burst.bestOffset - prior()
            if (lock) {
                burst.lockedOffset = open
                burst.bestAtLock = burst.bestOffset
                burst.framesSinceReanchor = 0
            }
            open
        }
        return offset + (seq - burst.baseSeq) * frameNanos
    }

    /** What is subtracted from a new burst's minimum offset at lock time: the median learned excess, 0 at first. */
    fun prior(): Long {
        if (history.isEmpty()) return 0L
        val sorted = history.sorted()
        return sorted[sorted.size / 2]
    }

    private fun find(id: Int): Burst? = kept.lastOrNull { it.id == id }

    private fun burstFor(arrivalNanos: Long, seq: Long): Burst? {
        val cur = current
        if (cur != null && seq in cur.baseSeq..cur.lastSeq) return cur
        val prev = previous
        if (cur != null && seq < cur.baseSeq) {
            prev?.takeIf { seq >= it.baseSeq && seq <= it.lastSeq }?.let { return it }
            // Older than the start of this burst but after the last one: its first frames came in out of order.
            // Never file them under the previous burst, whose grid knows nothing of the silence in between.
            if ((prev == null || seq > prev.lastSeq) && cur.baseSeq - seq <= MAX_REBASE_FRAMES) {
                rebase(cur, seq)
                return cur
            }
            // Several new frames in a row numbered below everything known: the client started counting again (voice
            // chat reconnected or reloaded its audio). A late straggler never comes as such a run.
            orphanRun = if (seq == orphanSeq + 1) orphanRun + 1 else 1
            orphanSeq = seq
            if (orphanRun < RESTART_RUN_FRAMES) return null
            orphanRun = 0
            learn(cur)
            previous = null
            return startBurst(seq)
        }
        val startNew = when {
            cur == null || cur.ended -> true
            seq == cur.lastSeq + 1 -> false
            seq < cur.baseSeq -> true
            // Frames missing: lost on the way, or a new burst whose end-of-burst frame was lost. A new burst arrives
            // far later than the old grid says.
            else -> arrivalNanos - (seq - cur.baseSeq) * frameNanos - (cur.lockedOffset ?: cur.bestOffset) > newBurstGapNanos
        }
        if (!startNew) return cur
        cur?.let(::learn)
        previous = cur
        return startBurst(seq)
    }

    private fun startBurst(seq: Long): Burst {
        bursts++
        return Burst(nextId++, seq).also {
            current = it
            kept.addLast(it)
            while (kept.size > keptBursts) kept.removeFirst()
        }
    }

    /** Moves [burst]'s first frame back to [seq]; every offset is relative to it, so they all shift by the same. */
    private fun rebase(burst: Burst, seq: Long) {
        val shift = (burst.baseSeq - seq) * frameNanos
        burst.baseSeq = seq
        if (burst.bestOffset != Long.MAX_VALUE) burst.bestOffset -= shift
        if (burst.firstWindowMin != Long.MAX_VALUE) burst.firstWindowMin -= shift
        burst.lockedOffset = burst.lockedOffset?.minus(shift)
        burst.bestAtLock -= shift
        val shifted = burst.window.map { it - shift }
        burst.window.clear()
        burst.window.addAll(shifted)
    }

    private fun learn(burst: Burst) {
        // Only a burst that was sent (locked) says how far its anchor was off
        if (burst.learned || burst.lockedOffset == null || burst.framesSeen < MIN_FRAMES_TO_LEARN) return
        burst.learned = true
        val excess = (burst.bestAtLock - burst.firstWindowMin).coerceAtLeast(0L)
        history.addLast(excess)
        while (history.size > historySize) history.removeFirst()
    }

    private fun reanchorIfLate(burst: Burst) {
        val locked = burst.lockedOffset ?: return
        if (burst.framesSinceReanchor < windowFrames / 2 || burst.window.size < windowFrames / 2) return
        val windowMin = burst.window.min()
        if (windowMin - locked <= reanchorNanos) return
        burst.lockedOffset = windowMin
        burst.framesSinceReanchor = 0
        reanchors++
    }

    private companion object {
        const val MIN_FRAMES_TO_LEARN = 5
        const val MAX_REBASE_FRAMES = 10

        /** Frames in a row that say the client's frame count started over. */
        const val RESTART_RUN_FRAMES = 3
    }
}
