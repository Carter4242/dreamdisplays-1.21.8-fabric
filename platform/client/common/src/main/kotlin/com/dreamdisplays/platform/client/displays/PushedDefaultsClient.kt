package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.core.display.policy.PushedDefaults
import com.dreamdisplays.core.display.policy.PushedSetting
import com.dreamdisplays.platform.client.replay.ReplayHooks
import com.dreamdisplays.platform.client.storage.ClientSettingsStore
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Applies the values an admin pushed for a display (`/display defaults`, [PushedDefaults]) to this player's own settings
 * for it: each push once, recorded per setting in `client-display-settings.json`, so whatever the player sets afterwards
 * stands until the next push. Runs for every description of a display, so a push lands at once for a player watching it
 * and on first sight (or the next join) for everyone else. While displays are off here (the mod's switch, `/display
 * off`) descriptions are held and handled when they come back on, so a push made meanwhile applies then. The server's
 * policy levers still apply on top.
 */
object PushedDefaultsClient {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Applies what [customData] (a display's accepted custom data) pushes that this client has not applied yet. */
    fun apply(displayId: UUID, customData: Map<String, ByteArray>) {
        val bytes = customData[PushedDefaults.KEY] ?: return
        // A replay shows the displays of the server it was recorded on: it must not change the player's settings
        if (ReplayHooks.inReplay()) return
        val pushed = PushedDefaults.decode(bytes) ?: return
        val settings = ClientSettingsStore.getSettings(displayId, DisplayScreen.defaultVolume())
        val pending = pushed.pending(settings.pushedRevisions)
        if (pending.isEmpty()) return
        val screen = DisplayRegistry.screens[displayId]
        for (p in pending) {
            if (screen != null) applyTo(screen, p.value) else applyToSaved(displayId, p.value)
        }
        ClientSettingsStore.markPushedApplied(displayId, pending.associate { it.setting.wire to it.rev })
        logger.info(
            "Display {}: applied the server's pushed {}.", displayId,
            pending.joinToString(", ") { "${it.setting.wire} ${it.value} (revision ${it.rev})" },
        )
    }

    /** Through the live screen, so it is heard / seen at once and saved like a change from its menu. */
    private fun applyTo(screen: DisplayScreen, value: PushedSetting.Value) {
        when (value) {
            is PushedSetting.Value.Volume -> screen.volume = value.fraction
            is PushedSetting.Value.Brightness -> screen.brightness = value.level
            is PushedSetting.Value.Quality -> screen.quality = VideoQuality.parse(value.label)
            is PushedSetting.Value.Audio3d -> screen.acousticsChoice = AcousticsTiers.parse(value.tier)
            is PushedSetting.Value.Mute -> screen.mute(value.muted)
        }
    }

    /** A display not loaded here: into its saved settings, which it is created from. */
    private fun applyToSaved(displayId: UUID, value: PushedSetting.Value) {
        val s = ClientSettingsStore.getSettings(displayId)
        when (value) {
            is PushedSetting.Value.Volume -> s.volume = value.fraction
            is PushedSetting.Value.Brightness -> s.brightness = value.level
            is PushedSetting.Value.Quality -> s.quality = VideoQuality.parse(value.label).serialize()
            is PushedSetting.Value.Audio3d -> s.acoustics = AcousticsTiers.parse(value.tier)?.name
            is PushedSetting.Value.Mute -> s.muted = value.muted
        }
        ClientSettingsStore.save()
    }
}
