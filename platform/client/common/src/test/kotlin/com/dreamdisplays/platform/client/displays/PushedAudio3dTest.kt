package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.media.audio.AcousticQuality
import com.dreamdisplays.core.display.policy.PushedSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A pushed 3D audio tier becomes the viewer's own choice; a tier the server's policy sets still wins over it. */
class PushedAudio3dTest {
    private fun pushed(raw: String): AcousticQuality? {
        val value = PushedSetting.AUDIO_3D.decode(PushedSetting.AUDIO_3D.parse(raw)!!) as PushedSetting.Value.Audio3d
        return AcousticsTiers.parse(value.tier) // what PushedDefaultsClient gives DisplayScreen.acousticsChoice
    }

    @Test
    fun `a pushed tier is the viewer's choice`() {
        assertEquals(AcousticQuality.ULTRA, pushed("ultra"))
        assertNull(pushed("default"))
        assertEquals(AcousticQuality.BASIC, AcousticsTiers.effective(null, false, pushed("basic"), AcousticQuality.ADVANCED))
    }

    @Test
    fun `the server's policy tier wins over a pushed one`() {
        val choice = pushed("ultra")
        assertEquals(AcousticQuality.OFF, AcousticsTiers.effective(AcousticQuality.OFF, false, choice, AcousticQuality.ADVANCED))
        assertEquals(AcousticQuality.ADVANCED, AcousticsTiers.effective(null, true, choice, AcousticQuality.ADVANCED))
    }
}
