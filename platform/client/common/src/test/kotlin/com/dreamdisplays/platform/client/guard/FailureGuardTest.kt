package com.dreamdisplays.platform.client.guard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FailureGuardTest {
    private var now = 0L
    private val logs = ArrayList<Pair<String, Throwable?>>()
    private val reports = ArrayList<Pair<String, Throwable>>()
    private val guard = FailureGuard(
        log = { message, error -> logs += message to error },
        report = { site, error -> reports += site to error },
        clock = { now },
        logIntervalMs = 10_000L,
    )

    @Test
    fun `a throwing handler returns the fallback and is logged and reported`() {
        val error = IllegalArgumentException("Invalid YouTube video id: https://www.instagram.com/reel/x")
        val consumed = guard.call("DisplayMenu.mouseClicked", true) { throw error }
        assertTrue(consumed)
        assertEquals(1, logs.size)
        assertSame(error, logs[0].second, "the first failure is logged with its trace")
        assertEquals(listOf("DisplayMenu.mouseClicked" to error), reports.map { it.first to it.second })
    }

    @Test
    fun `a handler that completes returns its own value and records nothing`() {
        assertFalse(guard.call("s", true) { false })
        assertTrue(guard.attempt("s") {})
        assertTrue(logs.isEmpty())
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `attempt says whether the block completed`() {
        assertFalse(guard.attempt("s") { error("boom") })
        assertEquals(1, reports.size)
    }

    @Test
    fun `a failure repeating every frame is logged once per interval with a count, and reported every time`() {
        repeat(100) { guard.attempt("DisplayMenu.render") { throw IllegalStateException("same") } }
        assertEquals(1, logs.size)
        now += 10_000L
        guard.attempt("DisplayMenu.render") { throw IllegalStateException("same") }
        assertEquals(2, logs.size)
        assertNull(logs[1].second, "repeats are logged without the trace")
        assertTrue(logs[1].first.contains("100 times"), logs[1].first)
        // The reporter does its own dedupe and rate limits; the guard hands every failure on
        assertEquals(101, reports.size)
    }

    @Test
    fun `different sites or errors are each logged with a trace`() {
        guard.attempt("a") { throw IllegalStateException("x") }
        guard.attempt("b") { throw IllegalStateException("x") }
        guard.attempt("a") { throw IllegalArgumentException("x") }
        assertEquals(3, logs.size)
        assertTrue(logs.all { it.second != null })
    }

    @Test
    fun `errors the JVM cannot recover from are rethrown`() {
        assertFailsWith<OutOfMemoryError> { guard.attempt("s") { throw OutOfMemoryError("test") } }
        assertTrue(reports.isEmpty())
        // A runaway recursion has unwound by the time it reaches the guard
        assertFalse(guard.attempt("s") { throw StackOverflowError() })
        // Kotlin's TODO() and assertion errors are Errors but not fatal ones
        assertFalse(guard.attempt("s") { TODO() })
        assertEquals(2, reports.size)
    }

    @Test
    fun `a reporter that throws does not escape the guard`() {
        val failing = FailureGuard(log = { m, e -> logs += m to e }, report = { _, _ -> error("report broke") })
        assertEquals(7, failing.call("s", 7) { throw RuntimeException("x") })
        assertTrue(logs.any { it.first.contains("Could not report") })
    }

    @Test
    fun `the compact trace starts with the error and keeps its root cause`() {
        val root = IllegalArgumentException("root")
        val wrapped = RuntimeException("outer", root)
        val trace = FailureGuard.compactTrace(wrapped, maxLines = 14)
        val lines = trace.lines()
        assertTrue(lines.size <= 14)
        assertTrue(lines[0].startsWith("java.lang.RuntimeException: outer"), lines[0])
        assertNotNull(lines.firstOrNull { it == "Caused by: java.lang.IllegalArgumentException: root" }, trace)
        assertTrue(FailureGuard.compactTrace(root).lines()[1].startsWith("  at "))
    }
}

class ScreenFailuresTest {
    @Test
    fun `a render failing every frame closes the screen after the limit`() {
        val f = ScreenFailures(limit = 3)
        f.rendered(false)
        f.rendered(false)
        assertFalse(f.closeRequested)
        f.rendered(false)
        assertTrue(f.closeRequested)
    }

    @Test
    fun `an occasional render failure does not close the screen`() {
        val f = ScreenFailures(limit = 3)
        repeat(10) {
            f.rendered(false)
            f.rendered(false)
            f.rendered(true)
        }
        assertFalse(f.closeRequested)
    }

    @Test
    fun `ticks that keep failing close the screen`() {
        val f = ScreenFailures(limit = 3)
        repeat(3) { f.ticked(false) }
        assertTrue(f.closeRequested)
    }

    @Test
    fun `a failed init closes at once and a rebuild starts over`() {
        val f = ScreenFailures()
        f.initialized(false)
        assertTrue(f.closeRequested)
        f.reset()
        f.initialized(true)
        assertFalse(f.closeRequested)
    }
}
