package com.dreamdisplays.platform.client.net

import com.dreamdisplays.util.FacingUtil
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PacketDispatchTest {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Test
    fun `a handler that throws on a malformed packet is contained`() {
        // A DisplayInfo with an out-of-range facing byte throws here on the client thread
        assertFalse(dispatchGuarded("DisplayInfo", logger) { FacingUtil.fromPacket(6) })
    }

    @Test
    fun `a handler that succeeds reports so`() {
        var ran = false
        assertTrue(dispatchGuarded("DisplaySync", logger) { ran = true })
        assertTrue(ran)
    }
}
