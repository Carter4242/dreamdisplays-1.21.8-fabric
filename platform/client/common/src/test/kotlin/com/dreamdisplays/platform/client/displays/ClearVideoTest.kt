package com.dreamdisplays.platform.client.displays

import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClearVideoTest {
    @Test
    fun `a clear says so explicitly and carries no video`() {
        val id = UUID.randomUUID()
        val request = clearRequest(id)
        assertTrue(request.clear)
        assertEquals("", request.url)
        assertEquals("", request.lang)
        assertEquals(id, request.id)
    }
}
