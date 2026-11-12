package com.dreamdisplays.platform.client.ui

import com.dreamdisplays.platform.client.utils.literalText
import com.dreamdisplays.platform.client.utils.translatableText
//? if >=1.21.11 {
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
//?}
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.core.services.keys.DisplayServices
import com.dreamdisplays.core.services.keys.MediaServices
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.media.audio.AcousticQuality
import com.dreamdisplays.core.services.keys.AudioAcousticsServices
import com.dreamdisplays.api.media.search.MediaSearchResult
import com.dreamdisplays.shared.playback.FullscreenMode
import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.playback.model.PlaybackSpeed
import com.dreamdisplays.core.services.keys.PlaybackServices
import com.dreamdisplays.core.runtime.get
import com.dreamdisplays.media.source.youtube.cache.VideoMetadataCache
import com.dreamdisplays.media.source.youtube.cache.VideoTitleCache
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.input.MouseButtons
import com.dreamdisplays.platform.client.displays.DisplayQueues
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.displays.ScreenShare
import com.dreamdisplays.media.player.process.ScreenCapture
import com.dreamdisplays.media.player.process.ShareAudio
import com.dreamdisplays.api.media.source.YouTubeUrls
import com.dreamdisplays.util.DreamCoroutines
import com.dreamdisplays.core.protocol.common.packets.QueueItemWire
import com.dreamdisplays.media.source.youtube.YouTubePlaylists
import kotlinx.coroutines.launch
import com.dreamdisplays.platform.client.displays.ServerOverrides
import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.platform.client.popout.PopoutManager
import com.dreamdisplays.platform.client.render.ScrubPreview
import com.dreamdisplays.platform.client.storage.CustomVideoStore
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiScreenBase
import com.dreamdisplays.platform.client.ui.kit.UiText
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.kit.claimClick
import com.dreamdisplays.platform.client.ui.kit.claimScroll
import com.dreamdisplays.platform.client.ui.kit.drawOutline
import com.dreamdisplays.platform.client.ui.kit.drawPanel
import com.dreamdisplays.platform.client.ui.menu.*
import com.dreamdisplays.platform.client.ui.widgets.*
import com.dreamdisplays.platform.client.utils.MinecraftScreenUtil
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The display configuration screen: video preview with playback controls, the settings rows, and
 * the suggestions panel.
 */
class DisplayMenu private constructor(
    val displayScreen: DisplayScreen,
) : UiScreenBase(ClientMessage.UI_TITLE.component()) {

    private val modLabel = ModTitleLabel()
    private val popout = DreamServices.registry.get<PopoutManager>()
    private val dropdown = PopoutDropdown(
        onWindow = { popout.openWindow(DisplayId(displayScreen.uuid)) },
        onPip = { popout.openPip(DisplayId(displayScreen.uuid)) },
        onFullscreen = { popout.openFullscreen(DisplayId(displayScreen.uuid), FullscreenMode.STANDARD); onClose() },
        onBorderless = { popout.openFullscreen(DisplayId(displayScreen.uuid), FullscreenMode.IMMERSIVE); onClose() },
    )
    private val audioTrackDropdown = AudioTrackDropdown(
        getTracks = { displayScreen.audioTrackList },
        currentUrl = { displayScreen.currentAudioTrackUrl },
        // Routed through the playback service (client-local, per-viewer) like every other control.
        onSelect = {
            DreamServices.registry.get(PlaybackServices.PLAYBACK)
                .setAudioTrack(DisplayId(displayScreen.uuid), it.url)
        },
    )
    private val shareDropdown = ShareDropdown(::shareTargetRows)
    private lateinit var shareButton: IconButton
    private val subtitleDropdown = SubtitleDropdown(
        getTracks = { displayScreen.subtitleTrackList },
        currentLang = { displayScreen.currentSubtitleLang.takeIf { displayScreen.subtitlesEnabled } },
        onSelect = {
            DreamServices.registry.get(PlaybackServices.PLAYBACK)
                .setSubtitleTrack(DisplayId(displayScreen.uuid), it?.lang)
        },
    )

    private lateinit var volume: ValueSlider
    private lateinit var quality: ValueSlider
    private lateinit var brightness: ValueSlider
    private lateinit var speed: SpeedField
    private lateinit var audio3d: ModeSlider<AcousticQuality>
    private lateinit var access: ModeSlider<DisplayAccess>
    private lateinit var progress: SeekBar
    private lateinit var suggestions: SuggestionsPanel
    private lateinit var preview: PreviewSection
    private lateinit var settings: SettingsSection
    private lateinit var errorPanel: ErrorPanel
    private lateinit var popoutButton: IconButton
    private lateinit var audioTrackButton: IconButton
    private lateinit var subtitleButton: IconButton
    private var lastSuggestedVideoId: String? = null
    private var prevQualityListSize = 0
    private var suggestionsRect: UiRect? = null
    private lateinit var queueSection: QueueSection
    private lateinit var skipButton: IconButton
    private lateinit var clearVideoButton: IconButton
    private lateinit var loopButton: IconButton
    private lateinit var clearQueueButton: IconButton
    private var clearArmedUntilMs = 0L
    private var lastQueueCount = -1
    private var queueFlashUntilMs = 0L
    private var suggestionsTabRect: UiRect? = null
    private var queueTabRect: UiRect? = null
    private var queueTip: String? = null

    override fun onInit() {
        val ds = displayScreen
        // Playback controls drive the display through the core PlaybackService instead of mutating
        // the DisplayScreen directly, so the UI no longer reaches into the live screen for these actions.
        val displayId = DisplayId(ds.uuid)
        val playback = DreamServices.registry.get(PlaybackServices.PLAYBACK)
        val displays = DreamServices.registry.get(DisplayServices.DISPLAY)
        val videoReady = { ds.isVideoStarted && !ds.errored }
        val notErrored = { !ds.errored }

        volume = addUi(
            ValueSlider(
                initial = ds.volume.toDouble(),
                label = { literalText("${(it * 200).roundToInt()}%") },
                // Volume's fraction maps to 0-200%, so a 5%-of-displayed-value stop is 0.025 of the fraction.
                step = 0.025,
            ) { playback.setVolume(displayId, it.toFloat()) })
        // Volume and mute are stored on the display and applied when its player starts, so they work while loading
        volume.enabledWhen = notErrored
        volume.visibleWhen = notErrored

        quality = addUi(
            ValueSlider(
                initial = qualityFraction(ds.quality.serialize()),
                label = {
                    when {
                        // Broadcast pins everyone to the highest quality within the cap; show that, not the saved setting
                        ds.qualityCap > 0 -> literalText("${broadcastQuality()}p")
                        ds.qualityList.isNotEmpty() -> literalText("${qualityFromFraction(it)}p")
                        else -> literalText("${ds.quality.serialize()}p")
                    }
                },
                // One stop per available quality, so the handle can only ever rest exactly on a real option
                step = qualityStep(ds.qualityList.size),
                // Commit on release: applying live would restart the decoder on every stop crossed
                // while dragging, which can drop videoReady() mid-drag and freeze the widget.
                live = false,
            ) {
                if (ds.qualityList.isNotEmpty()) playback.setQuality(
                    displayId,
                    VideoQuality.parse(qualityFromFraction(it))
                )
            })
        quality.enabledWhen = { ds.qualityList.isNotEmpty() && ds.canChangeQualityHere }
        quality.visibleWhen = notErrored

        brightness = addUi(
            ValueSlider(
                initial = ds.brightness.toDouble().coerceIn(0.0, 1.0),
                label = { literalText("${(it * 100).roundToInt()}%") },
                step = 0.05,
            ) { playback.setBrightness(displayId, it.toFloat()) })
        brightness.enabledWhen = { !ds.isSync || ds.canEdit }
        brightness.visibleWhen = notErrored

        speed = addUi(SpeedField(current = { ds.playbackSpeed }) { playback.setSpeed(displayId, it) })
        speed.enabledWhen = { ds.canChangeSpeedHere }
        speed.visibleWhen = notErrored

        audio3d = addUi(
            ModeSlider(
                modes = AUDIO_3D_MODES,
                initial = ds.audioAcousticsTier,
                current = { ds.audioAcousticsTier },
                enabledFor = { true },
                label = { translatableText(audio3dModeLabel(it)) },
            ) { quality -> ds.acousticsChoice = quality }) // This display only
        audio3d.visibleWhen = notErrored
        audio3d.enabledWhen = { !ds.acousticsForced }

        access = addUi(
            ModeSlider(
                modes = accessModesFor(ds),
                initial = ds.access ?: DisplayAccess.DEFAULT,
                current = { ds.access ?: DisplayAccess.DEFAULT },
                enabledFor = { ds.canToggleLockHere },
                label = { translatableText(accessLevelLabel(it)) },
            ) { level ->
                if (level != ds.access) displays.setAccess(displayId, level)
            })
        access.enabledWhen = { ds.canToggleLockHere }
        access.visibleWhen = { ds.access != null && !ds.errored }

        val qualityReset = addUi(IconButton("refresh") {
            playback.setQuality(displayId, VideoQuality.DEFAULT)
            quality.value = qualityFraction(VideoQuality.DEFAULT.serialize())
        })
        qualityReset.enabledWhen = { ds.canChangeQualityHere && ds.quality != VideoQuality.DEFAULT }
        qualityReset.visibleWhen = notErrored

        val brightnessReset = addUi(IconButton("refresh") {
            playback.setBrightness(displayId, 1.0f)
            brightness.value = 1.0
        })
        brightnessReset.enabledWhen = { abs(brightness.value - 1.0) > 0.01 }
        brightnessReset.visibleWhen = notErrored

        val speedReset = addUi(IconButton("refresh") { playback.setSpeed(displayId, PlaybackSpeed.NORMAL) })
        speedReset.enabledWhen = { ds.canChangeSpeedHere && ds.playbackSpeed != PlaybackSpeed.NORMAL }
        speedReset.visibleWhen = notErrored

        // Back to following the default tier
        val audio3dReset = addUi(IconButton("refresh") { ds.acousticsChoice = null })
        audio3dReset.enabledWhen = { !ds.acousticsForced && ds.acousticsChoice != null }
        audio3dReset.visibleWhen = notErrored

        val accessReset = addUi(IconButton("refresh") {
            if (ds.canToggleLockHere) displays.setAccess(displayId, ds.defaultAccess)
        })
        accessReset.enabledWhen = { ds.canToggleLockHere && ds.access != ds.defaultAccess }
        accessReset.visibleWhen = { ds.access != null && !ds.errored }

        val muteButton = addUi(
            IconButton(
                icon = { IconButton.modIcon(if (ds.muted) "mute" else "sound") },
            ) { playback.mute(displayId, !ds.muted) })
        muteButton.enabledWhen = notErrored
        muteButton.visibleWhen = notErrored

        popoutButton = addUi(IconButton("popout") {
            if (ds.isPopoutActive) {
                popout.close(displayId)
                dropdown.hide()
            } else {
                dropdown.toggle()
            }
        })
        popoutButton.enabledWhen = { videoReady() && (ds.canPopoutHere || ds.isPopoutActive) }
        popoutButton.visibleWhen = notErrored

        audioTrackButton = addUi(IconButton("lang") { audioTrackDropdown.toggle() })
        audioTrackButton.enabledWhen = { videoReady() && ds.audioTrackList.size > 1 }
        audioTrackButton.visibleWhen = notErrored

        subtitleButton = addUi(IconButton("cc") { subtitleDropdown.toggle() })
        subtitleButton.enabledWhen = { videoReady() && ds.subtitleTrackList.isNotEmpty() }
        subtitleButton.visibleWhen = notErrored

        val pauseButton = addUi(
            IconButton(
                icon = { IconButton.modIcon(if (ds.isPaused) "play" else "pause") },
            ) { if (ds.isPaused) playback.play(displayId) else playback.pause(displayId) })
        pauseButton.enabledWhen = { ds.canControlPlayback }
        pauseButton.visibleWhen = notErrored

        progress = addUi(
            SeekBar(
                current = { ds.seekBaseNanos },
                duration = { ds.mediaPlayerDurationNanos },
                previewFrame = { nanos ->
                    if (ds.isLive) null else {
                        val key = ds.videoUrl
                        val rawUrl = ds.scrubPreviewRawUrl
                        val dur = ds.mediaPlayerDurationNanos
                        if (key != null && rawUrl != null) {
                            ScrubPreview.request(key, rawUrl, dur, ds.scrubPreviewSeeksByDecoding)
                        }
                        key?.let { ScrubPreview.frameAt(it, nanos) }
                    }
                },
                waitingLabel = {
                    when {
                        ds.isWaitingForStream -> ClientMessage.UI_WAITING_STREAM.component().string
                        !ds.isVideoStarted -> ClientMessage.UI_WAITING.component().string
                        else -> null
                    }
                },
                liveLabel = { if (ds.isLive) ClientMessage.UI_LIVE.component().string else null },
                scheduleLabel = { scheduleCountdownText() },
                chapters = { DisplayChapters.of(ds) },
            ) { nanos ->
                if (ds.canSeek() && !ds.isLive && ds.canSeekHere) {
                    playback.seek(displayId, nanos / 1_000_000L)
                }
            })
        progress.enabledWhen = { videoReady() && ds.canSeek() && !ds.isLive && ds.canSeekHere }
        progress.visibleWhen = notErrored

        val retryButton = addUi(IconButton("refresh") {
            playback.retry(displayId) // Local re-resolve; the error panel clears itself once it succeeds
        })
        // Only the error panel places it; keep it hidden in the normal menu so it never strays to (0,0)
        retryButton.visibleWhen = { ds.errored }

        val deleteButton = addUi(
            IconButton(
                icon = { IconButton.modIcon("delete") },
                sprites = IconButton.RED_SPRITES,
            ) {
                displays.delete(displayId)
                onClose()
            })
        deleteButton.enabledWhen = { ds.owner || ds.isAdmin }

        val reportButton = if (ClientStateManager.isReportingEnabled) {
            addUi(
                IconButton(
                    icon = { IconButton.modIcon("report") },
                    sprites = IconButton.RED_SPRITES,
                ) {
                    displays.report(displayId)
                    onClose()
                })
        } else null

        skipButton = addUi(IconButton("skip") { DisplayQueues.skip(ds.uuid) })
        skipButton.visibleWhen = { notErrored() && ds.queueRunsHere && DisplayQueues.items(ds.uuid).isNotEmpty() }
        skipButton.enabledWhen = { ds.canSkipQueueHere }

        clearVideoButton = addUi(IconButton(icon = { IconButton.modIcon("cross") }, sprites = IconButton.RED_SPRITES) {
            ds.requestClearVideo()
        })
        // Also on the error panel and while loading: a video that fails or never starts is when clearing helps most
        clearVideoButton.visibleWhen = { ds.canClearVideoHere }

        suggestions = addUi(SuggestionsPanel(::onPickSuggested, ds.suggestionsController))
        suggestions.visibleWhen = { !ds.errored && suggestionsRect != null }
        // Locked / Broadcast displays only let the owner / admin change the video, so
        // the panel shows an "unavailable" notice to everyone else instead of pickable suggestions.
        suggestions.available = { ds.canSetVideoHere }
        // A press on a card's "+" queues it; the card under it must never also play it
        suggestions.cardPressBlocked = { mx, my -> suggestionQueueButtonRect()?.contains(mx, my) == true }

        // "Up next": the bottom panel's second tab and the loop toggle in the tab header
        queueSection = QueueSection(ds)
        loopButton = addUi(IconButton("loop") {
            DisplayQueues.setLoop(ds.uuid, DisplayQueues.of(ds.uuid)?.loop != true)
        })
        loopButton.visibleWhen = { showingQueue() && suggestionsRect != null && !ds.errored }
        loopButton.enabledWhen = { ds.canManageQueueHere }
        // Clearing takes a second click within a few seconds, so a stray click can't wipe everyone's list
        clearQueueButton = addUi(IconButton(icon = { IconButton.modIcon("delete") }, sprites = IconButton.RED_SPRITES) {
            val now = System.currentTimeMillis()
            if (now < clearArmedUntilMs) {
                clearArmedUntilMs = 0L
                DisplayQueues.clear(ds.uuid)
            } else {
                clearArmedUntilMs = now + CLEAR_CONFIRM_MS
            }
        })
        clearQueueButton.visibleWhen = { loopButton.visibleWhen() && DisplayQueues.items(ds.uuid).isNotEmpty() }
        clearQueueButton.enabledWhen = { ds.canManageQueueHere }

        // init() runs again on every resize: release the previous section's ambient texture and uploader first
        if (::preview.isInitialized) preview.close()
        preview =
            PreviewSection(
                ds, muteButton, volume, popoutButton, audioTrackButton, subtitleButton, pauseButton, progress,
                dropdown, audioTrackDropdown, subtitleDropdown,
                skipButton = skipButton,
                clearVideoButton = clearVideoButton,
                upNext = { if (ds.queueRunsHere) DisplayQueues.next(ds.uuid)?.let(QueueSection::titleOf) else null },
            )
        // Share screen: picks a monitor (dropdown) when idle, stops at once while this display is the one shared to
        shareButton = addUi(IconButton("share") {
            if (ScreenShare.isSharing(ds.uuid)) {
                ScreenShare.stop()
                shareDropdown.hide()
            } else {
                shareDropdown.toggle()
            }
        })
        // Stop is always there while this display is shared to, whatever else hides the button (an error, the server
        // dropping the feature): the capture runs until it is pressed
        shareButton.visibleWhen = { ScreenShare.isSharing(ds.uuid) || (ScreenShare.available && !ds.errored) }
        shareButton.enabledWhen = { ScreenShare.isSharing(ds.uuid) || ds.canSetVideoHere }
        settings = SettingsSection(
            rows = settingsRows(qualityReset, brightnessReset, speedReset, audio3dReset, accessReset),
            ownerActions = [reportButton, deleteButton, shareButton],
            buttonTooltips = [
                deleteButton to { buttonTooltip(ClientMessage.BUTTON_DELETE.key) },
                reportButton to { buttonTooltip(ClientMessage.BUTTON_REPORT.key) },
                shareButton to {
                    buttonTooltip(
                        if (ScreenShare.isSharing(ds.uuid)) ClientMessage.SHARE_STOP.key else ClientMessage.SHARE_BUTTON.key,
                    )
                },
            ],
        )
        errorPanel = ErrorPanel(retryButton, deleteButton, reportButton, clearVideoButton, shareButton) { ds.mediaError }
    }

    private fun settingsRows(
        qualityReset: IconButton,
        brightnessReset: IconButton, speedReset: IconButton, audio3dReset: IconButton,
        accessReset: IconButton,
    ): List<SettingsSection.Row> {
        val ds = displayScreen
        return [
            SettingsSection.Row(ClientMessage.BUTTON_QUALITY.key, quality, qualityReset) {
                val tip = mutableListOf(
                    tooltipTitle(ClientMessage.BUTTON_QUALITY_TOOLTIP_1.key),
                    tooltipBody(ClientMessage.BUTTON_QUALITY_TOOLTIP_2.key),
                    literalText(""),
                    tooltipValue(ClientMessage.BUTTON_QUALITY_TOOLTIP_4.key, qualityFromFraction(quality.value)),
                )
                if ((ds.quality.targetHeight ?: 0) > 1080) {
                    tip.add(
                        ClientMessage.BUTTON_QUALITY_TOOLTIP_5.component()
                            .withStyle { it.withColor(ChatFormatting.YELLOW) },
                    )
                }
                tip
            },
            SettingsSection.Row(ClientMessage.BUTTON_BRIGHTNESS.key, brightness, brightnessReset) {
                [
                    tooltipTitle(ClientMessage.BUTTON_BRIGHTNESS_TOOLTIP_1.key),
                    tooltipBody(ClientMessage.BUTTON_BRIGHTNESS_TOOLTIP_2.key),
                    literalText(""),
                    tooltipValue(ClientMessage.BUTTON_BRIGHTNESS_TOOLTIP_3.key, floor(brightness.value * 100).toInt()),
                ]
            },
            SettingsSection.Row(ClientMessage.BUTTON_SPEED.key, speed, speedReset) {
                [
                    tooltipTitle(ClientMessage.BUTTON_SPEED_TOOLTIP_1.key),
                    tooltipBody(ClientMessage.BUTTON_SPEED_TOOLTIP_2.key),
                    tooltipBody(ClientMessage.BUTTON_SPEED_TOOLTIP_3.key),
                    literalText(""),
                    tooltipValue(ClientMessage.BUTTON_SPEED_TOOLTIP_4.key, PlaybackSpeed.format(ds.playbackSpeed)),
                ]
            },
            SettingsSection.Row(ClientMessage.BUTTON_AUDIO3D.key, audio3d, audio3dReset) {
                [
                    tooltipTitle(ClientMessage.BUTTON_AUDIO3D_TOOLTIP_1.key),
                    tooltipBody(ClientMessage.BUTTON_AUDIO3D_TOOLTIP_2.key),
                    literalText(""),
                    tooltipModeBullet(ClientMessage.MODE_AUDIO_OFF.key, ClientMessage.BUTTON_AUDIO3D_TOOLTIP_3.key),
                    tooltipModeBullet(ClientMessage.MODE_AUDIO_ENHANCED.key, ClientMessage.BUTTON_AUDIO3D_TOOLTIP_4.key),
                    tooltipModeBullet(ClientMessage.MODE_AUDIO_ADVANCED.key, ClientMessage.BUTTON_AUDIO3D_TOOLTIP_5.key),
                    literalText(""),
                    tooltipValue(
                        // "(default)" while this display follows the default tier
                        if (ds.acousticsChoice == null && !ds.acousticsForced) ClientMessage.BUTTON_AUDIO3D_TOOLTIP_7.key
                        else ClientMessage.BUTTON_AUDIO3D_TOOLTIP_6.key,
                        translatableText(audio3dModeLabel(audio3d.mode)),
                    ),
                ]
            },
            SettingsSection.Row(ClientMessage.BUTTON_ACCESS.key, access, accessReset) {
                buildList {
                    add(tooltipTitle(ClientMessage.BUTTON_ACCESS_TOOLTIP_1.key))
                    add(tooltipBody(ClientMessage.BUTTON_ACCESS_TOOLTIP_2.key))
                    add(literalText(""))
                    add(tooltipModeBullet(ClientMessage.ACCESS_EVERYONE.key, ClientMessage.BUTTON_ACCESS_TOOLTIP_3.key))
                    if (ds.supportsRegionAccess) {
                        add(tooltipModeBullet(ClientMessage.ACCESS_REGION.key, ClientMessage.BUTTON_ACCESS_TOOLTIP_4.key))
                    }
                    add(tooltipModeBullet(ClientMessage.ACCESS_LOCKED.key, ClientMessage.BUTTON_ACCESS_TOOLTIP_5.key))
                    if (ds.supportsRegionAccess && !ds.inRegion) {
                        add(literalText(""))
                        add(
                            ClientMessage.BUTTON_ACCESS_TOOLTIP_NO_REGION.component()
                                .withStyle { it.withColor(ChatFormatting.YELLOW) },
                        )
                    }
                    add(literalText(""))
                    add(
                        tooltipValue(
                            ClientMessage.BUTTON_ACCESS_TOOLTIP_6.key,
                            translatableText(accessLevelLabel(access.mode)),
                        ),
                    )
                }
            },
        ]
    }

    private fun scheduleCountdownText(): String? {
        val at = displayScreen.scheduledStartEpochMillis.takeIf { it > 0 } ?: return null
        val remainingMs = at - System.currentTimeMillis()
        if (remainingMs <= 0) return null
        val key = if (displayScreen.scheduledAction == PlaybackAction.PAUSE.wire) {
            ClientMessage.UI_SCHEDULE_PAUSE.key
        } else {
            ClientMessage.UI_SCHEDULE_PLAY.key
        }
        return translatableText(key, UiText.formatTime(remainingMs * 1_000_000L)).string
    }

    private fun tooltipTitle(key: String): Component =
        translatableText(key).withStyle { it.withColor(ChatFormatting.WHITE).withBold(true) }

    private fun tooltipBody(key: String): Component =
        translatableText(key).withStyle { it.withColor(ChatFormatting.GRAY) }

    private fun tooltipValue(key: String, arg: Any): Component =
        translatableText(key, arg).withStyle { it.withColor(ChatFormatting.GOLD) }

    private fun tooltipModeBullet(modeKey: String, descKey: String): Component =
        literalText("• ").withStyle { it.withColor(ChatFormatting.GRAY) }
            .append(translatableText(modeKey).withStyle { it.withColor(ChatFormatting.GRAY) })
            .append(literalText(": ").withStyle { it.withColor(ChatFormatting.GRAY) })
            .append(translatableText(descKey).withStyle { it.withColor(ChatFormatting.GRAY) })

    private fun buttonTooltip(prefix: String): List<Component> = [
        tooltipTitle("$prefix.tooltip.1"),
        tooltipBody("$prefix.tooltip.2"),
    ]

    private fun onPickSuggested(info: MediaSearchResult) {
        val ds = displayScreen
        if (!ds.canSetVideoHere) return
        // A playlist card queues its playlist; there is no single video to play
        if (info.isPlaylist) return queuePlaylistCard(info)
        DreamServices.registry.get(DisplayServices.DISPLAY).setUrl(DisplayId(ds.uuid), info.getWatchUrl(), ds.lang)

        // A pasted link exists nowhere else, so remember it locally the moment it is used
        if (info.isCustom) {
            CustomVideoStore.remember(info.getWatchUrl(), info.title)
            return
        }

        // Related videos, the title cache, and the metadata cache are all keyed by a YouTube video
        // id. A Twitch / Vimeo / Kick card has none — its id is a URL or a platform key — so feeding
        // those here would fire a bogus YouTube "related" lookup. Only real YouTube picks continue.
        val videoId = DreamServices.registry.getOrNull(MediaServices.SEARCH)?.extractVideoId(info.getWatchUrl())
            ?: return
        VideoTitleCache.put(videoId, info.title)
        VideoMetadataCache.put(videoId, info)
        lastSuggestedVideoId = videoId
        suggestions.setRelatedTo(videoId)
    }

    override fun drawScreen(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawScreenBackground(g)
        val ds = displayScreen

        modLabel.draw(g, UiTheme.SCREEN_PADDING, 6)
        resyncQualitySlider()
        resyncModeSlider()
        audio3d.syncToCurrent()

        if (ds.errored) {
            // An open popup whose anchor is hidden would still claim (and act on) the next click
            dropdown.hide()
            shareDropdown.hide()
            audioTrackDropdown.hide()
            subtitleDropdown.hide()
            suggestions.hidePopups()
            suggestionsRect = null
            errorPanel.render(g, width, height)
            drawChildren(g, mouseX, mouseY, partialTick)
            clearVideoTip(mouseX, mouseY)?.let { renderQueueTooltip(g, it, toRealX(mouseX), toRealY(mouseY)) }
            return
        }

        val layout = MenuLayout.compute(width, height, font.lineHeight)
        suggestionsRect = layout.suggestions

        g.drawPanel(font, layout.preview, ClientMessage.UI_PREVIEW.component().string)
        g.drawPanel(font, layout.settings, ClientMessage.UI_SETTINGS.component().string)
        preview.render(g, layout.preview, mouseX, mouseY)
        settings.render(g, layout.settings)

        val suggestionsArea = layout.suggestions
        val tabs = DisplayQueues.supported
        if (!tabs) queueTabOpen = false
        queueTip = null
        if (suggestionsArea != null) {
            suggestions.visible = !showingQueue()
            suggestions.showTitle = !tabs
            suggestions.setVertical(layout.suggestionsVertical)
            suggestions.setCompactCards(false)
            suggestions.place(suggestionsArea)
            loopButton.place(UiRect(suggestionsArea.right - 10 - LOOP_BUTTON, suggestionsArea.y + 5, LOOP_BUTTON, LOOP_BUTTON))
            clearQueueButton.place(
                UiRect(suggestionsArea.right - 10 - LOOP_BUTTON * 2 - 4, suggestionsArea.y + 5, LOOP_BUTTON, LOOP_BUTTON),
            )
            if (showingQueue()) {
                queueSection.vertical = layout.suggestionsVertical
                queueSection.render(g, suggestionsArea, mouseX, mouseY)
                queueTip = queueSection.tooltip
            }
        } else {
            suggestions.visible = false
        }
        if (!suggestions.visible) suggestions.hidePopups()
        refreshRelatedVideos()

        drawChildren(g, mouseX, mouseY, partialTick)
        if (shareButton.visible) {
            if (ScreenShare.isSharing(ds.uuid)) {
                val bottom = shareButton.y + shareButton.height
                g.fill(shareButton.x + 2, bottom + 1, shareButton.x + shareButton.width - 2, bottom + 3, 0xFFFF5050.toInt())
            }
            shareDropdown.draw(g, shareButton.x + shareButton.width / 2, shareButton.y, mouseX, mouseY, width)
        } else {
            shareDropdown.hide()
        }
        if (suggestionsArea != null && tabs) {
            drawPanelTabs(g, suggestionsArea, mouseX, mouseY)
            drawSuggestionQueueButton(g, mouseX, mouseY)
            if (loopButton.visible && DisplayQueues.of(ds.uuid)?.loop == true) {
                val bottom = loopButton.y + loopButton.height
                g.fill(loopButton.x + 2, bottom + 1, loopButton.x + loopButton.width - 2, bottom + 3, UiTheme.ACCENT)
            }
            if (clearQueueButton.visible && System.currentTimeMillis() < clearArmedUntilMs) {
                g.drawOutline(
                    UiRect(clearQueueButton.x - 1, clearQueueButton.y - 1, clearQueueButton.width + 2, clearQueueButton.height + 2),
                    UiTheme.ACCENT_UPDATE,
                )
            }
        }
        buttonTip(mouseX, mouseY)
        //? if <1.21.6 {
        suggestions.redrawSortDropdownOnTop(g, mouseX, mouseY)
        //?}
        settings.renderTooltips(g, mouseX, mouseY, toRealX(mouseX), toRealY(mouseY))
        queueTip?.let { renderQueueTooltip(g, it, toRealX(mouseX), toRealY(mouseY)) }
    }

    private fun showingQueue(): Boolean = queueTabOpen && DisplayQueues.supported

    /** "Suggestions | Up next (N)" in the bottom panel's title line. */
    private fun drawPanelTabs(g: GuiGraphicsCompat, area: UiRect, mouseX: Int, mouseY: Int) {
        val ds = displayScreen
        val x = area.x + UiTheme.PANEL_PADDING_X
        val y = area.y + UiTheme.PANEL_PADDING_Y
        val first = ClientMessage.BUTTON_SUGGESTIONS.text()
        val count = DisplayQueues.items(ds.uuid).size
        val now = System.currentTimeMillis()
        if (lastQueueCount >= 0 && count > lastQueueCount) queueFlashUntilMs = now + QUEUE_FLASH_MS
        lastQueueCount = count
        val second = ClientMessage.QUEUE_TAB.text(count)
        val firstRect = UiRect(x - 2, y - 2, font.width(first) + 4, font.lineHeight + 4)
        val secondRect = UiRect(firstRect.right + TAB_GAP, y - 2, font.width(second) + 4, font.lineHeight + 4)
        suggestionsTabRect = firstRect
        queueTabRect = secondRect
        drawTab(g, first, firstRect, active = !showingQueue(), hover = firstRect.contains(mouseX, mouseY))
        drawTab(g, second, secondRect, active = showingQueue(), hover = secondRect.contains(mouseX, mouseY))
        // Just queued something: the count lights up so the add is visibly confirmed
        if (now < queueFlashUntilMs) g.drawText(font, second, secondRect.x + 2, secondRect.y + 2, UiTheme.ACCENT, false)
    }

    private fun drawTab(g: GuiGraphicsCompat, label: String, r: UiRect, active: Boolean, hover: Boolean) {
        val color = when {
            active -> UiTheme.TEXT_PRIMARY
            hover -> UiTheme.TEXT_DIM
            else -> UiTheme.TEXT_SECONDARY
        }
        g.drawText(font, label, r.x + 2, r.y + 2, color, false)
        if (active) g.fill(r.x + 2, r.bottom, r.right - 2, r.bottom + 1, UiTheme.ACCENT)
    }

    /** The small "+" (add to Up next) in the corner of the hovered suggestion card. */
    private fun drawSuggestionQueueButton(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int) {
        val r = suggestionQueueButtonRect() ?: return
        val hover = r.contains(mouseX, mouseY)
        QueueSection.drawMiniButton(g, r, "plus", hover)
        if (hover) {
            queueTip = queueBlockedReason()
                ?: if (suggestions.hoveredResult?.isPlaylist == true) ClientMessage.QUEUE_PLAYLIST_ADD.text()
                else ClientMessage.QUEUE_ADD.text()
        }
    }

    private fun suggestionQueueButtonRect(): UiRect? {
        val ds = displayScreen
        if (!suggestions.visible || !DisplayQueues.supported || !ds.canAddToQueueHere) return null
        suggestions.hoveredResult ?: return null
        val card = suggestions.hoveredCardRect ?: return null
        val r = UiRect(card.right - 2 - QUEUE_PLUS, card.y + 2, QUEUE_PLUS, QUEUE_PLUS)
        val strip = suggestions.stripRect()
        if (r.x < strip.x || r.right > strip.right || r.y < strip.y || r.bottom > strip.bottom) return null
        return r
    }

    /** Why the local player can't queue right now (full / own limit), or null. */
    private fun queueBlockedReason(): String? {
        val ds = displayScreen
        val state = DisplayQueues.of(ds.uuid) ?: return null
        if (state.maxItems > 0 && state.items.size >= state.maxItems) return ClientMessage.QUEUE_FULL.text()
        val exempt = ds.owner || ds.isAdmin
        if (!exempt && state.maxPerPlayer > 0 && DisplayQueues.ownCount(ds.uuid) >= state.maxPerPlayer) {
            return ClientMessage.QUEUE_PLAYER_LIMIT.text(state.maxPerPlayer)
        }
        return null
    }

    /** The Clear button's tooltip when the pointer is on it, else null. */
    private fun clearVideoTip(mouseX: Int, mouseY: Int): String? {
        val b = clearVideoButton
        if (!b.visible || mouseX < b.x || mouseX >= b.x + b.width || mouseY < b.y || mouseY >= b.y + b.height) return null
        return ClientMessage.UI_CLEAR_VIDEO.text()
    }

    private fun buttonTip(mouseX: Int, mouseY: Int) {
        fun over(b: IconButton) =
            b.visible && mouseX >= b.x && mouseX < b.x + b.width && mouseY >= b.y && mouseY < b.y + b.height
        when {
            over(skipButton) -> queueTip = ClientMessage.QUEUE_SKIP.text()
            over(clearVideoButton) -> queueTip = clearVideoTip(mouseX, mouseY)
            over(clearQueueButton) -> queueTip = if (System.currentTimeMillis() < clearArmedUntilMs) {
                ClientMessage.QUEUE_CLEAR_CONFIRM.text()
            } else {
                ClientMessage.QUEUE_CLEAR.text()
            }
            over(loopButton) -> queueTip = if (DisplayQueues.of(displayScreen.uuid)?.loop == true) {
                ClientMessage.QUEUE_LOOP_ON.text()
            } else {
                ClientMessage.QUEUE_LOOP_OFF.text()
            }
        }
    }

    private fun renderQueueTooltip(g: GuiGraphicsCompat, text: String, x: Int, y: Int) {
        val lines = [literalText(text)]
        //? if >=1.21.6 {
        g.setComponentTooltipForNextFrame(font, lines, x, y)
        //?} else
        /*g.renderComponentTooltip(font, lines, x, y)*/
    }

    /** Queue clicks that must win over the panels underneath: tabs, card buttons, the card "+". */
    private fun handleQueueClick(mx: Int, my: Int): Boolean {
        if (!DisplayQueues.supported || suggestionsRect == null || displayScreen.errored) return false
        if (suggestionsTabRect?.contains(mx, my) == true && showingQueue()) {
            queueTabOpen = false
            playClick()
            return true
        }
        if (queueTabRect?.contains(mx, my) == true && !showingQueue()) {
            queueTabOpen = true
            playClick()
            return true
        }
        if (showingQueue()) {
            if (queueSection.handleClick(mx, my)) return true
            // The hidden suggestions panel still sits under the queue: keep its cards from catching the click, but let
            // the tab header's own buttons (loop, clear) have it
            fun over(b: IconButton) =
                b.visible && mx >= b.x && mx < b.x + b.width && my >= b.y && my < b.y + b.height
            if (over(loopButton) || over(clearQueueButton)) return false
            return suggestionsRect?.contains(mx, my) == true
        }
        val plus = suggestionQueueButtonRect()
        if (plus != null && plus.contains(mx, my)) {
            val result = suggestions.hoveredResult ?: return false
            playClick()
            val blocked = queueBlockedReason()
            if (blocked != null) DisplayQueues.notifyLocal(literalText(blocked).withStyle(ChatFormatting.GRAY))
            else if (result.isPlaylist) queuePlaylistCard(result)
            else DisplayQueues.add(displayScreen.uuid, result, displayScreen.lang ?: "")
            return true
        }
        return false
    }

    /**
     * A playlist card's click or "+": queues its playlist (all of it, or for a `watch?v=X&list=Y` link X and everything
     * after it) when Up next runs here and has room.
     */
    private fun queuePlaylistCard(card: MediaSearchResult) {
        val ds = displayScreen
        val link = PlaylistCards.linkOf(card) ?: return
        if (!ds.canAddToQueueHere) {
            return DisplayQueues.notifyLocal(ClientMessage.QUEUE_NOT_RUNNING.component().withStyle(ChatFormatting.GRAY))
        }
        queueBlockedReason()?.let { return DisplayQueues.notifyLocal(literalText(it).withStyle(ChatFormatting.GRAY)) }
        queuePlaylist(link, ds.lang ?: "")
    }

    /** Lists [link]'s playlist off-thread and queues as many of its videos as fit, in order, in one batch. */
    private fun queuePlaylist(link: YouTubePlaylists.Link, lang: String) {
        val ds = displayScreen
        val room = DisplayQueues.room(ds.uuid, exempt = ds.owner || ds.isAdmin, atMost = MAX_PLAYLIST_BATCH)
        if (room <= 0) return DisplayQueues.notifyLocal(ClientMessage.QUEUE_FULL.component())
        // A second "+" while the listing loads (it takes seconds) would queue the whole playlist twice
        val loadingKey = "${ds.uuid}/${link.listId}"
        if (!playlistsLoading.add(loadingKey)) return
        DisplayQueues.notifyLocal(ClientMessage.QUEUE_PLAYLIST_LOADING.component().withStyle(ChatFormatting.GRAY))
        DreamCoroutines.clientIo.launch {
            val listing = runCatching { YouTubePlaylists.fetch(link, room) }
                .onFailure { org.slf4j.LoggerFactory.getLogger(DisplayMenu::class.java).warn("Playlist {}: {}", link.listId, it.message) }
                .getOrNull()
            Minecraft.getInstance().execute {
                playlistsLoading.remove(loadingKey)
                if (listing == null || listing.entries.isEmpty()) {
                    DisplayQueues.notifyLocal(ClientMessage.QUEUE_PLAYLIST_FAILED.component().withStyle(ChatFormatting.RED))
                    return@execute
                }
                DisplayQueues.addBatch(ds.uuid, listing.entries.map { entry ->
                    QueueItemWire(
                        url = YouTubeUrls.watchUrl(entry.videoId), lang = lang, title = entry.title.take(100),
                        durationMs = entry.durationSec * 1_000L,
                    )
                })
                listing.entries.forEach { VideoTitleCache.put(it.videoId, it.title) }
                val title = listing.title.ifBlank { link.listId }
                DisplayQueues.notifyLocal(ClientMessage.QUEUE_PLAYLIST_ADDED.component(listing.entries.size, title))
                if (listing.more) DisplayQueues.notifyLocal(ClientMessage.QUEUE_PLAYLIST_MORE.component().withStyle(ChatFormatting.GRAY))
                if (listing.unavailable > 0) {
                    DisplayQueues.notifyLocal(
                        ClientMessage.QUEUE_PLAYLIST_UNAVAILABLE.component(listing.unavailable).withStyle(ChatFormatting.GRAY),
                    )
                }
            }
        }
    }

    private fun playClick() {
        Minecraft.getInstance().soundManager.play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                com.dreamdisplays.platform.client.utils.buttonClickSound(), 1.0f,
            ),
        )
    }

    private fun resyncQualitySlider() {
        val ds = displayScreen
        val qualityList = ds.qualityList
        if (qualityList.size != prevQualityListSize) {
            prevQualityListSize = qualityList.size
            quality.step = qualityStep(qualityList.size)
            if (qualityList.isNotEmpty()) {
                // In Broadcast the handle should sit on the capped quality, not the user's saved value.
                quality.value = qualityFraction(
                    if (ds.qualityCap > 0) broadcastQuality().toString() else ds.quality.serialize()
                )
            }
        }
    }

    private fun resyncModeSlider() {
        access.syncToCurrent()
    }

    private fun refreshRelatedVideos() {
        val ds = displayScreen
        val currentId = DreamServices.registry.getOrNull(MediaServices.SEARCH)?.extractVideoId(ds.videoUrl ?: "")
        if (currentId != null && currentId != lastSuggestedVideoId) {
            // While the menu is open a video change is someone else's (own picks set lastSuggestedVideoId first):
            // keep the search being typed / browsed. On opening, the search box is empty, so show related.
            val opening = lastSuggestedVideoId == null
            lastSuggestedVideoId = currentId
            if (opening) suggestions.setRelatedTo(currentId) else suggestions.followVideo(currentId)
        }
    }

    //? if >=1.21.11 {
    override fun onKeyPressed(event: KeyEvent): Boolean = handleHotkey(event.key(), event.modifiers())
    //?} else
    /*override fun onKeyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean = handleHotkey(keyCode, modifiers)*/

    private fun handleHotkey(key: Int, modifiers: Int): Boolean {
        if (modifiers and (MOD_CONTROL or MOD_ALT or MOD_SUPER) != 0) return false
        val coarse = modifiers and MOD_SHIFT != 0
        val seekStep = if (coarse) SEEK_STEP_COARSE_NANOS else SEEK_STEP_NANOS
        val volumeStep = if (coarse) VOLUME_STEP_COARSE else VOLUME_STEP
        if (::suggestions.isInitialized && suggestions.isTyping) return false
        if (::speed.isInitialized && speed.isTyping) return false
        val ds = displayScreen
        if (ds.errored) return false
        val displayId = DisplayId(ds.uuid)
        val playback = DreamServices.registry.get(PlaybackServices.PLAYBACK)
        val videoReady = ds.isVideoStarted
        val canSeek = videoReady && ds.canSeek() && !ds.isLive && ds.canSeekHere
        fun seekTo(nanos: Long) {
            val target = nanos.coerceIn(0L, ds.mediaPlayerDurationNanos.coerceAtLeast(0L))
            playback.seek(displayId, target / 1_000_000L)
        }
        fun nudgeVolume(delta: Double) {
            val next = (((volume.value + delta) / VOLUME_STEP).roundToInt() * VOLUME_STEP).coerceIn(0.0, 1.0)
            volume.value = next
            playback.setVolume(displayId, next.toFloat())
        }
        when (key) {
            KEY_SPACE -> {
                if (!ds.canControlPlayback) return false
                if (ds.isPaused) playback.play(displayId) else playback.pause(displayId)
            }
            KEY_LEFT -> if (canSeek) seekTo(ds.seekBaseNanos - seekStep) else return false
            KEY_RIGHT -> if (canSeek) seekTo(ds.seekBaseNanos + seekStep) else return false
            KEY_UP -> nudgeVolume(volumeStep)
            KEY_DOWN -> nudgeVolume(-volumeStep)
            KEY_M -> playback.mute(displayId, !ds.muted)
            in DIGIT_KEYS, in NUMPAD_DIGIT_KEYS -> {
                if (!canSeek) return false
                val digit = DIGIT_KEYS.indexOf(key).takeIf { it >= 0 } ?: NUMPAD_DIGIT_KEYS.indexOf(key)
                seekTo(ds.mediaPlayerDurationNanos / 10 * digit)
            }
            KEY_F -> {
                if (!videoReady || !ds.canPopoutHere) return false
                popout.openFullscreen(displayId, FullscreenMode.STANDARD)
                onClose()
            }
            KEY_P -> {
                if (!videoReady) return false
                if (ds.isPopoutActive) popout.close(displayId)
                else if (ds.canPopoutHere) popout.openPip(displayId)
                else return false
                dropdown.hide()
            }
            else -> return false
        }
        return true
    }

    /** Share picker, first page: screens, then (Windows) windows; on macOS a note that no sound goes along. */
    private fun shareTargetRows(): List<ShareRow> = buildList {
        val os = ScreenShare.audioOs
        add(ShareRow.Header(ClientMessage.SHARE_SCREENS.text()))
        ScreenShare.monitors().forEach { m -> add(ShareRow.Item(ScreenCapture.label(m)) { shareAudioRows(m) }) }
        val windows = ScreenShare.windows()
        if (windows.isNotEmpty()) {
            add(ShareRow.Header(ClientMessage.SHARE_WINDOWS.text()))
            windows.forEach { w -> add(ShareRow.Item(ScreenCapture.label(w)) { shareAudioRows(w) }) }
        }
        if (os == ShareAudio.Os.MAC) add(ShareRow.Note(ClientMessage.SHARE_AUDIO_MAC.text()))
        else if (ScreenShare.soundOffByServer) add(ShareRow.Note(ClientMessage.SHARE_AUDIO_SERVER_OFF.text()))
    }

    /**
     * Share picker, second page: the sound for a screen [target], its default marked (everything but the game, or no
     * sound where only the whole PC can be captured). A window takes its own app's sound without a step, and macOS
     * has no sound to pick, so a pick there starts at once.
     */
    private fun shareAudioRows(target: ScreenCapture.Target): List<ShareRow>? {
        val os = ScreenShare.audioOs
        val start = { choice: ShareAudio.Choice -> ScreenShare.start(displayScreen.uuid, target, choice); null }
        // Nothing to pick: macOS has no share sound, and a server may share screens without it
        if (os == ShareAudio.Os.MAC || ScreenShare.soundOffByServer) return start(ShareAudio.Choice.Off)
        if (target is ScreenCapture.Window) {
            val sound = ScreenShare.windowSound(target)
            ScreenShare.start(displayScreen.uuid, target, sound.choice, sound.silence)
            return null
        }
        val perApp = ScreenShare.processLoopback
        val default = ShareAudio.defaultChoice(os, perApp)
        val options = ShareAudio.options(os, perApp)
        if (options.isEmpty()) return start(ShareAudio.Choice.Off)
        return buildList {
            add(ShareRow.Header(ClientMessage.SHARE_AUDIO.text()))
            options.forEach { choice ->
                val label = when (choice) {
                    ShareAudio.Choice.ExceptGame -> ClientMessage.SHARE_AUDIO_EXCEPT_GAME.text()
                    ShareAudio.Choice.WholePc -> ClientMessage.SHARE_AUDIO_WHOLE_PC.text()
                    ShareAudio.Choice.Off -> ClientMessage.SHARE_AUDIO_NONE.text()
                    // Screens never offer one app (a window's own sound is picked with the window)
                    is ShareAudio.Choice.App -> return@forEach
                }
                add(ShareRow.Item(label, selected = choice == default) { start(choice) })
            }
        }
    }

    override fun onMouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val mx = mouseX.toInt()
        val my = mouseY.toInt()
        // An open popup swallows scrolls over it even when it has nothing to scroll, so the list beneath stays put.
        return audioTrackDropdown.handleScroll(mx, my, scrollY) ||
                subtitleDropdown.handleScroll(mx, my, scrollY) ||
                shareDropdown.handleScroll(mx, my, scrollY) ||
                dropdown.claimScroll(mx, my) ||
                shareDropdown.claimScroll(mx, my) ||
                audioTrackDropdown.claimScroll(mx, my) ||
                subtitleDropdown.claimScroll(mx, my) ||
                (::suggestions.isInitialized && suggestions.claimPopupScroll(mx, my)) ||
                (showingQueue() && queueSection.handleScroll(mx, my, scrollX, scrollY))
    }

    // Open popups get first claim on every button, ahead of the widgets they cover (see claimClick).
    private fun claimPopupClick(mx: Int, my: Int, button: Int): Boolean {
        if (!::popoutButton.isInitialized) return false
        val x = mx.toDouble()
        val y = my.toDouble()
        return dropdown.claimClick(mx, my, button, onToggle = popoutButton.isMouseOver(x, y)) ||
                shareDropdown.claimClick(mx, my, button, onToggle = shareButton.isMouseOver(x, y)) ||
                audioTrackDropdown.claimClick(mx, my, button, onToggle = audioTrackButton.isMouseOver(x, y)) ||
                subtitleDropdown.claimClick(mx, my, button, onToggle = subtitleButton.isMouseOver(x, y)) ||
                suggestions.claimPopupClick(mx, my, button)
    }

    private fun handleClick(mx: Int, my: Int, button: Int): Boolean =
        claimPopupClick(mx, my, button) ||
                (MouseButtons.isLeft(button) && ::queueSection.isInitialized && handleQueueClick(mx, my)) ||
                (MouseButtons.isLeft(button) && modLabel.handleClick(mx, my))

    // Scrollbar drags and seek commits belong to the left button only.
    private fun handleDrag(my: Int, button: Int): Boolean =
        MouseButtons.isLeft(button) &&
                (audioTrackDropdown.handleDrag(my) || subtitleDropdown.handleDrag(my) || shareDropdown.handleDrag(my))

    private fun handleRelease(button: Int): Boolean =
        MouseButtons.isLeft(button) &&
                (audioTrackDropdown.handleRelease() || subtitleDropdown.handleRelease() || shareDropdown.handleRelease() ||
                        progress.commitDragIfActive())

    //? if >=1.21.11 {
    override fun onMouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean =
        handleClick(event.x().toInt(), event.y().toInt(), event.button())

    override fun onMouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean =
        handleDrag(event.y().toInt(), event.button())

    override fun onMouseReleased(event: MouseButtonEvent): Boolean = handleRelease(event.button())
    //?} else
    /*override fun onMouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean =
        handleClick(mouseX.toInt(), mouseY.toInt(), button)

    override fun onMouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean =
        handleDrag(mouseY.toInt(), button)

    override fun onMouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean = handleRelease(button)*/

    override fun isPauseScreen(): Boolean = false

    override fun onRemoved() {
        if (::preview.isInitialized) preview.close()
    }

    override fun minContentSize(): Pair<Int, Int> = MIN_CONTENT_W to MIN_CONTENT_H

    private fun broadcastQuality(): Int {
        val ds = displayScreen
        val cap = ds.qualityCap
        return ds.qualityList.filter { it <= cap }.maxOrNull() ?: cap
    }

    private fun qualityStep(size: Int): Double = 1.0 / max(1, size - 1)

    private fun qualityFraction(q: String): Double {
        val list = displayScreen.qualityList
        if (list.isEmpty()) return 0.0
        val target = q.replace("p", "").toIntOrNull() ?: 1080
        val closest = list.minByOrNull { abs(target - it) } ?: return 0.0
        return list.indexOf(closest) / max(1, list.size - 1).toDouble()
    }

    private fun qualityFromFraction(v: Double): String {
        val list = displayScreen.qualityList
        if (list.isEmpty()) return "144"
        val idx = (v * (list.size - 1)).roundToInt().coerceIn(0, list.size - 1)
        return list[idx].toString()
    }

    companion object {
        private const val SEEK_STEP_NANOS = 5_000_000_000L
        private const val VOLUME_STEP = 0.025
        private const val SEEK_STEP_COARSE_NANOS = 15_000_000_000L
        private const val VOLUME_STEP_COARSE = 0.05
        private const val KEY_SPACE = InputConstants.KEY_SPACE
        private const val KEY_F = InputConstants.KEY_F
        private const val KEY_M = InputConstants.KEY_M
        private const val KEY_P = InputConstants.KEY_P
        private const val KEY_RIGHT = InputConstants.KEY_RIGHT
        private const val KEY_LEFT = InputConstants.KEY_LEFT
        private const val KEY_DOWN = InputConstants.KEY_DOWN
        private const val KEY_UP = InputConstants.KEY_UP

        private val DIGIT_KEYS = intArrayOf(
            InputConstants.KEY_0, InputConstants.KEY_1, InputConstants.KEY_2, InputConstants.KEY_3,
            InputConstants.KEY_4, InputConstants.KEY_5, InputConstants.KEY_6, InputConstants.KEY_7,
            InputConstants.KEY_8, InputConstants.KEY_9,
        )
        private val NUMPAD_DIGIT_KEYS = intArrayOf(
            InputConstants.KEY_NUMPAD0, InputConstants.KEY_NUMPAD1, InputConstants.KEY_NUMPAD2,
            InputConstants.KEY_NUMPAD3, InputConstants.KEY_NUMPAD4, InputConstants.KEY_NUMPAD5,
            InputConstants.KEY_NUMPAD6, InputConstants.KEY_NUMPAD7, InputConstants.KEY_NUMPAD8,
            InputConstants.KEY_NUMPAD9,
        )

        //? if >=1.21.11 {
        private const val MOD_CONTROL = InputConstants.MOD_CONTROL
        private const val MOD_ALT = InputConstants.MOD_ALT
        private const val MOD_SUPER = InputConstants.MOD_SUPER
        private const val MOD_SHIFT = InputConstants.MOD_SHIFT
        //?} else
        /*private const val MOD_CONTROL = 0x2; private const val MOD_ALT = 0x4; private const val MOD_SUPER = 0x8
        private const val MOD_SHIFT = 0x1*/
        private const val LOOP_BUTTON = 16
        private const val QUEUE_PLUS = 14
        private const val TAB_GAP = 10
        private const val CLEAR_CONFIRM_MS = 3_000L
        private const val QUEUE_FLASH_MS = 700L

        /** Most playlist videos one "+" queues; the server takes at most this many per batch as well. */
        private const val MAX_PLAYLIST_BATCH = 100

        /** The bottom panel shows "Up next" instead of suggestions; remembered across menu reopenings. */
        private var queueTabOpen = false

        /** Playlists being listed for a display ("<display>/<list id>"); render thread only. */
        private val playlistsLoading = mutableSetOf<String>()
        private const val MIN_CONTENT_W = 640
        private const val MIN_CONTENT_H = 410

        private val AUDIO_3D_MODES = [AcousticQuality.OFF, AcousticQuality.ADVANCED, AcousticQuality.ULTRA]

        private fun audio3dModeLabel(quality: AcousticQuality): String = when (quality) {
            AcousticQuality.OFF -> ClientMessage.MODE_AUDIO_OFF.key
            AcousticQuality.ULTRA -> ClientMessage.MODE_AUDIO_ADVANCED.key
            else -> ClientMessage.MODE_AUDIO_ENHANCED.key
        }

        private fun accessModesFor(ds: DisplayScreen): List<DisplayAccess> =
            if (ds.supportsRegionAccess || ds.access == DisplayAccess.REGION) {
                [DisplayAccess.EVERYONE, DisplayAccess.REGION, DisplayAccess.LOCKED]
            } else {
                [DisplayAccess.EVERYONE, DisplayAccess.LOCKED]
            }

        private fun accessLevelLabel(access: DisplayAccess): String = when (access) {
            DisplayAccess.EVERYONE -> ClientMessage.ACCESS_EVERYONE.key
            DisplayAccess.REGION -> ClientMessage.ACCESS_REGION.key
            DisplayAccess.LOCKED -> ClientMessage.ACCESS_LOCKED.key
        }

        /** Opens the menu for [displayScreen]. */
        fun open(displayScreen: DisplayScreen) {
            MinecraftScreenUtil.setScreen(Minecraft.getInstance(), DisplayMenu(displayScreen))
        }
    }
}
