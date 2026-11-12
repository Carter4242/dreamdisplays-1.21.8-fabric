package com.dreamdisplays.core.queue

/**
 * What clearing a display's video (the menu's Clear button, `/display video <display> clear`) does: "take this off and
 * move on". With an entry waiting in its queue the display moves on to it exactly like a skip; only with nothing
 * waiting does it go blank. A screen share running there ends first either way, without bringing back what it replaced.
 */
object QueueOnClear {
    enum class Outcome {
        /** Start the queue's next entry the way a skip does (preloads, karaoke line and loop as there). */
        NEXT_ENTRY,

        /** Nothing waiting: the display goes blank (its singers stop, fullscreen sessions of it end). */
        BLANK,
    }

    /**
     * [queueRuns]: queues are on and run on this display (synced / broadcast, not virtual); [queue]: its queue, null
     * when it has none. A looping queue whose only entry is the one playing has nothing waiting (a skip does nothing
     * there either); with entries waiting, the cleared entry goes back to the end, as on a skip. A screen share ended
     * just before changes nothing here: the entry it interrupted is no current entry any more, as for a skip during one.
     */
    fun outcome(queueRuns: Boolean, queue: DisplayQueue?): Outcome =
        if (queueRuns && queue != null && queue.hasNext()) Outcome.NEXT_ENTRY else Outcome.BLANK
}
