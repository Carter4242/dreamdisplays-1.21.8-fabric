package com.dreamdisplays.platform.client.ui.menu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OncePerKeyTest {
    /** Launched tasks wait here until the test runs them. */
    private val launched = ArrayDeque<() -> Unit>()
    private val fetched = ArrayList<String>()
    private val answers = HashMap<String, Long?>()

    private fun lookup(maxConcurrent: Int = 2, maxKeys: Int = 100) =
        OncePerKey<String, Long>(maxConcurrent, maxKeys, { launched.addLast(it) }) { key ->
            fetched += key
            if (key == "boom") error("network down")
            answers[key]
        }

    private fun runNext() = launched.removeFirst()()

    @Test
    fun fetchesOnceAndThenAnswersFromMemory() {
        answers["a"] = 213L
        val l = lookup()
        assertNull(l.get("a"))
        assertNull(l.get("a"))
        assertEquals(1, launched.size)
        runNext()
        assertEquals(213L, l.get("a"))
        assertEquals(213L, l.get("a"))
        assertEquals(listOf("a"), fetched)
        assertEquals(0, launched.size)
    }

    @Test
    fun noAnswerOrAFailureIsNeverRetried() {
        answers["live"] = null
        val l = lookup()
        l.get("live")
        l.get("boom")
        runNext()
        runNext()
        repeat(3) {
            assertNull(l.get("live"))
            assertNull(l.get("boom"))
        }
        assertEquals(listOf("live", "boom"), fetched)
        assertEquals(0, launched.size)
    }

    @Test
    fun runsAtMostMaxConcurrentAtOnceAndTheRestInOrder() {
        listOf("a", "b", "c", "d").forEach { answers[it] = 1L; }
        val l = lookup(maxConcurrent = 2)
        listOf("a", "b", "c", "d").forEach { l.get(it) }
        assertEquals(2, launched.size)
        runNext() // a done: c starts
        assertEquals(2, launched.size)
        runNext() // b done: d starts
        runNext()
        runNext()
        assertEquals(0, launched.size)
        assertEquals(listOf("a", "b", "c", "d"), fetched)
        listOf("a", "b", "c", "d").forEach { assertEquals(1L, l.get(it)) }
    }

    @Test
    fun stopsLookingUpNewKeysPastTheCap() {
        answers["a"] = 1L
        answers["b"] = 2L
        val l = lookup(maxKeys = 1)
        l.get("a")
        l.get("b")
        assertEquals(1, launched.size)
        runNext()
        assertEquals(1L, l.get("a"))
        assertNull(l.get("b"))
        assertEquals(listOf("a"), fetched)
    }

    @Test
    fun aLaunchThatThrowsFreesItsSlot() {
        answers["a"] = 1L
        answers["b"] = 2L
        var failNext = true
        val l = OncePerKey<String, Long>(1, 100, { task ->
            if (failNext) {
                failNext = false
                error("scope cancelled")
            }
            launched.addLast(task)
        }) { answers[it] }
        l.get("a")
        l.get("b")
        assertEquals(1, launched.size)
        runNext()
        assertNull(l.get("a"))
        assertEquals(2L, l.get("b"))
    }
}
