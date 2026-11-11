package com.dreamdisplays.platform.client.displays

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResumePositionTest {
    @Test
    fun `a position saved in another video is not resumed`() {
        assertTrue(ResumePosition.belongsToOtherVideo("https://youtu.be/a", "https://youtu.be/b"))
        assertTrue(ResumePosition.belongsToOtherVideo("https://youtu.be/a", ""))
    }

    @Test
    fun `the same video, or settings that never recorded one, resume`() {
        assertFalse(ResumePosition.belongsToOtherVideo("https://youtu.be/a", "https://youtu.be/a"))
        assertFalse(ResumePosition.belongsToOtherVideo(null, "https://youtu.be/b"))
    }
}
