package com.dreamdisplays.platform.client.net

import com.dreamdisplays.util.FacingUtil
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PacketDispatchTest {
    @Test
    fun `a handler that throws on a malformed packet is contained`() {
        var error: Exception? = null
        // A DisplayInfo with an out-of-range facing byte throws here on the client thread
        assertFalse(dispatchGuarded({ FacingUtil.fromPacket(6) }) { error = it })
        assertIs<IllegalArgumentException>(error)
    }

    @Test
    fun `a handler that succeeds reports so`() {
        var ran = false
        assertTrue(dispatchGuarded({ ran = true }) { throw AssertionError("no error expected") })
        assertTrue(ran)
    }
}
