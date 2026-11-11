package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.platform.client.managers.displayWorld
import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayWorldTest {
    @Test
    fun `the server's world wins over the player's dimension`() {
        assertEquals("minecraft:overworld", displayWorld("minecraft:overworld", "minecraft:the_nether"))
    }

    @Test
    fun `an older server's display is placed in the player's dimension`() {
        assertEquals("minecraft:the_nether", displayWorld("", "minecraft:the_nether"))
    }
}
