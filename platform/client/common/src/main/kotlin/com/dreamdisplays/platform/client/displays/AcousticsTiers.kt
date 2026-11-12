package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.media.audio.AcousticQuality

/**
 * Which 3D audio tier a display's sound renders with. Highest first:
 * 1. the display's own policy tier (`client.audio_acoustics` set on that display by an admin),
 * 2. the server-wide policy tier (forced for every display),
 * 3. the viewer's own choice for this display (its menu slider),
 * 4. the viewer's default tier (`audio-acoustics` in the client config).
 *
 * The engine holds 2 or 4 as its global tier ([ServerOverrides.audioAcoustics]); a display gets a per-source tier
 * ([sourceTier]) only when 1 or 3 applies to it.
 */
object AcousticsTiers {
    /**
     * The tier to give this display's audio source for itself, or null to follow the engine's global tier.
     * [displayPolicy] is the display policy's tier, [serverForced] whether the server-wide policy sets one, [choice]
     * the viewer's own choice for the display.
     */
    fun sourceTier(displayPolicy: AcousticQuality?, serverForced: Boolean, choice: AcousticQuality?): AcousticQuality? =
        displayPolicy ?: choice.takeUnless { serverForced }

    /** The tier the display renders with; [global] is the engine's global tier (server-wide, else the default). */
    fun effective(
        displayPolicy: AcousticQuality?,
        serverForced: Boolean,
        choice: AcousticQuality?,
        global: AcousticQuality,
    ): AcousticQuality = sourceTier(displayPolicy, serverForced, choice) ?: global

    /** Whether the server decides this display's tier, so the viewer's choice does not apply. */
    fun forced(displayPolicy: AcousticQuality?, serverForced: Boolean): Boolean = displayPolicy != null || serverForced

    /** A saved tier name back to its tier; null (follow the default) for none or an unknown name. */
    fun parse(name: String?): AcousticQuality? =
        name?.let { n -> AcousticQuality.entries.firstOrNull { it.name.equals(n.trim(), ignoreCase = true) } }
}
