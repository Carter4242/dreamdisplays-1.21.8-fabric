package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.player.process.win.WinApi
import kotlin.test.Test
import kotlin.test.assertEquals

class ComRefCountTest {
    @Test
    fun `the object is let go only when its creator and every COM holder have released it`() {
        var freed = 0
        val refs = WinApi.ComRefCount { freed++ }
        // Windows takes its references during the activation call
        refs.addRef()
        refs.addRef()
        refs.release()
        // The caller gives up (a timeout): Windows still holds one, so the callbacks must stay alive
        refs.release()
        assertEquals(0, freed)
        // The late completion ran and Windows let go
        assertEquals(0, refs.release())
        assertEquals(1, freed)
    }
}
