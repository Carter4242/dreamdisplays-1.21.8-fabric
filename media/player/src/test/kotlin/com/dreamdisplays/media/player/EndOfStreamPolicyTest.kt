package com.dreamdisplays.media.player

import kotlin.test.Test
import kotlin.test.assertEquals

class EndOfStreamPolicyTest {
    @Test
    fun `a server timeline display never loops on its own end`() {
        assertEquals(EndOfStreamPolicy.Action.HOLD, EndOfStreamPolicy.onEnd(followsServerTimeline = true, shouldLoopOnEnd = true))
        assertEquals(EndOfStreamPolicy.Action.HOLD, EndOfStreamPolicy.onEnd(followsServerTimeline = true, shouldLoopOnEnd = false))
    }

    @Test
    fun `a local display loops, or stops when its fullscreen does not loop`() {
        assertEquals(EndOfStreamPolicy.Action.LOOP, EndOfStreamPolicy.onEnd(followsServerTimeline = false, shouldLoopOnEnd = true))
        assertEquals(EndOfStreamPolicy.Action.END, EndOfStreamPolicy.onEnd(followsServerTimeline = false, shouldLoopOnEnd = false))
    }
}
