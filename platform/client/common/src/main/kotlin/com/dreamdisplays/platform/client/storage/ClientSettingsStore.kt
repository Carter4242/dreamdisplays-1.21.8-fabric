package com.dreamdisplays.platform.client.storage

import com.dreamdisplays.core.share.ScreenSharePrivacy
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.display.model.settings.ClientDisplaySettings
import com.dreamdisplays.api.display.model.settings.ClientSettingsStorage
import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.util.json.JsonFileStore
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * In-memory cache and JSON persistence for per-display, client-local [ClientDisplaySettings].
 *
 * Backed by `client-display-settings.json`. Every mutator persists: changes are written off the render thread,
 * coalesced to one write per [SAVE_DELAY_MS], and [flush] writes any pending change at shutdown.
 */
object ClientSettingsStore : ClientSettingsStorage {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val FILE_NAME = "client-display-settings.json"
    private const val SCHEMA_VERSION = 1
    private val jsonFiles = JsonFileStore()
    private val settingsSerializer = MapSerializer(String.serializer(), ClientDisplaySettings.serializer())
    private val settings = ConcurrentHashMap<UUID, ClientDisplaySettings>()
    private const val SAVE_DELAY_MS = 500L
    private val writer = CoalescingWriter(
        SAVE_DELAY_MS,
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "DreamDisplays-SettingsWriter").apply { isDaemon = true }
        },
    ) { writeNow() }

    override fun load() {
        val loaded: Map<String, ClientDisplaySettings> =
            jsonFiles.readVersioned(jsonFiles.file(FILE_NAME), settingsSerializer, SCHEMA_VERSION, logger) ?: return
        settings.clear()
        loaded.forEach { (key, value) ->
            // Older files may hold a live relay URL override: dropped on load, gone from the file on the next save
            if (!ScreenSharePrivacy.mayPersist(value.urlOverride)) {
                value.urlOverride = null
                value.langOverride = null
            }
            runCatching { settings[UUID.fromString(key)] = value }
                .onFailure { logger.error("Invalid UUID in client display settings: $key.") }
        }
    }

    override fun save() = writer.request()

    /** Writes any change still waiting for its coalesced save. Call on shutdown. */
    fun flush() = writer.flush()

    private fun writeNow() {
        if (!jsonFiles.ensureDir(logger)) return
        val toSave = settings.entries.associate { (k, v) -> k.toString() to v }
        jsonFiles.writeVersioned(jsonFiles.file(FILE_NAME), settingsSerializer, toSave, SCHEMA_VERSION, logger)
    }

    override fun getSettings(
        displayUuid: UUID,
        defaultVolume: Float,
    ): ClientDisplaySettings =
        settings.computeIfAbsent(displayUuid) {
            ClientDisplaySettings(
                volume = defaultVolume.coerceIn(0f, 1f),
                quality = ClientStateManager.config.newDisplayQuality,
            )
        }

    override fun updateSettings(
        displayUuid: UUID,
        volume: Float,
        quality: VideoQuality,
        brightness: Float,
        muted: Boolean,
        paused: Boolean,
    ) {
        val s = getSettings(displayUuid)
        s.volume = volume
        s.quality = quality.serialize()
        s.brightness = brightness
        s.muted = muted
        s.paused = paused
        save()
    }

    override fun setUrlOverride(displayUuid: UUID, url: String?, lang: String?) {
        val s = getSettings(displayUuid)
        // A screen share / live relay URL is never written to client-display-settings.json
        val keep = url?.takeIf(ScreenSharePrivacy::mayPersist)
        s.urlOverride = keep
        s.langOverride = lang.takeIf { keep != null }
        save()
    }

    override fun setAudioTrackLang(displayUuid: UUID, lang: String?) {
        val s = getSettings(displayUuid)
        s.audioTrackLang = lang
        save()
    }

    override fun setSubtitleTrackLang(displayUuid: UUID, lang: String?) {
        val s = getSettings(displayUuid)
        s.subtitleTrackLang = lang
        s.subtitlesEnabled = lang != null
        save()
    }

    override fun setSavedTimeNanos(displayUuid: UUID, nanos: Long) {
        val s = getSettings(displayUuid)
        s.savedTimeNanos = nanos
        save()
    }

    /** Saves the resume position [nanos] together with the video [url] it is a position in. */
    fun setSavedTime(displayUuid: UUID, nanos: Long, url: String?) {
        // A replay shows the same displays as the server it was recorded on: it must not move their resume positions
        if (com.dreamdisplays.platform.client.replay.ReplayHooks.inReplay()) return
        val s = getSettings(displayUuid)
        s.savedTimeNanos = nanos
        s.savedTimeUrl = url?.takeIf { it.isNotEmpty() }
        save()
    }

    override fun setPipOpen(displayUuid: UUID, open: Boolean) {
        val s = getSettings(displayUuid)
        s.pipOpen = open
        save()
    }

    override fun setAcousticsEnabled(displayUuid: UUID, enabled: Boolean) {
        val s = getSettings(displayUuid)
        s.acousticsEnabled = enabled
        save()
    }

    /** Saves the viewer's own 3D audio tier for this display (an `AcousticQuality` name), or null to follow the default. */
    fun setAcoustics(displayUuid: UUID, tier: String?) {
        val s = getSettings(displayUuid)
        if (s.acoustics == tier) return
        s.acoustics = tier
        save()
    }

    /** Records that the pushed values at [revisions] (setting name to revision) were applied to this display. */
    fun markPushedApplied(displayUuid: UUID, revisions: Map<String, Long>) {
        if (revisions.isEmpty()) return
        val s = getSettings(displayUuid)
        // A fresh map: the writer thread may be serializing the old one
        s.pushedRevisions = (s.pushedRevisions + revisions).toMutableMap()
        save()
    }

    override fun remove(displayUuid: UUID): Boolean {
        val removed = settings.remove(displayUuid) != null
        if (removed) save()
        return removed
    }
}
