package com.dreamdisplays.media.source.youtube

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YtStreamRefusalsTest {
    @AfterTest
    fun cleanUp() = YtStreamRefusals.clear()

    @Test
    fun firstRefusalOnlyAsksForAFreshResolve() {
        assertEquals(YtResolvePlan.DEFAULT, YtResolvePlan.forRefusals(0))
        assertEquals(YtResolvePlan.DEFAULT, YtResolvePlan.forRefusals(1))
        assertFalse(YtResolvePlan.forRefusals(1).escalated)
    }

    @Test
    fun repeatedRefusalLeavesNewPipeAndVisionos() {
        val plan = YtResolvePlan.forRefusals(2)
        assertTrue(plan.escalated)
        assertEquals("android_vr", plan.primaryClient)
        assertFalse("visionos" in plan.fallbackClients)
    }

    @Test
    fun thirdRefusalRacesTheRemainingClientsThenStartsOver() {
        val plan = YtResolvePlan.forRefusals(3)
        assertTrue(plan.escalated)
        assertNull(plan.primaryClient)
        assertFalse("visionos" in plan.fallbackClients || "android_vr" in plan.fallbackClients)
        assertTrue(plan.fallbackClients.isNotEmpty())
        assertEquals(YtResolvePlan.forRefusals(1), YtResolvePlan.forRefusals(4))
        assertEquals(YtResolvePlan.forRefusals(2), YtResolvePlan.forRefusals(5))
    }

    @Test
    fun refusalsAreCountedPerVideoWhateverTheLinkForm() {
        assertEquals(1, YtStreamRefusals.record("https://www.youtube.com/watch?v=b65MoVwANq4&t=30"))
        assertEquals(2, YtStreamRefusals.record("https://youtu.be/b65MoVwANq4"))
        assertEquals(2, YtStreamRefusals.count("https://www.youtube.com/watch?v=b65MoVwANq4"))
        assertTrue(YtStreamRefusals.plan("https://www.youtube.com/watch?v=b65MoVwANq4").escalated)
        assertEquals(0, YtStreamRefusals.count("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun nonYouTubeLinksAreNotTracked() {
        assertEquals(0, YtStreamRefusals.record("https://example.com/video.mp4"))
        assertFalse(YtStreamRefusals.plan("https://example.com/video.mp4").escalated)
    }
}
