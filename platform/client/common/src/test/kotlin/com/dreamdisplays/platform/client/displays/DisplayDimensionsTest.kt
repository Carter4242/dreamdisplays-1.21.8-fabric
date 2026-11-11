package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayDimensionsTest {
    private val overworld = "minecraft:overworld"
    private val nether = "minecraft:the_nether"

    @Test
    fun ordinaryDescriptionTagsTheCurrentWorld() {
        assertEquals(overworld, DisplayDimensions.keyForNewScreen(serverWorld = "", forced = false, virtual = false, currentKey = overworld))
    }

    @Test
    fun forcedOrVirtualDescriptionLeavesTheWorldUnknown() {
        // A pinned PiP restored on join, or a fullscreen target, may be in any world
        assertEquals("", DisplayDimensions.keyForNewScreen(serverWorld = "", forced = true, virtual = false, currentKey = nether))
        assertEquals("", DisplayDimensions.keyForNewScreen(serverWorld = "", forced = false, virtual = true, currentKey = nether))
    }

    @Test
    fun anOrdinaryDescriptionLaterTeachesTheWorld() {
        assertEquals(overworld, DisplayDimensions.learnedKey("", serverWorld = "", forced = false, virtual = false, currentKey = overworld))
        // A forced one teaches nothing, and never overwrites a known world
        assertEquals(overworld, DisplayDimensions.learnedKey(overworld, serverWorld = "", forced = true, virtual = false, currentKey = nether))
        assertEquals("", DisplayDimensions.learnedKey("", serverWorld = "", forced = true, virtual = false, currentKey = nether))
        assertEquals("", DisplayDimensions.learnedKey("", serverWorld = "", forced = false, virtual = true, currentKey = nether))
    }

    @Test
    fun aWorldNamedByTheServerWins() {
        // Protocol 12: a pinned PiP described on join while the player is in the Nether is still filed as Overworld
        assertEquals(overworld, DisplayDimensions.keyForNewScreen(overworld, forced = true, virtual = false, currentKey = nether))
        assertEquals(overworld, DisplayDimensions.learnedKey("", overworld, forced = true, virtual = false, currentKey = nether))
        assertEquals(nether, DisplayDimensions.learnedKey(overworld, nether, forced = false, virtual = false, currentKey = overworld))
    }

    @Test
    fun onlyAKnownMatchingWorldIsDrawnHere() {
        assertTrue(DisplayDimensions.inWorldHere(virtual = false, screenKey = overworld, currentKey = overworld))
        // Carried through a portal: kept for its PiP, never drawn at the same coordinates in the Nether
        assertFalse(DisplayDimensions.inWorldHere(virtual = false, screenKey = overworld, currentKey = nether))
        assertFalse(DisplayDimensions.inWorldHere(virtual = false, screenKey = "", currentKey = overworld))
        // A URL broadcast's virtual display has no place in any world
        assertFalse(DisplayDimensions.inWorldHere(virtual = true, screenKey = overworld, currentKey = overworld))
    }

    @Test
    fun unknownWorldIsNotKeptForADistanceRestore() {
        assertFalse(DisplayDimensions.cacheableForRestore(""))
        assertTrue(DisplayDimensions.cacheableForRestore(nether))
    }
}
