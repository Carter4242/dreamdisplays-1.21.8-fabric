package com.dreamdisplays.platform.client.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PopoutRequestsTest {
    @Test
    fun `window, PiP, window before the EDT runs, only the last open acts`() {
        val requests = PopoutRequests()
        val open1 = requests.next()
        val close = requests.next()
        val open2 = requests.next()
        // The EDT runs them in order: the first open and the close are superseded, the reopen goes ahead
        assertFalse(requests.isLatest(open1))
        assertFalse(requests.isLatest(close))
        assertTrue(requests.isLatest(open2))
    }

    @Test
    fun `a close made after an open wins`() {
        val requests = PopoutRequests()
        val open = requests.next()
        val close = requests.next()
        assertFalse(requests.isLatest(open))
        assertTrue(requests.isLatest(close))
    }
}
