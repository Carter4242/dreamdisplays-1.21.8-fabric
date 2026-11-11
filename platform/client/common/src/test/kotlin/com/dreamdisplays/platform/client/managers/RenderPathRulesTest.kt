package com.dreamdisplays.platform.client.managers

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderPathRulesTest {
    private fun inPlace(shadersOn: Boolean = false, server: Boolean = false, kept: Boolean = true, yuv: Boolean = false, alpha: Boolean = false) =
        RenderPathRules.newPlayerInPlace(shadersOn, server, kept, yuv, alpha)

    @Test
    fun aDisplayKeptUpThroughShadersOnGetsANewPlayerWhenItDecodesForAPathShadersCantDraw() {
        assertTrue(inPlace(shadersOn = true, yuv = true))
        assertTrue(inPlace(shadersOn = true, alpha = true))
    }

    @Test
    fun aPlainRgbaPlayerKeepsPlayingThroughShadersOn() {
        assertFalse(inPlace(shadersOn = true))
    }

    @Test
    fun aServerPathChangeRebuildsEveryDisplayKeptUp() {
        assertTrue(inPlace(server = true))
    }

    @Test
    fun displaysNotKeptUpAreRebuiltByTheTeardownInstead() {
        assertFalse(inPlace(shadersOn = true, server = true, kept = false, yuv = true, alpha = true))
    }

    @Test
    fun switchingShadersOffNeedsNothing() {
        assertFalse(inPlace(shadersOn = false, yuv = true, alpha = true))
    }
}
