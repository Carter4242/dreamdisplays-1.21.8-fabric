package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.display.model.settings.ClientDisplaySettings
import com.dreamdisplays.api.media.audio.AcousticQuality
import com.dreamdisplays.api.media.audio.AcousticQuality.*
import com.dreamdisplays.media.audio.engine.AcousticsEngine
import com.dreamdisplays.util.json.DreamJson
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AcousticsTiersTest {
    @Test
    fun aDisplayWithNoChoiceFollowsTheDefault() {
        assertNull(AcousticsTiers.sourceTier(null, serverForced = false, choice = null))
        assertEquals(ULTRA, AcousticsTiers.effective(null, serverForced = false, choice = null, global = ULTRA))
    }

    @Test
    fun theViewersChoiceWinsOverTheDefault() {
        assertEquals(OFF, AcousticsTiers.sourceTier(null, serverForced = false, choice = OFF))
        assertEquals(OFF, AcousticsTiers.effective(null, serverForced = false, choice = OFF, global = ULTRA))
        assertFalse(AcousticsTiers.forced(null, serverForced = false))
    }

    @Test
    fun aServerWideTierWinsOverTheViewersChoice() {
        // The global tier is then the server's ([ServerOverrides.audioAcoustics]), so the source just follows it
        assertNull(AcousticsTiers.sourceTier(null, serverForced = true, choice = ULTRA))
        assertEquals(BASIC, AcousticsTiers.effective(null, serverForced = true, choice = ULTRA, global = BASIC))
        assertTrue(AcousticsTiers.forced(null, serverForced = true))
    }

    @Test
    fun theDisplaysPolicyTierWinsOverEverything() {
        for (forced in listOf(false, true)) for (choice in listOf(null, OFF, ULTRA)) {
            assertEquals(ADVANCED, AcousticsTiers.sourceTier(ADVANCED, forced, choice))
            assertEquals(ADVANCED, AcousticsTiers.effective(ADVANCED, forced, choice, global = OFF))
            assertTrue(AcousticsTiers.forced(ADVANCED, forced))
        }
    }

    @Test
    fun theEngineRendersEachDisplayAtItsEffectiveTier() {
        val engine = AcousticsEngine()
        val global = ADVANCED
        engine.setGlobalQuality(global)
        val cases = listOf(
            Triple<AcousticQuality?, Boolean, AcousticQuality?>(null, false, null),
            Triple(null, false, OFF),
            Triple(null, true, OFF),
            Triple(ULTRA, true, OFF),
            Triple(BASIC, false, ULTRA),
        )
        for ((policy, forced, choice) in cases) {
            val id = UUID.randomUUID()
            engine.setSourceQuality(id, AcousticsTiers.sourceTier(policy, forced, choice))
            engine.registerSource(id)
            assertEquals(AcousticsTiers.effective(policy, forced, choice, global), engine.qualityFor(id))
        }
        // One display's choice leaves the others on the default
        assertEquals(global, engine.qualityFor(UUID.randomUUID()))
    }

    @Test
    fun savedTierNamesParseAndUnknownOnesFollowTheDefault() {
        assertEquals(ULTRA, AcousticsTiers.parse("ULTRA"))
        assertEquals(OFF, AcousticsTiers.parse("off"))
        assertNull(AcousticsTiers.parse(null))
        assertNull(AcousticsTiers.parse("hyper"))
    }

    @Test
    fun settingsSavedBeforeAndAfterTheChoiceReadBack() {
        val old = DreamJson.pretty.decodeFromString(ClientDisplaySettings.serializer(), """{"volume":0.3,"muted":true}""")
        assertNull(old.acoustics)
        val unset = DreamJson.pretty.encodeToString(ClientDisplaySettings.serializer(), old)
        assertFalse("\"acoustics\"" in unset) // explicitNulls = false: nothing written while it follows the default
        val chosen = old.copy(acoustics = OFF.name)
        val text = DreamJson.pretty.encodeToString(ClientDisplaySettings.serializer(), chosen)
        assertEquals(chosen, DreamJson.pretty.decodeFromString(ClientDisplaySettings.serializer(), text))
    }
}
