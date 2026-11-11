package com.dreamdisplays.platform.client.displays

//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
//?} else
/*import net.minecraft.client.renderer.RenderType*/
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import com.dreamdisplays.shared.capability.ServerFeature
import com.dreamdisplays.api.display.model.property.DisplayRotation
import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.media.audio.AcousticEnvironment
import com.dreamdisplays.api.media.audio.AcousticQuality
import com.dreamdisplays.platform.client.core.modules.ClientAudioModule
import com.dreamdisplays.api.media.audio.SourceAcousticState
import com.dreamdisplays.api.media.audio.SourcePlane
import com.dreamdisplays.core.display.policy.DisplayPolicy
import com.dreamdisplays.core.display.policy.PolicyGainRamp
import com.dreamdisplays.core.display.policy.SyncSettings
import com.dreamdisplays.core.services.keys.AudioAcousticsServices
import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.api.media.stream.SubtitleTrack
import com.dreamdisplays.shared.playback.FullscreenMode
import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.shared.playback.PlaybackContext
import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.model.PlaybackSpeed
import com.dreamdisplays.api.playback.model.WatchPartyAction
import com.dreamdisplays.api.playback.model.WatchPartySessionState
import com.dreamdisplays.shared.playback.PlaybackPermissions
import com.dreamdisplays.api.watchparty.WatchPartySession
import com.dreamdisplays.core.protocol.common.hasFeature
import com.dreamdisplays.core.protocol.common.packets.*
import com.dreamdisplays.core.share.ScreenSharePrivacy
import com.dreamdisplays.core.share.ScreenShareViewerRules
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.audio.ListenerPoseTracker
import com.dreamdisplays.platform.client.audio.VoxelAcousticsProbe
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.DisplayScreen.Companion.DEFAULT_QUALITY
import com.dreamdisplays.platform.client.displays.DisplayScreen.Companion.ENV_PROBE_INTERVAL_TICKS
import com.dreamdisplays.platform.client.managers.ClientPacketManager
import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.platform.client.managers.DisplayPopoutManager
import com.dreamdisplays.platform.client.managers.FullscreenPrepareController
import com.dreamdisplays.api.display.geometry.DisplayGeometry
import com.dreamdisplays.platform.client.render.*
import com.dreamdisplays.platform.client.storage.ClientSettingsStore
import com.dreamdisplays.platform.client.ui.DisplayMenu
import com.dreamdisplays.platform.client.ui.PipCorner
import com.dreamdisplays.platform.client.ui.widgets.SuggestionsController
import com.dreamdisplays.platform.client.utils.MinecraftScreenUtil
import com.dreamdisplays.util.FacingUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.BlockPos
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.*
import kotlin.time.Duration.Companion.seconds
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Represents a video display screen in the game world. */
class DisplayScreen(
    /** Stable unique id of this display, shared with the server. */
    val uuid: UUID,

    /** ID of the player who created and owns this display. */
    val ownerUuid: UUID,

    private var x: Int,

    private var y: Int,

    private var z: Int,

    /** Direction the screen surface faces. */
    var facing: DisplayFacing,

    /** Screen width in blocks. */
    var width: Int,

    /** Screen height in blocks. */
    var height: Int,

    /** Current base playback mode (`LOCAL` / `SYNCED` / `BROADCAST`). */
    var mode: PlaybackMode,

    /** Hard quality cap in pixel height for Broadcast, or `0` for no cap. */
    var qualityCap: Int = 0,

    /** Content rotation; only used for floor/ceiling (`UP`/`DOWN`) screens. */
    var rotation: DisplayRotation = DisplayRotation.NONE,

    val dimensionKey: String = "",

    /** Blocks the screen extends away from [facing]. `1` for a flat wall. */
    var depth: Int = 1,

    /** True when the picture hugs slabs and stairs instead of one flat quad. */
    var conforming: Boolean = false,
) {
    private val savedSettings = ClientSettingsStore.getSettings(uuid, defaultVolume())

    /** True if the local player owns this display. */
    var owner: Boolean = Minecraft.getInstance().player?.gameProfile?.id?.toString() == ownerUuid.toString()

    /** True if the local player has admin (OP) permissions. */
    val isAdmin: Boolean get() = ClientStateManager.isAdmin

    /** Server-reported lock state, or `null` until the server reports it. */
    var isLocked: Boolean? = null

    /** Server-reported access level, or `null` until the server reports it. */
    var access: DisplayAccess? = null

    /**
     * True when the display stands inside a `WorldGuard` region. Only affects presentation: the
     * region access level is offered as a real choice here and explained as ineffective elsewhere.
     */
    var inRegion: Boolean = false

    /** Whether the local player belongs to that region; only meaningful at the region access level. */
    var viewerInRegion: Boolean = false

    /** The level a display in this spot starts at: shared with the region when it stands in one. */
    val defaultAccess: DisplayAccess
        get() = if (inRegion) DisplayAccess.REGION else DisplayAccess.DEFAULT

    /**
     * True when the server can resolve region membership at all (it runs `WorldGuard`). Where it
     * can't, the region level is left out of the UI entirely rather than offered as a dead option.
     */
    val supportsRegionAccess: Boolean
        get() = ClientPacketManager.serverSnapshot.hasFeature(ServerFeature.REGION_ACCESS)

    /** Epoch millis of a pending scheduled play / pause, or `0` when none is set (see [com.dreamdisplays.core.protocol.common.packets.DisplayInfo]). */
    var scheduledStartEpochMillis: Long = 0

    /** Wire ordinal of the scheduled [com.dreamdisplays.api.playback.model.PlaybackAction] (`PLAY`/`PAUSE`), or `-1` when none is set. */
    var scheduledAction: Int = -1

    private val errorGate = MediaErrorGate(cooldown = ERROR_RETRY_COOLDOWN)

    /**
     * The last media failure on this display, or `null` when healthy. A failure within
     * [ERROR_RETRY_COOLDOWN] of a retry is held back until the cooldown ends (see [MediaErrorGate]).
     */
    var mediaError: DreamMediaException?
        get() = errorGate.current
        set(value) = errorGate.set(value)

    /** True while a media error is active. */
    val errored: Boolean get() = mediaError != null

    /** True if the local player may edit this display (owner, admin, or not effectively locked). */
    val canEdit: Boolean get() = owner || isAdmin || !effectiveLocked

    /** Whether the user has muted this display. */
    var muted: Boolean = savedSettings.muted

    /** 3D acoustics engine (directivity, occlusion, reverb); false = legacy distance-gain only. */
    var acousticsEnabled: Boolean = savedSettings.acousticsEnabled

    /** Legacy mirror of [mode]; true only for [PlaybackMode.SYNCED]. */
    val isSync: Boolean get() = mode == PlaybackMode.SYNCED

    /** Live watch-party session over this display, or null when none is running. */
    @Volatile
    var watchParty: WatchPartySession? = null; internal set

    /** Whether the local player has marked themselves ready in the current session (UI toggle state). */
    @Volatile
    var localWatchPartyReady: Boolean = false; internal set

    /** Suggestions-panel state; survives menu close/reopen. */
    val suggestionsController = SuggestionsController()

    /** The effective mode the player experiences — `WATCH_PARTY` while a session is live. */
    val effectiveMode: PlaybackMode get() = if (watchParty != null) PlaybackMode.WATCH_PARTY else mode

    private fun ctx(): PlaybackContext = PlaybackContext(
        mode = effectiveMode,
        isOwner = owner,
        isAdmin = isAdmin,
        isLocked = PlaybackPermissions.isEffectivelyLocked(effectiveMode, lockedForMe()),
        hasActiveParty = watchParty != null,
        isPartyHost = watchParty?.isHost == true,
    )

    private fun lockedForMe(): Boolean = when (access) {
        DisplayAccess.EVERYONE -> false
        DisplayAccess.REGION -> !viewerInRegion
        DisplayAccess.LOCKED -> true
        null -> isLocked == true // Pre-hello, or a server too old to send a level
    }

    /** True if the local player may play/pause here. Locked displays allow only owner / admin controls. */
    val canControlPlayback: Boolean get() = PlaybackPermissions.canPlayPause(ctx())

    /** True if the local player may seek here. */
    val canSeekHere: Boolean get() = PlaybackPermissions.canSeek(ctx())

    /** Speed this viewer picked for a Local display (not saved; a shared timeline carries its own). */
    private var localSpeed: Double = PlaybackSpeed.NORMAL

    /** Speed of the server timeline this display follows (1 until a timeline says otherwise). */
    private var serverSpeed: Double = PlaybackSpeed.NORMAL

    /** The speed this display plays at: its shared timeline's, the viewer's own when Local, 1 in a watch party. */
    val playbackSpeed: Double
        get() = when {
            watchParty != null -> PlaybackSpeed.NORMAL
            requiresServerTimeline() -> serverSpeed
            else -> localSpeed
        }

    /**
     * True if the local player may change the speed here: whoever may play / pause, never in a watch party or on a
     * live stream, and on a shared timeline only when the server keeps a speed for it.
     */
    val canChangeSpeedHere: Boolean
        get() = PlaybackPermissions.canChangeSpeed(ctx()) && !isLive &&
                (!requiresServerTimeline() || ClientPacketManager.serverSnapshot.hasFeature(ServerFeature.PLAYBACK_SPEED))

    /** Asks for playback at [rate] x: applied here for a Local display, sent to the server for a Synced one. */
    fun requestSpeed(rate: Double) {
        if (!canChangeSpeedHere) return
        val speed = PlaybackSpeed.clamp(rate)
        if (requiresServerTimeline()) {
            if (speed != serverSpeed) emitPlaybackIntent(PlaybackAction.SPEED, rate = speed)
            return
        }
        localSpeed = speed
        syncPlaybackSpeed()
        DisplayRegistry.recordScreen(this)
    }

    /** Hands [playbackSpeed] to the player; cheap when it already plays at it. */
    internal fun syncPlaybackSpeed() {
        mediaPlayer?.setPlaybackSpeed(playbackSpeed)
    }

    /** True if the (personal) quality may be changed — false for Broadcast's hard cap. */
    val canChangeQualityHere: Boolean get() = PlaybackPermissions.canChangeQuality(ctx())

    /** True if the local player may change the display's video here (suggestions / SetVideo). */
    val canSetVideoHere: Boolean get() = PlaybackPermissions.canSetVideo(ctx())

    /** True if the local player may open the popout here — false in Broadcast for everyone. */
    val canPopoutHere: Boolean get() = PlaybackPermissions.canPopout(ctx())

    /** True if the base lock may be toggled (impossible in Watch Party / Broadcast). */
    val canToggleLockHere: Boolean get() = PlaybackPermissions.canToggleLock(ctx())

    /** True if the base mode may be switched. */
    val canSetModeHere: Boolean get() = PlaybackPermissions.canSetMode(ctx())

    /** True if the local player may start a watch party here. */
    val canStartWatchPartyHere: Boolean get() = PlaybackPermissions.canStartWatchParty(ctx())

    /** True if the local player may close the active watch party. */
    val canCloseWatchPartyHere: Boolean get() = PlaybackPermissions.canCloseWatchParty(ctx())

    /** True when the server runs queues and this display's mode lets the queue advance. */
    val queueRunsHere: Boolean get() = DisplayQueues.supported && PlaybackPermissions.queueRuns(effectiveMode)

    val canAddToQueueHere: Boolean get() = DisplayQueues.supported && PlaybackPermissions.canAddToQueue(ctx())

    val canSkipQueueHere: Boolean get() = DisplayQueues.supported && PlaybackPermissions.canSkipQueue(ctx())

    val canManageQueueHere: Boolean get() = DisplayQueues.supported && PlaybackPermissions.canManageQueue(ctx())

    /** Whether the local player may remove a queue entry; [isAdder] = they added it. */
    fun canRemoveFromQueueHere(isAdder: Boolean): Boolean =
        DisplayQueues.supported && PlaybackPermissions.canRemoveFromQueue(ctx(), isAdder)

    /** The lock the player actually sees: base lock, or forced on by Watch Party / Broadcast. */
    val effectiveLocked: Boolean get() = PlaybackPermissions.isEffectivelyLocked(effectiveMode, lockedForMe())

    private val textureResource = DisplayTextureResource(uuid)

    /** The live RGBA texture, or `null` in YUV mode / before allocation. */
    val texture: DynamicTexture? get() = textureResource.texture

    /** Resource identifier of the live texture, or `null` before allocation. */
    val textureId: Identifier? get() = textureResource.textureId

    /** [RenderType] used to draw the live video frame, or `null` before allocation. */
    val renderType: RenderType? get() = textureResource.renderType

    /** True once either texture flavor (RGBA or YUV planes) is allocated and the screen can be drawn. */
    val hasTexture: Boolean get() = textureResource.hasTexture

    /** True when the GPU-side YUV path backs this display (brightness is applied in the shader). */
    val isYuvTexture: Boolean get() = textureResource.isYuv

    /** [RenderType] for the loading / error color quads (differs from [renderType] in YUV mode). */
    @Suppress("UNUSED")
    val fallbackRenderType: RenderType? get() = textureResource.fallbackRenderType

    // During a quality handoff the new decoder must target the pending (new-resolution) texture,
    // not the live one — otherwise its frames never match the staged texture and the display freezes.
    val textureWidth: Int get() = if (textureResource.hasPending) textureResource.pendingWidth else textureResource.width
    val textureHeight: Int get() = if (textureResource.hasPending) textureResource.pendingHeight else textureResource.height

    /** Aspect ratio of the decoded video content (width / height); `0.0` until the first frame. */
    @Volatile
    var videoContentAspect: Double = 0.0

    /** User-set volume (`0.0`..`1.0`); writes apply the effective volume and persist the setting. */
    var volume: Float = savedSettings.volume
        set(value) {
            field = value
            applyEffectiveVolume()
            ClientSettingsStore.updateSettings(uuid, value, quality, brightness, muted, paused)
            DisplayRegistry.recordScreen(this)
        }

    /** Display brightness (`0`..`2`); writes push to the player and persist the setting. */
    var brightness: Float = savedSettings.brightness
        set(value) {
            field = value.coerceIn(0f, 2f)
            mediaPlayer?.setBrightness(field)
            ClientSettingsStore.updateSettings(uuid, volume, quality, field, muted, paused)
            DisplayRegistry.recordScreen(this)
        }

    /** Requested video quality; writes push the effective quality to the player and persist the setting. */
    var quality: VideoQuality = VideoQuality.parse(savedSettings.quality)
        set(value) {
            field = value
            mediaPlayer?.setQuality(effectiveQuality(value), userInitiated = qualityWriteIsViewerAction)
            ClientSettingsStore.updateSettings(uuid, volume, value, brightness, muted, paused)
            DisplayRegistry.recordScreen(this)
        }

    private var qualityWriteIsViewerAction = false

    /**
     * Applies a viewer-chosen [value]. Identical to assigning [quality], except the player is told the
     * change was asked for, so the UI may report it as being applied. Settings restores, server echoes
     * and the automatic distance ladder all assign [quality] directly and stay silent — otherwise every
     * video swap would flash an "applying quality" hint the viewer never triggered.
     */
    fun setQualityByViewer(value: VideoQuality) {
        qualityWriteIsViewerAction = true
        try {
            quality = value
        } finally {
            qualityWriteIsViewerAction = false
        }
    }

    /**
     * Requested audio track (stream URL); respawns audio only. [MinecraftDisplayCommands.setAudioTrack]
     * persists the track's *language* alongside this (stream URLs aren't stable across resolves), so
     * [restoreAudioTrackIfPending] can re-apply it once a later resolve exposes a matching track.
     */
    var audioTrack: String = ""
        set(value) {
            field = value
            pendingAudioTrackLangRestore = null
            mediaPlayer?.setAudioTrack(value)
        }

    private var pendingAudioTrackLangRestore: String? = savedSettings.audioTrackLang

    private fun restoreAudioTrackIfPending() {
        val wanted = pendingAudioTrackLangRestore ?: return
        val tracks = audioTrackList
        if (tracks.isEmpty()) return
        val match = tracks.firstOrNull { it.audioIdentity == wanted }
        pendingAudioTrackLangRestore = null
        if (match != null && match.url != currentAudioTrackUrl) audioTrack = match.url
    }

    /** Requested subtitle track language, or null to turn subtitles off. */
    var subtitleTrack: String? = null
        set(value) {
            field = value
            pendingSubtitleLangRestore = null
            mediaPlayer?.setSubtitleTrack(value)
        }

    private var pendingSubtitleLangRestore: String? = savedSettings.subtitleTrackLang.takeIf { savedSettings.subtitlesEnabled }

    private fun restoreSubtitleTrackIfPending() {
        val wanted = pendingSubtitleLangRestore ?: return
        val tracks = subtitleTrackList
        if (tracks.isEmpty()) return
        val match = tracks.firstOrNull { it.lang == wanted }
        pendingSubtitleLangRestore = null
        if (match != null) subtitleTrack = match.lang
    }

    private fun effectiveQuality(requested: VideoQuality = quality): VideoQuality {
        val policyCap = policy.quality?.maxHeight?.takeIf { it > 0 } ?: 0
        val cap = listOf(qualityCap, policyCap).filter { it > 0 }.minOrNull() ?: 0
        val requestedHeight = requested.targetHeight
        val base = when {
            cap <= 0 -> requested
            qualityCap > 0 -> VideoQuality.Fixed(cap)
            requestedHeight == null || requestedHeight > cap -> VideoQuality.Fixed(cap)
            else -> requested
        }
        return applyDistanceSteps(base)
    }

    /**
     * The quality the player opens a new video at: the requested quality, capped and stepped down for
     * distance like every later switch, so a far display never starts at full resolution.
     */
    internal val playbackQuality: VideoQuality get() = effectiveQuality()

    private var distanceQualitySteps = 0
    private fun updateDistanceQuality(fraction: Float) {
        var steps = distanceQualitySteps
        while (steps < DISTANCE_STEP_THRESHOLDS.size && fraction >= DISTANCE_STEP_THRESHOLDS[steps]) steps++
        while (steps > 0 && fraction < DISTANCE_STEP_THRESHOLDS[steps - 1] - DISTANCE_STEP_HYSTERESIS) steps--
        if (steps == distanceQualitySteps) return
        distanceQualitySteps = steps
        reloadQuality()
    }

    private fun applyDistanceSteps(base: VideoQuality): VideoQuality {
        val stepsDown = distanceQualitySteps
        if (stepsDown <= 0) return base
        val baseHeight = base.targetHeight ?: DEFAULT_QUALITY
        val idx = QUALITY_LADDER.indexOfFirst { it <= baseHeight }.let { if (it < 0) QUALITY_LADDER.lastIndex else it }
        val target = QUALITY_LADDER[(idx + stepsDown).coerceAtMost(QUALITY_LADDER.lastIndex)]
        return VideoQuality.Fixed(target)
    }

    /** True once the controller has applied the screen's initial state to the current player. */
    internal val videoStarted: Boolean get() = media.videoStarted

    /** Local paused state (user intent / server-followed). */
    internal var paused: Boolean = savedSettings.paused

    private var focusMuted: Boolean = false

    /** True while a fullscreen preparation keeps this display loaded for a broadcast that has not started yet. */
    val isPrepared: Boolean get() = FullscreenPrepareController.isPrepared(uuid)

    /**
     * True while prepared and out of sight (a broadcast-only display, or one past render distance): kept loaded and
     * on the timeline for the broadcast, but neither drawn in the world nor heard.
     */
    @Volatile
    var isHiddenForBroadcast: Boolean = false; private set

    /** Silenced while hidden by a preparation, or while opening its paused stream to hold it ready. */
    @Volatile
    private var prepareMuted: Boolean = false

    private var lastPrepareDistance: Double = Double.MAX_VALUE

    /** Distance in blocks past which the display is unloaded; mirrors the client's own render distance option. */
    val renderDistance: Int get() = DisplayPolicyCache.renderDistanceFor(policy)

    /** Last known playback position in nanoseconds, restored on reconnect. */
    var savedTimeNanos: Long = 0

    /** Follows the server-authoritative timeline (Synced / Broadcast / watch party). */
    internal val timelineFollower = TimelineFollower(this)

    /** Preloads this display's next queue entry shortly before the current video ends. */
    internal val queuePreloader = QueuePreloader(this)

    private val media = DisplayMediaController(this)
    private val watchPartyController = WatchPartyController(this)
    private val frameUploader = DisplayFrameUploader(uuid)
    private val mediaPlayer: MediaPlayer? get() = media.player

    /**
     * True when the live video is decoded with its alpha channel (straight-alpha RGBA frames). False under a shader
     * pack, where alpha video plays opaque.
     */
    val videoHasAlpha: Boolean get() = mediaPlayer?.videoHasAlpha == true

    /** Warm-parked out of render distance: decoder + audio open, instant resume. */
    @Volatile
    var isDormant: Boolean = false; private set

    private var dormantSinceNanos = 0L
    private val popoutManager = DisplayPopoutManager(this)

    /** The currently loaded video URL, or `null` when idle. */
    var videoUrl: String? = null; private set

    private var clientUrlOverride: Boolean = false

    @Transient
    private var blockPos: BlockPos? = null

    @Transient
    @Volatile
    private var hasEverRendered = false

    @Transient
    @Volatile
    private var firstFrameNanos = 0L

    @Transient
    @Volatile
    private var waitingForInitialTimeline = false

    @Transient
    @Volatile
    private var waitingSinceNanos = 0L

    private fun stillWaitingForInitialTimeline(): Boolean {
        if (waitingForInitialTimeline && waitingSinceNanos != 0L &&
            System.nanoTime() - waitingSinceNanos > WAITING_FOR_TIMELINE_TIMEOUT_NANOS
        ) {
            markInitialTimelineReady()
        }
        return waitingForInitialTimeline
    }

    /** Audio track / language of the current video, or `null` when idle. */
    var lang: String? = null; private set

    private var lastPacketLang: String? = null

    /** True once the video is effectively playing: not awaiting the initial timeline and a frame has filled. */
    val isVideoStarted: Boolean
        get() = !stillWaitingForInitialTimeline() && !privatePictureHidden() &&
                (hasEverRendered || mediaPlayer?.textureFilled() == true)

    /**
     * True while this display plays a private (screen share / relay) stream whose picture must not be drawn: off the
     * air, reconnecting or stalled. The "no video" look shows instead, never the held last frame.
     */
    private fun privatePictureHidden(): Boolean {
        val mp = mediaPlayer ?: return false
        return ScreenShareViewerRules.hidePicture(
            videoUrl, mp.isRelayPictureStale(ScreenShareViewerRules.STALE_PICTURE_NANOS),
        )
    }

    /** [privatePictureHidden] as of the last [tick], to drop the picture once when it goes stale. */
    private var privatePictureWasHidden = false

    /**
     * Drops every copy of the current private stream's picture: the display texture (reallocated blank), the player's
     * ready frame, the menu preview, and any popout window / PiP / fullscreen overlay of it (closed at once, no fade).
     * The display shows the "no video" look until a new frame arrives.
     */
    internal fun dropPrivatePicture(reason: String) {
        logger.info("Display {}: dropping the live picture ({}).", uuid, reason)
        popoutManager.closeImmediately(mediaPlayer)
        previewFrameCache?.clear()
        mediaPlayer?.clearFrame()
        hasEverRendered = false
        firstFrameNanos = 0L
        val mc = Minecraft.getInstance()
        if (mc.isSameThread) createTexture() else mc.execute { createTexture() }
    }

    private var publishedVideoStarted = false

    private fun publishStartedFlip() {
        val started = isVideoStarted
        if (started == publishedVideoStarted) return
        publishedVideoStarted = started
        DisplayRegistry.recordScreen(this)
    }

    private fun markRendered() {
        framePresented = true
        if (!hasEverRendered) firstFrameNanos = System.nanoTime()
        hasEverRendered = true
    }

    /** First-frame fade-in progress (0..1 eased); 1 = no fade or replay reappear. */
    internal fun appearProgress(): Float {
        val start = firstFrameNanos
        if (start == 0L || mediaPlayer?.isResumingFromReplay() == true) return 1f
        val dt = System.nanoTime() - start
        if (dt >= APPEAR_FADE_NANOS) return 1f
        val t = (dt.toFloat() / APPEAR_FADE_NANOS).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t) // Smoothstep
    }

    /** True while a PiP or window popout is open for this display. */
    val isPopoutActive: Boolean; get() = popoutManager.isActive

    /** True for a fullscreen broadcast's synthetic display. */
    var virtual: Boolean = false

    /** Data addons keep on this display, as the server last sent it. */
    var customData: Map<String, ByteArray> = emptyMap()

    private var policyBytes: ByteArray? = null

    /** Acoustics tier last handed to the engine for this display (null = following the global tier). */
    private var pushedAcoustics: AcousticQuality? = null

    /**
     * Server-controlled behaviour for this display ([DisplayPolicy]), with the client defaults filled in for
     * everything the server leaves unset. Re-read only when the server sends different policy bytes.
     */
    @Volatile
    var policy: DisplayPolicy = DisplayPolicy.CLIENT_DEFAULTS
        private set

    /** The server's `audio.gain` (karaoke track volume), eased in and out; never touches the saved volume. */
    private val policyGain = PolicyGainRamp()

    /** [policy]'s sync section, resolved and clamped. */
    @Volatile
    var syncSettings: SyncSettings = SyncSettings.DEFAULT
        private set

    private fun updatePolicy(data: Map<String, ByteArray>) {
        val bytes = data[DisplayPolicy.KEY]
        if (bytes != null && policyBytes != null && bytes.contentEquals(policyBytes)) return
        if (bytes == null && policyBytes == null && policy === DisplayPolicy.CLIENT_DEFAULTS) return
        policyBytes = bytes
        val effective = (DisplayPolicy.decode(bytes) ?: DisplayPolicy.EMPTY).mergedOver(DisplayPolicy.CLIENT_DEFAULTS)
        val previous = policy
        policy = effective
        syncSettings = SyncSettings.from(effective.sync)
        // Live policy edits (e.g. /display reload) take effect without the player touching anything
        if (previous.audio != effective.audio) applyEffectiveVolume()
        if (previous.quality != effective.quality) reloadQuality()
    }

    /** Anchor block position of the display (cached). */
    val pos: BlockPos; get() = blockPos ?: BlockPos(x, y, z).also { blockPos = it }

    // Resume position, not the raw clock: while a replay -> live bridge is mid-flight this reports the live
    // edge instead of the replay playhead, so unloading then (rapid leave / return) never regresses the
    // saved / captured position by the replay lead. Identical to the clock in normal playback.
    val currentTimeNanos: Long get() = mediaPlayer?.getResumePositionNanos() ?: 0L

    /** True when the current stream is a live broadcast (no seekable duration). */
    val isLive: Boolean get() = mediaPlayer?.isLive() == true

    /** True while a live relay stream (`srt://` ...) is off the air and its player keeps reconnecting. */
    val isWaitingForStream: Boolean get() = mediaPlayer?.isWaitingForStream() == true

    /** Total duration of the current video in nanoseconds, or `0` if unknown / live. */
    val mediaPlayerDurationNanos: Long get() = mediaPlayer?.getDuration() ?: 0L

    /** Raw stream URL for seek-bar scrub-preview frame extraction; null for live/unresolved. */
    val scrubPreviewRawUrl: String?
        get() = if (!ScreenSharePrivacy.mayPreview(videoUrl)) null
        else mediaPlayer?.capturedStreamRawUrl()?.takeIf(ScreenSharePrivacy::mayPreview)

    /** Whether scrub previews for this display must seek by decoding forward; see `MediaStream.seekByDecoding`. */
    val scrubPreviewSeeksByDecoding: Boolean get() = mediaPlayer?.capturedStreamSeeksByDecoding() == true

    /** Pixel heights of the qualities available for the current video. */
    val qualityList: List<Int>
        get() = mediaPlayer?.getAvailableQualities() ?: []

    /** Audio tracks available for the current video (more than one only when the provider exposes dubs). */
    val audioTrackList: List<MediaStream>
        get() = mediaPlayer?.getAvailableAudioTracks() ?: []

    /** Resolved URL of the audio track currently playing, or empty before a stream has resolved. */
    val currentAudioTrackUrl: String
        get() = mediaPlayer?.getCurrentAudioTrack() ?: ""

    /** True while an audio-track switch is in flight; the audio can lag the picked track by a few seconds. */
    val isSwitchingAudioTrack: Boolean
        get() = mediaPlayer?.isSwitchingAudioTrack() == true

    /** Subtitle tracks available for the current video (empty unless the provider exposed captions). */
    val subtitleTrackList: List<SubtitleTrack>
        get() = mediaPlayer?.getAvailableSubtitleTracks() ?: []

    /** Language of the currently selected subtitle track, or null when subtitles are off. */
    val currentSubtitleLang: String?
        get() = mediaPlayer?.getCurrentSubtitleLang()

    /** True while this viewer has subtitles turned on for this display. */
    val subtitlesEnabled: Boolean
        get() = mediaPlayer?.isSubtitlesEnabled() == true

    /** Subtitle line active at the current playback position, or null when off / between cues. */
    val currentSubtitleText: String?
        get() = mediaPlayer?.getCurrentSubtitleText()

    /** True while a quality change is still being applied; the new resolution lands a few seconds later. */
    val isApplyingQuality: Boolean
        get() = mediaPlayer?.isApplyingQuality() == true

    init {
        // Ask the server for the current timeline / session; it replies only if it has one
        sendRequestSyncPacket()
    }

    /** Loads a new video from [videoUrl], preserving the current paused state. */
    fun loadVideo(videoUrl: String, lang: String) {
        errorGate.resetAutoRetries()
        if (!clientUrlOverride) ClientSettingsStore.setUrlOverride(uuid, null, null)
        loadVideoInternal(videoUrl, lang, true)
    }

    /** Re-attempts current video after failure; purely local, no server packet. */
    fun retryVideo() {
        val url = videoUrl ?: return
        errorGate.markRetry()
        loadVideoInternal(url, lang ?: "", preservePausedState = true)
        // The new player's follower starts without a timeline; ask for it, or a synced display would play from
        // wherever it starts instead of rejoining the others
        if (requiresServerTimeline()) sendRequestSyncPacket()
    }

    /** Called once a load got far enough to start playing: unattended retries get their full budget back. */
    internal fun onMediaStarted() = errorGate.resetAutoRetries()

    /** Shows a held-back error once due, and retries a display that failed on the network on its own. */
    private fun recoverMediaErrorIfDue() {
        if (!errorGate.tick()) return
        logger.info("Display $uuid: retrying after a network error.")
        retryVideo()
    }

    /** Requests a server-authoritative video change from a picked suggestion. */
    fun playSuggestedVideo(videoUrl: String, lang: String): Boolean {
        if (!canSetVideoHere) return false
        clientUrlOverride = false
        ClientSettingsStore.setUrlOverride(uuid, null, null)
        Initializer.sendPacket(SetVideo(uuid, videoUrl, lang))
        return true
    }

    private fun loadVideoInternal(videoUrl: String, lang: String, preservePausedState: Boolean) {
        media.load(videoUrl, lang, preservePausedState)
    }

    /**
     * Records the new [videoUrl] and [lang] when the media controller swaps players. [awaitTimeline] false: a queue
     * entry starting on schedule, whose timeline is applied in the same step, so the picture is never held back.
     */
    internal fun onVideoSwapped(videoUrl: String, lang: String, awaitTimeline: Boolean = true) {
        this.videoUrl = videoUrl
        this.lang = lang
        waitingForInitialTimeline = awaitTimeline && requiresServerTimeline()
        waitingSinceNanos = if (waitingForInitialTimeline) System.nanoTime() else 0L
        pendingAudioTrackLangRestore = savedSettings.audioTrackLang
        pendingSubtitleLangRestore = savedSettings.subtitleTrackLang.takeIf { savedSettings.subtitlesEnabled }
    }

    /** The media controller stopped the player with nothing next ([DisplayMediaController.unload]). */
    internal fun onVideoCleared(lang: String) {
        videoUrl = ""
        this.lang = lang
        waitingForInitialTimeline = false
        waitingSinceNanos = 0L
        privatePictureWasHidden = false
    }

    /** True while the screen is holding back the picture until the server's first timeline arrives. */
    internal val isWaitingForInitialTimeline: Boolean get() = stillWaitingForInitialTimeline()

    /** Clears the initial-timeline gate so the video may render. */
    internal fun markInitialTimelineReady() {
        waitingForInitialTimeline = false
        waitingSinceNanos = 0L
    }

    /** Primes the player to begin at [positionNanos] so the first frame lands on the synced position. */
    internal fun primeTimelineStart(positionNanos: Long) {
        mediaPlayer?.primeStartPosition(positionNanos.coerceAtLeast(0L))
    }

    /** Drops the rendered frame so the screen re-fades in after a timeline-driven seek. */
    internal fun clearRenderedFrameForTimeline() {
        hasEverRendered = false
        firstFrameNanos = 0L
        mediaPlayer?.clearFrame()
    }

    private fun requiresServerTimeline(): Boolean =
        mode == PlaybackMode.SYNCED || mode == PlaybackMode.BROADCAST || watchParty != null

    /** Sizes the GPU texture buffers for the current dimensions and quality before the first frame. */
    internal fun prepareTextureDimensions() {
        // A new player starts out opaque; it switches the texture over once it knows its video has alpha
        textureResource.alphaMode = false
        textureResource.prepareDimensions(width, height, parseQualityOrDefault())
    }

    /** Re-attaches the popout sink chain to a freshly created [player]. */
    internal fun attachPopout(player: MediaPlayer) {
        popoutManager.attachTo(player) { videoContentAspect }
    }

    /** Attaches or clears the menu preview raw-frame sink on the current player. */
    fun setPreviewFrameSink(sink: ((ByteBuffer, Int, Int, UploadPixelFormat) -> Unit)?) {
        mediaPlayer?.setPreviewSink(
            if (sink == null) null else { buf, w, h, fmt -> sink(buf, w, h, fmt.toUploadFormat()) },
        )
    }

    @Transient
    private var previewFrameCache: PreviewFrameTexture? = null

    /** The display's persistent preview texture, created on first use and released in [unregister]. */
    internal fun previewFrameTexture(): PreviewFrameTexture =
        previewFrameCache ?: PreviewFrameTexture(uuid).also { previewFrameCache = it }

    @Transient
    private var subtitleOverlayCache: SubtitleOverlayTexture? = null

    internal fun subtitleOverlayTexture(): SubtitleOverlayTexture =
        subtitleOverlayCache ?: SubtitleOverlayTexture().also { subtitleOverlayCache = it }

    /** Updates position, dimensions, and video URL from an incoming [DisplayInfo] packet. */
    fun updateData(packet: DisplayInfo) {
        virtual = packet.virtual
        customData = acceptedCustomData(packet)
        updatePolicy(customData)
        x = packet.x
        y = packet.y
        z = packet.z
        blockPos = null
        facing = FacingUtil.fromPacket(packet.facing.toByte()).toDisplayFacing()
        rotation = DisplayRotation.fromQuarterTurns(packet.rotation)
        width = packet.width
        height = packet.height
        depth = packet.depth.coerceAtLeast(1)
        conforming = packet.conforming

        val nextMode = if (packet.mode == PlaybackMode.LOCAL.wire && packet.isSync) {
            PlaybackMode.SYNCED
        } else {
            PlaybackMode.fromWire(packet.mode)
        }
        mode = nextMode

        qualityCap = packet.qualityCap
        isLocked = packet.isLocked
        access = DisplayAccess.fromWire(packet.access)
        inRegion = packet.inRegion
        viewerInRegion = packet.viewerInRegion
        scheduledStartEpochMillis = packet.scheduledStartEpochMillis
        scheduledAction = packet.scheduledAction
        owner = Minecraft.getInstance().player?.gameProfile?.id?.toString() == packet.ownerId.toString()

        if (videoUrl != packet.url || lastPacketLang != packet.lang) {
            val previousUrl = videoUrl
            lastPacketLang = packet.lang
            // A screen share (any private relay stream) ended here: nothing of it stays up, not even until the next
            // video's first frame, and with nothing next the player stops instead of holding its last picture
            if (ScreenShareViewerRules.clearOnUrlChange(previousUrl, packet.url)) {
                dropPrivatePicture("display URL changed")
                if (ScreenShareViewerRules.stopPlayerOnUrlChange(previousUrl, packet.url)) {
                    media.unload(packet.lang)
                    return
                }
            }
            if (clientUrlOverride && canSetVideoHere) return
            if (clientUrlOverride) {
                clientUrlOverride = false
                ClientSettingsStore.setUrlOverride(uuid, null, null)
            }

            val ds = ClientSettingsStore.getSettings(uuid)
            val override = ds.urlOverride

            if (!override.isNullOrEmpty() && canSetVideoHere) {
                clientUrlOverride = true
                val overrideLang = ds.langOverride ?: packet.lang
                paused = false
                if (override != previousUrl) savedTimeNanos = 0L
                loadVideo(override, overrideLang)
                return
            } else if (!override.isNullOrEmpty()) {
                ClientSettingsStore.setUrlOverride(uuid, null, null)
            }

            paused = false
            if (packet.url != previousUrl) savedTimeNanos = 0L
            loadVideo(packet.url, ds.audioTrackLang ?: packet.lang)
            sendRequestSyncPacket()
        }
    }

    private fun sendRequestSyncPacket() {
        Initializer.sendPacket(RequestSync(uuid))
    }


    /** Applies the authoritative server timeline: matches pause state and corrects drift. */
    fun updateData(packet: DisplaySync) {
        if (watchParty != null) return
        // The next queue entry's start, scheduled at the current video's end: kept for that instant
        if (media.onTimelineWhileSwitching(packet)) return
        serverSpeed = PlaybackSpeed.clamp(packet.rate)
        com.dreamdisplays.platform.client.karaoke.KaraokeClient.onSync(uuid, packet.karaokeLeadMs)
        // A speed change normally comes as a hold (paused): pause first, so the player is only retimed while it is
        // stopped. A playing packet retimes first, so the resume or seek the follower triggers runs at the new speed.
        if (packet.isPaused) {
            timelineFollower.apply(
                packet.currentTimeMs, packet.serverTimeMs, true, packet.loop, serverSpeed, leadMs = packet.karaokeLeadMs,
            )
            syncPlaybackSpeed()
        } else {
            syncPlaybackSpeed()
            timelineFollower.apply(
                packet.currentTimeMs, packet.serverTimeMs, false, packet.loop, serverSpeed, packet.startInMs,
                packet.karaokeLeadMs,
            )
        }
    }

    /** The display audio's output latency now (karaoke timing), or null while it plays no audio. */
    internal fun audioOutputLatency(): com.dreamdisplays.media.player.pipeline.AudioOutputLatency? =
        mediaPlayer?.audioOutputLatency()

    /** The current media player, for the karaoke track tap (it changes with every video). */
    internal val karaokePlayer: MediaPlayer? get() = mediaPlayer

    /** Applies watch-party snapshot: tracks session, loads host video, follows timeline. */
    fun updateWatchParty(packet: WatchPartyState) {
        if (packet.sessionId.isEmpty()) {
            watchParty = null
            localWatchPartyReady = false
            sendRequestSyncPacket() // Pull the base-mode timeline back
            return
        }
        if (watchParty?.sessionId != packet.sessionId) localWatchPartyReady = false
        val localId = Minecraft.getInstance().player?.gameProfile?.id
        val state = WatchPartySessionState.fromWire(packet.state)
        watchParty = WatchPartySession(
            sessionId = packet.sessionId,
            state = state,
            isHost = localId != null && localId == packet.hostId,
            hostName = packet.hostName,
            readyCount = packet.readyCount,
            nearbyCount = packet.nearbyCount,
            positionMs = packet.positionMs,
            // Both stamps are server-time, so this is immune to client / server wall-clock skew
            countdownRemainingMs = (packet.countdownStartEpochMs - packet.serverTimeMs)
                .takeIf { state == WatchPartySessionState.COUNTDOWN && it > 0 },
        )

        if (packet.url.isNotEmpty() && packet.url != videoUrl) loadVideo(packet.url, packet.lang)

        when (state) {
            WatchPartySessionState.PLAYING, WatchPartySessionState.PAUSED ->
                timelineFollower.apply(packet.positionMs, packet.serverTimeMs, packet.paused, loop = false)

            else -> if (!isPaused && videoStarted) applyServerPaused(true)
        }
    }

    /** Forces the pause state and starts playback; used by the sync controller before the video has started. */
    internal fun beginPlaybackPaused(desiredPaused: Boolean) {
        paused = desiredPaused
        startVideo()
    }

    /** True if the media player's playback clock is currently advancing. */
    internal fun isClockRunning(): Boolean = mediaPlayer?.isClockRunning() == true

    /** Position actually heard / shown, for sync drift measurement (see [MediaPlayer.getSyncPositionNanos]). */
    internal val syncPositionNanos: Long get() = mediaPlayer?.getSyncPositionNanos() ?: 0L

    /** Sets the sync playback rate used to close small drift (1.0 = normal speed). */
    internal fun setSyncPlaybackRate(ratio: Double) {
        mediaPlayer?.setSyncPlaybackRate(ratio)
    }

    /** True when a resume would continue the open session instantly rather than restart the stream. */
    internal fun resumesWarm(): Boolean = mediaPlayer?.resumesWarm() == true

    /** True while this display follows a server timeline (Synced / Broadcast / watch party). */
    internal val followsServerTimeline: Boolean get() = requiresServerTimeline()

    /** A preloaded queue entry is waiting for its scheduled start (the current video plays on until then). */
    internal val isQueueSwitchPending: Boolean get() = media.isSwitchPending

    /** The stream ended ahead of the server timeline and holds its last frame until that timeline moves on. */
    internal val isHeldAtEnd: Boolean get() = mediaPlayer?.isHeldAtEnd() == true

    /** The current media/player generation, used by the sync controller to detect stale video swaps. */
    internal val mediaGeneration: Long get() = media.generationNow

    /** Recreates the GPU texture (e.g. after a resolution change). */
    fun reloadTexture() = createTexture()

    /**
     * Pushes the current quality setting to the media player (clamped to [qualityCap], stepped for distance /
     * size). `Auto` is pinned to the height it opens at, or stepping back up from a lower rung would be a no-op.
     */
    fun reloadQuality() {
        mediaPlayer?.setQuality(VideoQuality.Fixed(effectiveQuality().targetHeight ?: DEFAULT_QUALITY))
    }

    /** Returns true if [pos] falls within the screen's block bounding box. */
    fun isInScreen(pos: BlockPos): Boolean =
        PlacedDisplays.contains(uuid, width, height, depth, facing, pos)
            ?: DisplayGeometry.isInBounds(pos.x, pos.y, pos.z, x, y, z, width, height, facing, depth)

    /** Returns the shortest Euclidean distance from [pos] to any block in the screen's bounding box. */
    fun getDistanceToScreen(pos: BlockPos): Double =
        PlacedDisplays.distance(uuid, width, height, depth, facing, pos)
            ?: DisplayGeometry.distanceTo(pos.x, pos.y, pos.z, x, y, z, width, height, facing, depth)

    private var cachedPlane: SourcePlane? = null
    private var planeX = 0
    private var planeY = 0
    private var planeZ = 0
    private var planeWidth = 0
    private var planeHeight = 0
    private var planeDepth = 0
    private var planeFacing: DisplayFacing? = null

    private fun sourcePlane(): SourcePlane {
        // A display an addon has moved cannot use the cache: its plane changes every tick.
        PlacedDisplays.sourcePlane(uuid, width, height, depth, facing)?.let { return it }
        val cached = cachedPlane
        if (cached != null && planeX == x && planeY == y && planeZ == z && planeWidth == width &&
            planeHeight == height && planeDepth == depth && planeFacing == facing
        ) return cached
        planeX = x; planeY = y; planeZ = z
        planeWidth = width; planeHeight = height; planeDepth = depth; planeFacing = facing
        cachedReverb = null
        return toSourcePlane().also { cachedPlane = it }
    }

    private fun toSourcePlane(): SourcePlane {
        val pose = DisplayGeometry.pose(x, y, z, width, height, facing, depth)
        return SourcePlane(
            pose.centerX, pose.centerY, pose.centerZ,
            pose.normalX, pose.normalY, pose.normalZ,
            pose.uAxisX, pose.uAxisY, pose.uAxisZ,
            pose.vAxisX, pose.vAxisY, pose.vAxisZ,
            width.toDouble(), height.toDouble(),
        )
    }

    /** Uploads the latest decoded frame to the GPU texture(s). Called on the render thread once per frame. */
    fun fitTexture() {
        val mp = mediaPlayer ?: return
        matchAlphaTexture(mp)
        val start = System.nanoTime()
        framePresented = false
        frameUploader.upload(mp, textureResource, onRendered)
        // Stutter detection: the gap between presented frames against the expected one (no allocation per draw)
        val now = System.nanoTime()
        val running = !isPaused && mp.isClockRunning()
        if (frameGaps.onDraw(now, framePresented, now - start, running, mp.frameIntervalNanos())) {
            ClientErrorReporter.onStutter(this, mp, frameGaps, now)
        }
    }

    /** Finds short stutters in this display's presented frames (render thread only). */
    internal val frameGaps = com.dreamdisplays.media.runtime.diagnostics.FrameGapDetector()

    /** A new frame landed during the current [fitTexture] call. */
    private var framePresented = false

    /** The player the live texture was last switched to alpha for. */
    private var alphaTexturePlayer: MediaPlayer? = null

    /**
     * Reallocates the texture when the player's video output and the texture disagree about alpha: an alpha video is
     * drawn from a capped RGBA texture whatever the render path, an opaque one from the usual texture. Render thread.
     */
    private fun matchAlphaTexture(mp: MediaPlayer) {
        val alpha = mp.videoHasAlpha
        if (alpha == textureResource.alphaMode && (!textureResource.hasTexture || alpha == textureResource.isAlpha)) return
        val leavingAlpha = textureResource.isAlpha && !alpha
        textureResource.alphaMode = alpha
        createTexture()
        if (alpha) {
            alphaTexturePlayer = mp
        } else if (leavingAlpha && alphaTexturePlayer === mp) {
            // The same player went opaque (a new stream set): its decode is still sized for the capped alpha
            // texture, so reopen it at the full size
            alphaTexturePlayer = null
            mp.restartVideoPipeline()
        }
    }

    private val onRendered: () -> Unit = ::markRendered

    /**
     * Render-thread stand-in for [fitTexture] while the display is culled from view. Frames are not uploaded
     * (the decoder keeps running and the newest one goes up as soon as the display is back in view), except
     * while a quality handoff is staged: that one only completes once its first frame lands.
     */
    fun fitTextureWhileHidden() {
        if (textureResource.hasPending) fitTexture() else frameGaps.hidden()
    }

    private val sphereScratch = DoubleArray(4)

    /** World-space bounding sphere of the display's blocks as center x / y / z and radius, refreshed per call. */
    internal fun boundingSphere(): DoubleArray {
        if (PlacedDisplays.boundingSphere(uuid, width, height, depth, facing, sphereScratch)) return sphereScratch
        DisplayGeometry.boundingSphere(x, y, z, width, height, facing, depth, sphereScratch)
        return sphereScratch
    }

    /** Renders frame to popout; call after all Minecraft / mod rendering to avoid GL-context corruption. */
    fun renderPopout() {
        popoutManager.renderFrame()
    }

    /** Passes [volume] directly to the media player without persisting to settings. */
    fun setVideoVolume(volume: Float) {
        mediaPlayer?.setVolume(playVolume(volume))
    }

    /** [capVolume] times the server's (ramped) `audio.gain`. */
    private fun playVolume(volume: Float): Float =
        (capVolume(volume) * policyGain.value(System.nanoTime())).toFloat().coerceIn(0f, 2f)

    /** Moves the gain ramp toward this display's target and re-applies the volume while it moves; every tick. */
    private fun tickPolicyGain() {
        val now = System.nanoTime()
        policyGain.retarget(PolicyGainRamp.targetFor(policy, com.dreamdisplays.platform.client.karaoke.KaraokeClient.isSinging(uuid)), now)
        if (policyGain.pollChanged(now)) applyEffectiveVolume()
    }

    /** [volume] limited to the server's `audio.maxVolume` for this display, when set. */
    private fun capVolume(volume: Float): Float =
        policy.audio?.maxVolume?.let { minOf(volume, it.toFloat().coerceIn(0f, 2f)) } ?: volume

    /** Distance at which this display's sound fades out: the server's `audio.radiusBlocks`, else the client's. */
    private fun audioRadius(): Double =
        if (isPopoutActive) Double.MAX_VALUE
        else policy.audio?.radiusBlocks?.takeIf { it > 0 } ?: ClientStateManager.config.defaultDistance.toDouble()

    private fun audioFullVolumeRadius(): Double = policy.audio?.fullVolumeBlocks?.coerceAtLeast(0.0) ?: 0.0

    private fun audioSquaredFalloff(): Boolean = policy.audio?.falloff != DisplayPolicy.Falloff.LINEAR

    /** Applies effective volume (0 if muted/unfocused, else user volume). */
    internal fun applyEffectiveVolume() {
        setVideoVolume(if (muted || focusMuted || prepareMuted) 0f else volume)
    }

    /** True when paused and parked on a decoded start-up cushion by [holdReadyForBroadcast]: a resume plays at once. */
    internal val isHeldReady: Boolean get() = mediaPlayer?.isHeldReady() == true

    /** True when a seek now lands within a frame or two (see [MediaPlayer.seeksCheaply]). */
    internal val seeksCheaply: Boolean get() = mediaPlayer?.seeksCheaply() == true

    /** True while [holdReadyForBroadcast] is opening the paused stream. */
    internal val isWarmingUp: Boolean get() = mediaPlayer?.isWarmingUp() == true

    /**
     * Opens the paused stream at the server's paused position and parks it on its decoded first frame, so the next
     * resume plays at once instead of opening the stream then (see [MediaPlayer.warmUpPaused]). Driven by
     * [com.dreamdisplays.platform.client.managers.DisplayWarmup].
     */
    internal fun holdReadyForBroadcast() {
        val mp = mediaPlayer ?: return
        val target = timelineFollower.pausedTargetNanos() ?: currentTimeNanos
        timelineFollower.onHeldReady()
        mp.warmUpPaused(target)
        refreshPrepareState()
    }

    /** Re-evaluates the preparation's hiding and muting now (also done every tick). */
    fun refreshPrepareState(distance: Double = lastPrepareDistance) {
        lastPrepareDistance = distance
        val prepared = isPrepared
        isHiddenForBroadcast = prepared && !isPopoutActive && (virtual || distance > renderDistance)
        // Opening a paused stream to hold it ready may release a moment of sound: never let it be heard
        val silenced = isHiddenForBroadcast || (!isPopoutActive && isWarmingUp)
        if (silenced == prepareMuted) return
        prepareMuted = silenced
        applyEffectiveVolume()
    }

    /** Opens or focuses the `GLFW` window mode. Closes PiP if active. */
    fun activateWindowMode() {
        if (!canPopoutHere) return
        val mp = mediaPlayer ?: return
        popoutManager.activateWindowMode(mp, textureWidth, textureHeight) { videoContentAspect }
    }

    /** Shows the in-game PiP overlay at [corner]. Closes window mode if active. */
    fun activatePipMode(corner: PipCorner = PipCorner.BOTTOM_RIGHT) {
        if (!canPopoutHere) return
        val mp = mediaPlayer ?: return
        popoutManager.activatePipMode(mp, corner) { videoContentAspect }
    }

    /** Closes whichever popout mode is active. */
    fun deactivatePopout() {
        popoutManager.deactivate(mediaPlayer)
    }

    private var fullscreenLoop = false

    /** [fullscreenLoop] exposed to [DisplayPlaybackHost]. */
    internal val isFullscreenLoop: Boolean get() = fullscreenLoop

    /** Last applied fullscreen state; survives server switch but not display re-creation. */
    @Volatile
    internal var lastFullscreenState: FullscreenState? = null

    /** Shows this display's video as a fullscreen overlay in [mode]. Closes PiP if active. */
    fun activateFullscreenMode(
        mode: FullscreenMode = FullscreenMode.STANDARD,
        forced: Boolean = false,
        sessionId: String? = null,
        loop: Boolean = false,
    ) {
        if (!canPopoutHere && !forced) return
        val mp = mediaPlayer ?: return
        fullscreenLoop = loop
        popoutManager.activateFullscreenMode(mp, mode, forced, sessionId) { videoContentAspect }
    }

    /** Closes the fullscreen overlay, keeping other popout surfaces alive. */
    fun deactivateFullscreen() {
        popoutManager.deactivateFullscreen(mediaPlayer)
    }

    /** Swaps fullscreen to non-interactive PiP (forced-broadcast Esc behavior). */
    fun minimizeFullscreenToPip() {
        popoutManager.minimizeFullscreenToPip(mediaPlayer, interactive = false)
    }

    /** True while this display is shown in a PiP overlay. */
    val isPipActive: Boolean get() = popoutManager.isPipActive

    /** True while this display is shown in the fullscreen overlay. */
    val isFullscreenActive: Boolean get() = popoutManager.isFullscreenActive

    /** Applies volume, brightness, and paused state to the media player, then seeks to the saved position. */
    fun startVideo() = media.start()

    /** Whether playback is currently paused. */
    val isPaused: Boolean get() = paused

    /** User intent to pause / resume: applied locally for instant feedback and emitted upstream per mode. */
    fun setPaused(paused: Boolean) {
        if (!canControlPlayback) return
        applyPausedLocal(paused)
        emitPlaybackIntent(if (paused) PlaybackAction.PAUSE else PlaybackAction.PLAY)
    }

    /** Applies the server's pause state without emitting any upstream intent (timeline-follower path). */
    internal fun applyServerPaused(paused: Boolean) = applyPausedLocal(paused)

    private fun applyPausedLocal(paused: Boolean) {
        if (!videoStarted) {
            this.paused = paused
            DisplayRegistry.recordScreen(this)
            waitForMFInit { startVideo() }
            return
        }
        if (this.paused == paused) return
        this.paused = paused
        if (paused) mediaPlayer?.pause() else mediaPlayer?.play()
        ClientSettingsStore.updateSettings(uuid, volume, quality, brightness, muted, paused)
        DisplayRegistry.recordScreen(this)
    }

    /** Marks a local VOD as finished without emitting playback commands upstream; also auto-closes a non-looping fullscreen overlay. */
    internal fun onPlaybackEnded(positionNanos: Long) {
        if (isFullscreenActive && !fullscreenLoop) deactivateFullscreen()

        if (effectiveMode != PlaybackMode.LOCAL) return
        savedTimeNanos = positionNanos.coerceAtLeast(0L)
        if (paused) return
        paused = true
        ClientSettingsStore.updateSettings(uuid, volume, quality, brightness, muted, paused)
        DisplayRegistry.recordScreen(this)
    }

    private fun emitPlaybackIntent(
        action: PlaybackAction, positionMs: Long = currentTimeNanos / 1_000_000L, rate: Double = PlaybackSpeed.NORMAL,
    ) {
        when (effectiveMode) {
            PlaybackMode.SYNCED -> Initializer.sendPacket(PlaybackCommand(uuid, action.wire, positionMs, rate))
            PlaybackMode.WATCH_PARTY -> if (watchParty?.isHost == true) action.toWatchPartyAction()?.let {
                Initializer.sendPacket(WatchPartyControl(uuid, it.wire, positionMs))
            }

            else -> {}
        }
    }

    private fun PlaybackAction.toWatchPartyAction(): WatchPartyAction? = when (this) {
        PlaybackAction.PLAY -> WatchPartyAction.RESUME
        PlaybackAction.PAUSE -> WatchPartyAction.PAUSE
        PlaybackAction.SEEK -> WatchPartyAction.SEEK
        PlaybackAction.RESTART -> WatchPartyAction.RESTART
        PlaybackAction.SPEED -> null // Watch parties play at normal speed
    }

    /** Seeks [seconds] seconds relative to the current playback position (negative = backward). */
    fun seekVideoRelative(seconds: Double) {
        if (!canSeekHere) return
        val mp = mediaPlayer ?: return
        if (mp.canSeek()) mp.seekRelative(seconds)
    }

    /** Seeks to an absolute position [nanos] without firing the sync event (used for incoming sync packets). */
    internal fun seekVideoTo(nanos: Long) {
        val mp = mediaPlayer ?: return
        if (mp.canSeek()) mp.seekTo(nanos, false)
    }

    /** Seeks to [ms] and fires the seek event so [afterSeek] emits the intent upstream (Synced / WP host). */
    fun seekToMillis(ms: Long) {
        if (!canSeekHere) return
        val mp = mediaPlayer ?: return
        if (mp.canSeek()) mp.seekTo(ms * 1_000_000L, true)
    }

    /** Stops the media player, releases GPU texture, closes any popout, and closes the display menu if open. */
    fun unregister() {
        queuePreloader.cancel()
        DisplayQueues.forget(uuid)
        captureReplayCache()
        val currentPlayer = media.shutdown()
        popoutManager.unregister(currentPlayer)
        currentPlayer?.stop()

        textureResource.releaseAsync()
        previewFrameCache?.closeAsync()
        previewFrameCache = null
        subtitleOverlayCache?.dispose()
        subtitleOverlayCache = null

        val mc = Minecraft.getInstance()
        val screen = MinecraftScreenUtil.currentScreen(mc)
        if (screen is DisplayMenu && screen.displayScreen === this) screen.onClose()
    }

    private fun captureReplayCache() {
        if (mode != PlaybackMode.LOCAL || watchParty != null || isLive) return
        val url = videoUrl?.takeIf(ScreenSharePrivacy::maySnapshot) ?: return
        val mp = mediaPlayer ?: return
        val position = currentTimeNanos
        val started = System.nanoTime()
        val snapshot = mp.captureReplaySnapshot() ?: return
        DisplayReplayCache.put(uuid, url, position, snapshot, mp.capturePreparedMedia())
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        logger.debug(
            "{} captured replay snapshot bytes={} at {} ms in {} ms.",
            uuid,
            snapshot.size,
            "%.1f".format(position / 1_000_000.0),
            "%.1f".format(elapsedMs)
        )
    }

    /** Mutes or unmutes the screen; no-op if already in the requested state. */
    fun mute(status: Boolean) {
        if (muted == status) return
        muted = status
        applyEffectiveVolume()
        ClientSettingsStore.updateSettings(uuid, volume, quality, brightness, muted, paused)
        DisplayRegistry.recordScreen(this)
    }

    /** Applies temporary focus mute without changing the user's persisted mute setting. */
    fun setFocusMuted(status: Boolean) {
        if (focusMuted == status) return
        focusMuted = status
        applyEffectiveVolume()
    }

    /** Allocates (or reallocates) the GPU texture and render type for the current quality setting. */
    fun createTexture() {
        hasEverRendered = false
        firstFrameNanos = 0L
        textureResource.allocate(width, height, parseQualityOrDefault())
    }

    /** Stages new-resolution texture; live frame renders until first new frame (render thread only). */
    fun beginQualityHandoff() {
        textureResource.allocatePending(width, height, parseQualityOrDefault())
    }

    /** Drops any staged quality-handoff texture (e.g. when a full session restart supersedes it). */
    fun cancelQualityHandoff() {
        textureResource.discardPendingAsync()
    }

    /** Switches the persistent base mode (`LOCAL` / `SYNCED` / `BROADCAST`); the server validates and echoes. */
    fun requestMode(newMode: PlaybackMode) {
        if (!canSetModeHere) return
        if (PlaybackMode.isBaseMode(newMode)) {
            Initializer.sendPacket(SetMode(uuid, newMode.wire, currentTimeNanos / 1_000_000L))
        }
    }

    /** Starts a watch party here with the current (or given) video; the local player becomes host. */
    fun startWatchParty(url: String = videoUrl ?: "", lang: String = this.lang ?: "") =
        watchPartyController.start(url, lang)

    /** Marks the local player ready / not-ready in the active watch party. */
    fun setWatchPartyReady(ready: Boolean) = watchPartyController.setReady(ready)

    /** Host action: starts the countdown for the active watch party. */
    fun beginWatchParty() = watchPartyController.begin()

    /** Host action: ends the active watch party (freezes on the final frame). */
    fun endWatchParty() = watchPartyController.end()

    /** Host action: restarts an ended watch party from preparation. */
    fun restartWatchParty() = watchPartyController.restart()

    /** Closes the watch party, handing the display back to its base mode (host / owner / admin). */
    fun closeWatchParty() = watchPartyController.close()

    /** Seeks to the saved playback position after reconnection; only meaningful for Local displays. */
    fun restoreSavedTime() {
        if (mode != PlaybackMode.LOCAL) return
        val mp = mediaPlayer ?: return
        if (abs(mp.getCurrentTime() - savedTimeNanos) <= RESTORE_SEEK_TOLERANCE_NS) return
        if (savedTimeNanos > 0) mp.seekTo(savedTimeNanos, false)
    }

    /** Primes player volume before prelude audio to avoid blast on return. */
    internal fun primeNewPlayerVolume(mp: MediaPlayer) {
        val player = Minecraft.getInstance().player ?: return
        mp.primeVolume(
            if (muted || focusMuted || prepareMuted) 0f else playVolume(volume), getDistanceToScreen(player.blockPosition()),
            audioRadius(), audioFullVolumeRadius(), audioSquaredFalloff(),
        )
    }

    /** Whether this display can be parked warm when it leaves render distance (Local VOD on the
     *  in-process-libav decoder), rather than torn down and rebuilt from a snapshot on return. */
    fun canWarmPark(): Boolean =
        mode == PlaybackMode.LOCAL && watchParty == null && !isLive && ScreenSharePrivacy.maySnapshot(videoUrl) &&
                mediaPlayer?.canPark() == true

    /** Parks the display warm: stops rendering/advancing but keeps the decoder + audio open and frozen. */
    fun goDormant() {
        if (isDormant) return
        mediaPlayer?.park()
        dormantSinceNanos = System.nanoTime()
        isDormant = true
    }

    /** Wakes a [goDormant] display: resumes its warm session from the frozen position and renders again. */
    fun wake() {
        if (!isDormant) return
        mediaPlayer?.unpark()
        isDormant = false
    }

    /** True once a dormant display has sat parked longer than [ttlNanos] (caller then tears it down). */
    fun dormantExpired(ttlNanos: Long): Boolean = isDormant && System.nanoTime() - dormantSinceNanos > ttlNanos

    /** Monotonic timestamp when this display entered full warm park, or [Long.MAX_VALUE] when active. */
    fun dormantSinceNanos(): Long = if (isDormant) dormantSinceNanos else Long.MAX_VALUE

    /** Approximate GPU texture bytes held by this display, including staged quality-handoff textures. */
    fun estimatedTextureBytes(): Long = textureResource.estimatedBytes()

    /** Takes a one-shot replay bootstrap matching [url] and this screen's saved restore position. */
    internal fun takeReplayBootstrap(url: String): MediaPlayer.ReplayBootstrap? {
        if (mode != PlaybackMode.LOCAL || watchParty != null || savedTimeNanos <= 0L) return null
        return DisplayReplayCache.take(uuid, url, savedTimeNanos)
    }

    /** Returns true if the media player is ready and the stream supports seeking. */
    fun canSeek(): Boolean = mediaPlayer?.canSeek() == true

    /** Runs [action] once the current media player is initialized; guards against stale generations. */
    fun waitForMFInit(action: () -> Unit) = media.whenInitialized(action)

    /**
     * Resolves the current quality to a target pixel height, clamped to [qualityCap] when set
     * ([VideoQuality.Auto] falls back to [DEFAULT_QUALITY]). Broadcast caps every client at 720p.
     * Includes the distance / size steps, so a far display's texture, frame buffers and uploads shrink with
     * the stream instead of staying at the requested resolution.
     */
    private fun parseQualityOrDefault(): Int = effectiveQuality().targetHeight ?: DEFAULT_QUALITY

    private var cachedEnvironment: AcousticEnvironment = AcousticEnvironment.OPEN_AIR
    private var cachedReverb: AcousticEnvironment? = null
    private var envProbeCountdown: Int = uuid.hashCode().mod(ENV_PROBE_INTERVAL_TICKS)
    private var reverbProbeCountdown: Int = uuid.hashCode().mod(REVERB_PROBE_INTERVAL_TICKS)

    /** Called every game tick to update distance-based volume attenuation from [pos]. */
    fun tick(pos: BlockPos) {
        recoverMediaErrorIfDue()
        val hidden = privatePictureHidden()
        // Once when the picture goes stale, if any copy of it is up (texture drawn, popout, menu preview)
        if (hidden && !privatePictureWasHidden && ScreenSharePrivacy.isPrivateStream(videoUrl) &&
            (hasEverRendered || isPopoutActive || previewFrameCache != null)
        ) {
            dropPrivatePicture("live stream not delivering")
        }
        privatePictureWasHidden = hidden
        val distance = getDistanceToScreen(pos)
        refreshPrepareState(distance)
        tickPolicyGain()
        mediaPlayer?.tick(distance, audioRadius(), audioFullVolumeRadius(), audioSquaredFalloff())
        syncPlaybackSpeed() // Follows mode / watch-party changes
        timelineFollower.tick()
        queuePreloader.tick()
        restoreAudioTrackIfPending()
        restoreSubtitleTrackIfPending()
        publishStartedFlip()
        // A prepared display opens at the quality the broadcast will show, not one stepped down for distance
        if (isPopoutActive || isPrepared) {
            if (distanceQualitySteps != 0) {
                distanceQualitySteps = 0
                reloadQuality()
            }
        } else {
            updateDistanceQuality((distance / renderDistance.coerceAtLeast(1)).toFloat())
        }
        // The display's own tier (its policy) wins over the server-wide / player tier for this source only
        val ownTier = ServerOverrides.displayAcoustics(policy)
        if (ownTier != pushedAcoustics) {
            pushedAcoustics = ownTier
            ClientAudioModule.setSourceQuality(uuid, ownTier)
        }
        if ((ownTier ?: ServerOverrides.audioAcoustics) == AcousticQuality.OFF) return
        val plane = sourcePlane()
        DreamServices.registry.getOrNull(AudioAcousticsServices.ACOUSTICS)?.updateSource(
            uuid,
            SourceAcousticState(
                plane = plane,
                userVolume = volume,
                muted = muted || focusMuted || prepareMuted,
                bypassSpatial = isPopoutActive,
                acousticsEnabled = acousticsEnabled,
                environment = probeEnvironment(plane, distance),
            ),
        )
    }

    private fun probeEnvironment(plane: SourcePlane, distance: Double): AcousticEnvironment {
        val tier = ServerOverrides.audioAcoustics(policy)
        if (isPopoutActive || !acousticsEnabled || (tier != AcousticQuality.ADVANCED && tier != AcousticQuality.ULTRA)) {
            cachedEnvironment = AcousticEnvironment.OPEN_AIR
            cachedReverb = null
            envProbeCountdown = 0
            return cachedEnvironment
        }
        // Nothing to hear, nothing to trace: the raycasts run on the game thread, once per display
        if (muted || focusMuted || volume <= 0f) return cachedEnvironment
        reverbProbeCountdown--
        if (envProbeCountdown > 0) {
            envProbeCountdown--
            return cachedEnvironment
        }
        val listener = ListenerPoseTracker.currentPose(Minecraft.getInstance())
        // A display an addon has moved holds its blocks in local space, so the sound is traced there.
        val local = PlacedDisplays.localProbe(uuid, width, height, depth, facing, listener)
        val probePlane = local?.plane ?: plane
        val probeListener = local?.listener ?: listener
        val reverb = cachedReverb?.takeIf { reverbProbeCountdown > 0 }
            ?: VoxelAcousticsProbe.probeReverb(probePlane).also {
                cachedReverb = it
                reverbProbeCountdown = REVERB_PROBE_INTERVAL_TICKS
            }
        cachedEnvironment = reverb.copy(occlusion = VoxelAcousticsProbe.probeOcclusion(probePlane, probeListener))
        envProbeCountdown = when {
            distance < NEAR_PROBE_DISTANCE -> ENV_PROBE_INTERVAL_TICKS
            distance < MID_PROBE_DISTANCE -> ENV_PROBE_INTERVAL_TICKS * 2
            else -> ENV_PROBE_INTERVAL_TICKS * 4
        }
        return cachedEnvironment
    }

    /** Called after a user-initiated seek completes; emits the seek intent upstream per mode. */
    fun afterSeek() {
        if (!canSeekHere) return
        emitPlaybackIntent(PlaybackAction.SEEK)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(javaClass)
        private const val ENV_PROBE_INTERVAL_TICKS = 2
        private const val REVERB_PROBE_INTERVAL_TICKS = 40
        private const val NEAR_PROBE_DISTANCE = 16.0
        private const val MID_PROBE_DISTANCE = 48.0
        private val ERROR_RETRY_COOLDOWN = 15.seconds

        /** Initial per-display volume for newly seen displays. */
        /** Starting volume for a display the player hasn't seen: the server's `audio.startVolume` when set. */
        internal fun defaultVolume(policy: DisplayPolicy?): Float =
            policy?.audio?.startVolume?.toFloat()?.coerceIn(0f, 1f) ?: defaultVolume()

        internal fun defaultVolume(): Float {
            val serverDefault = ClientPacketManager.serverSnapshot.defaultVolume
            if (serverDefault >= 0f) return serverDefault.coerceIn(0f, MAX_SERVER_DEFAULT_VOLUME) // No to bad servers
            return ClientStateManager.config.newDisplayVolume.toFloat().coerceIn(0f, 1f)
        }

        private const val DEFAULT_QUALITY = 1080
        private val QUALITY_LADDER = intArrayOf(2160, 1440, 1080, 720, 480, 360, 240, 144)
        private val DISTANCE_STEP_THRESHOLDS = floatArrayOf(0.66f, 0.75f)
        private const val DISTANCE_STEP_HYSTERESIS = 0.05f

        private const val RESTORE_SEEK_TOLERANCE_NS = 250_000_000L
        private const val APPEAR_FADE_NANOS = 260_000_000L
        private const val WAITING_FOR_TIMELINE_TIMEOUT_NANOS = 5_000_000_000L
        private const val MAX_SERVER_DEFAULT_VOLUME = 1.0f

        /**
         * How far displays stay alive, in blocks: the client's chunk render distance, capped by the
         * server's simulation distance and by the viewer's own display distance setting, whichever is shortest.
         */
        internal fun clientRenderDistanceBlocks(ownPolicy: DisplayPolicy? = null): Int {
            val minecraft = Minecraft.getInstance()
            //? if >=1.19 {
            val render = minecraft.options.renderDistance().get()
            //?} else
            /*val render = minecraft.options.renderDistance*/
            val simulation = minecraft.level?.serverSimulationDistance?.takeIf { it > 0 } ?: render
            val own = ServerOverrides.displayDistanceChunks(ownPolicy).takeIf { it > 0 } ?: render
            return minOf(render, simulation, own) * 16
        }
    }
}
