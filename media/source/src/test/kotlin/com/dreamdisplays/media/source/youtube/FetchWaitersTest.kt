package com.dreamdisplays.media.source.youtube

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FetchWaitersTest {
    private val url = "https://www.youtube.com/watch?v=abc"

    @Test
    fun `a lost resolve stops the fetch only while it is the one caller waiting`() {
        val w = FetchWaiters()
        val loser = Any()
        w.join(url, loser)
        var stopped = 0
        assertTrue(w.stopIfOnly(url, loser) { stopped++ })
        assertEquals(1, stopped)
    }

    @Test
    fun `a metadata lookup or another display waiting on the same fetch keeps it going`() {
        val w = FetchWaiters()
        val loser = Any()
        val metadata = Any()
        w.join(url, loser)
        w.join(url, metadata)
        assertFalse(w.stopIfOnly(url, loser) { error("must not stop a fetch someone else waits on") })
        w.leave(url, metadata)
        assertTrue(w.stopIfOnly(url, loser) {})
    }

    @Test
    fun `a resolve that is not waiting stops nothing, even when someone else waits alone`() {
        val w = FetchWaiters()
        val other = Any()
        w.join(url, other)
        assertFalse(w.stopIfOnly(url, Any()) { error("the fetch in flight is someone else's") })
        assertFalse(w.stopIfOnly("https://other", Any()) { error("nothing waits") })
    }

    @Test
    fun `owners are told apart by identity, not equality`() {
        val w = FetchWaiters()
        val a = "same"
        val b = String("same".toCharArray())
        w.join(url, a)
        w.join(url, b)
        w.leave(url, b)
        assertEquals(1, w.count(url))
        assertTrue(w.stopIfOnly(url, a) {})
        w.leave(url, a)
        assertEquals(0, w.count(url))
    }

    @Test
    fun `a caller joining while the stop runs waits for it and is then counted, so its own fetch is never the one stopped`() {
        val w = FetchWaiters()
        val loser = Any()
        w.join(url, loser)
        val stopping = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopper = Thread {
            w.stopIfOnly(url, loser) {
                stopping.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }.apply { start() }
        assertTrue(stopping.await(5, TimeUnit.SECONDS))
        val joined = CountDownLatch(1)
        val joiner = Thread { w.join(url, Any()); joined.countDown() }.apply { start() }
        // Blocked behind the stop
        assertFalse(joined.await(200, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(joined.await(5, TimeUnit.SECONDS))
        stopper.join(5_000)
        joiner.join(5_000)
        assertEquals(2, w.count(url))
        // Now two wait: the loser can no longer stop it
        assertFalse(w.stopIfOnly(url, loser) { error("a second caller waits") })
    }
}
