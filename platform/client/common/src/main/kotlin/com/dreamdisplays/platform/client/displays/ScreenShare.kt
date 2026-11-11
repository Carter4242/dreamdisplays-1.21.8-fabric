package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.media.source.LiveStreamUrls
import com.dreamdisplays.core.protocol.common.hasFeature
import com.dreamdisplays.core.protocol.common.packets.ScreenShareRequest
import com.dreamdisplays.core.protocol.common.packets.ResolveAttemptReport
import com.dreamdisplays.core.protocol.common.packets.ScreenShareState
import com.dreamdisplays.core.share.ScreenShareCaptureRules
import com.dreamdisplays.core.share.ScreenShareCaptureRules.StopReason
import com.dreamdisplays.core.share.ScreenShareServerRules
import com.dreamdisplays.media.player.process.CaptureLifetime
import com.dreamdisplays.core.share.ShareTimeline
import com.dreamdisplays.media.player.process.FFmpegBinary
import com.dreamdisplays.media.player.process.MacDisplays
import com.dreamdisplays.media.player.process.ScreenCapture
import com.dreamdisplays.media.player.process.ScreenCaptureProcesses
import com.dreamdisplays.media.player.process.ShareAudio
import com.dreamdisplays.media.player.process.win.AudioPipe
import com.dreamdisplays.media.player.process.win.WinAudio
import com.dreamdisplays.media.player.process.win.WinScreens
import com.dreamdisplays.media.player.process.win.WinWindows
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.managers.ClientPacketManager
import com.dreamdisplays.platform.client.managers.DisplayLifecycleManager
import com.dreamdisplays.platform.client.ui.ClientMessage
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
import net.minecraft.client.Minecraft
import com.dreamdisplays.shared.capability.ServerFeature
import com.dreamdisplays.util.OsInfo
import net.minecraft.ChatFormatting
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory
import java.util.*

/**
 * The built-in "Share screen": asks the server for a share on a display, then runs an `FFmpeg` capture of the picked
 * monitor or window publishing to the relay URL the server hands back (see [ScreenCapture]), with the sound picked
 * ([ShareAudio]): on Windows captured here through WASAPI and piped into the same `FFmpeg`, on Linux read by `FFmpeg`
 * from PulseAudio, none on macOS. Sound capture stops with the picture's, on every path.
 *
 * Privacy first: the capture is killed outright (`destroyForcibly` on its process tree, before the server is told) on
 * Stop, on any lifecycle event that ends the share (see [ScreenShareCaptureRules.StopReason]) and on any capture
 * error; a watchdog in [tick] kills any capture process still running that should not be, and a JVM shutdown hook
 * (in [ScreenCaptureProcesses]) kills it when the game quits. A HUD badge shows while any capture runs. Everything but
 * [start] and [tick] may be called from any thread.
 */
object ScreenShare {
    private val logger = LoggerFactory.getLogger(javaClass)

    private class Running(
        val displayId: UUID,
        val target: ScreenCapture.Target,
        /** What the display showed when the share was asked for (see [ScreenShareCaptureRules.Observation]). */
        val urlBeforeShare: String?,
        /** The server asked for x264 only. */
        val softwareOnly: Boolean = false,
        settings: ScreenCapture.Settings = ScreenCapture.Settings(),
        val audioChoice: ShareAudio.Choice = ShareAudio.Choice.Off,
        /** The server's caps for this share (0: none) and its bitrate (0: the client's default). */
        val serverMaxFps: Int = 0,
        val serverMaxHeight: Int = 0,
        val serverBitrateKbps: Int = 0,
    ) {
        /** What the capture runs at: set again once the plan is known (60 fps where it measures safe). */
        @Volatile
        var settings: ScreenCapture.Settings = settings

        @Volatile
        var process: Process? = null

        /** Sound is still wanted (a run whose sound input broke FFmpeg turns it off for the retry). */
        @Volatile
        var withAudio = audioChoice != ShareAudio.Choice.Off

        /** The sound really being sent by the current run, or null for none. */
        @Volatile
        var audio: ShareAudio.Plan? = null

        @Volatile
        var capture: WinAudio.Capture? = null

        @Volatile
        var pipe: AudioPipe? = null

        @Volatile
        var stopped = false

        /** The share's URL as first seen on the display (see [ScreenShareCaptureRules.shareUrlAfter]). */
        var shareUrl: String? = null

        var lastHeartbeatMs = 0L

        /** The sharer was told this share runs without the sound they picked. */
        @Volatile
        var toldNoSound = false

        /** Sound captures started again after one ended on its own (see [restartAudio]). */
        @Volatile
        var audioRestarts = 0

        /** The relay could not be reached at once and the same plan already got its one more try. */
        @Volatile
        var relayRetried = false

        /** The share's [ScreenShareRequest.nonce], on its heartbeats and stop (see [ScreenShareServerRules.sameShare]). */
        var nonce: Long = 0L
    }

    private val lock = Any()

    @Volatile
    private var running: Running? = null

    /** What was asked for per display, until the server answers; [nonce] names that start. */
    private data class Pick(
        val target: ScreenCapture.Target, val audio: ShareAudio.Choice, val urlBefore: String?, val nonce: Long,
    )

    /** A fresh non-zero share nonce. */
    private fun newNonce(): Long {
        while (true) java.util.concurrent.ThreadLocalRandom.current().nextLong().takeIf { it != 0L }?.let { return it }
    }

    /** The server's last word: it shares screens without sound ([ScreenShareState.noSound]). The picker offers none. */
    @Volatile
    var soundOffByServer: Boolean = false
        private set

    private val pending = Collections.synchronizedMap(HashMap<UUID, Pick>())

    /** Every sound capture running in this game, so the watchdog can end one that outlived its share. */
    private val audioCaptures: MutableSet<WinAudio.Capture> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The OS as far as share sound goes. */
    val audioOs: ShareAudio.Os
        get() = when {
            OsInfo.isWindows -> ShareAudio.Os.WINDOWS
            OsInfo.isMac -> ShareAudio.Os.MAC
            else -> ShareAudio.Os.LINUX
        }

    /** This Windows can capture one app's sound, or all but the game's (build 20348+). */
    val processLoopback: Boolean
        get() = audioOs == ShareAudio.Os.WINDOWS && runCatching { WinAudio.processLoopback }.getOrDefault(false)

    private val selfPid: Long = ProcessHandle.current().pid()

    @Volatile
    private var playingApps: List<ShareAudio.Choice.App> = emptyList()

    /** Apps playing sound as of the last [refreshAudioApps] (Windows), for "Just <app>". */
    fun audioApps(): List<ShareAudio.Choice.App> = playingApps

    /** Re-reads which apps play sound, in the background (COM lives off the render thread). */
    fun refreshAudioApps() {
        if (!processLoopback) return
        thread("DreamDisplays-share-apps") {
            runCatching { WinAudio.playingApps(selfPid) }
                .onSuccess { playingApps = it }
                .onFailure { logger.debug("Share: listing apps playing sound failed: {}", it.toString()) }
        }
    }

    /** Windows that can be shared (Windows only), frontmost first. Fast enough for the render thread. */
    fun windows(): List<ScreenCapture.Window> {
        if (audioOs != ShareAudio.Os.WINDOWS) return emptyList()
        return runCatching { WinWindows.list(selfPid) }.getOrElse {
            logger.debug("Share: listing windows failed: {}", it.toString())
            emptyList()
        }
    }

    /**
     * The app owning [window], as a "Just this app" choice (its program's topmost process); null for a Store app whose
     * process could not be found (its frame host plays nothing, so "this app" would be silence), and for an app this
     * game runs under (its launcher, Steam): its process tree would carry the game's sound and voice chat.
     */
    fun windowApp(window: ScreenCapture.Window): ShareAudio.Choice.App? {
        if (runCatching { WinWindows.isFrameHost(window.pid) }.getOrDefault(false)) return null
        val root = runCatching { WinAudio.programRoot(window.pid) }.getOrDefault(window.pid)
        if (runCatching { WinAudio.includesGame(root, selfPid) }.getOrDefault(true)) return null
        return ShareAudio.Choice.App(root, window.app)
    }

    /** True when this server lets this player share, and this OS has a capture source. */
    val available: Boolean
        get() = ClientPacketManager.serverSnapshot.hasFeature(ServerFeature.SCREEN_SHARE) &&
                ScreenCapture.sourceForThisOs() != null

    /** The display this client is sharing to, or null. */
    val sharingTo: UUID? get() = running?.displayId

    /** True while this client shares to [displayId]. */
    fun isSharing(displayId: UUID): Boolean = running?.displayId == displayId

    /**
     * What may be shared. On Windows every screen on the desktop, read from DXGI each time the picker opens (left to
     * right); elsewhere, and if DXGI cannot be read, the monitors GLFW sees (primary first). Render thread only.
     */
    fun monitors(): List<ScreenCapture.Monitor> {
        if (ScreenCapture.sourceForThisOs() == ScreenCapture.Source.DDAGRAB) {
            val ffmpeg = FFmpegBinary.getPathIfResolved()
            val screens = ScreenCapture.windowsScreens(ffmpeg)?.takeIf { it.isNotEmpty() }
                ?: ScreenCapture.knownWindowsScreens(ffmpeg)?.takeIf { it.isNotEmpty() }
            if (screens != null) {
                // A screen plugged in since the last look gets its plan worked out now, before it is picked
                if (ffmpeg != null && screens.any { ScreenCapture.knownChoice(ffmpeg, it) == null }) prepare(ffmpeg, screens)
                return screens
            }
        }
        return glfwMonitors()
    }

    /**
     * The monitors GLFW sees (primary first), each with what names its screen ([ScreenCapture.Monitor.key]): on
     * macOS its CGDirectDisplayID, on Linux its output's name. Render thread only.
     */
    private fun glfwMonitors(): List<ScreenCapture.Monitor> {
        val handles = GLFW.glfwGetMonitors() ?: return []
        return (0 until handles.limit()).mapNotNull { i ->
            val h = handles.get(i)
            val mode = GLFW.glfwGetVideoMode(h) ?: return@mapNotNull null
            val x = IntArray(1)
            val y = IntArray(1)
            GLFW.glfwGetMonitorPos(h, x, y)
            val name = GLFW.glfwGetMonitorName(h).orEmpty()
            val key = when {
                OsInfo.isMac -> runCatching {
                    ScreenCapture.MAC_KEY + Integer.toUnsignedString(org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaMonitor(h))
                }.getOrDefault("")
                OsInfo.isWindows -> ""
                else -> "x11:$name"
            }
            ScreenCapture.Monitor(i, name, x[0], y[0], mode.width(), mode.height(), key = key)
        }
    }

    /**
     * Linux: the monitors as GLFW saw them on the last tick while a share was asked for or running, so the share's
     * own threads can find its screen (GLFW answers on the render thread only); null before.
     */
    @Volatile
    private var glfwScreens: List<ScreenCapture.Monitor>? = null

    private fun refreshGlfwScreens() {
        if (ScreenCapture.sourceForThisOs() != ScreenCapture.Source.X11GRAB) return
        glfwScreens = runCatching { glfwMonitors() }.getOrNull()
    }

    private val preparing = java.util.concurrent.atomic.AtomicBoolean()

    /** Works out each screen's capture source and encoder in the background (a few short `FFmpeg` runs each). */
    private fun prepare(ffmpeg: String, screens: List<ScreenCapture.Monitor>) {
        if (!preparing.compareAndSet(false, true)) return
        thread("DreamDisplays-screen-share-probe") {
            try {
                screens.forEach { runCatching { ScreenCapture.choose(ffmpeg, it) } }
            } finally {
                preparing.set(false)
            }
        }
    }

    /**
     * The server lets this player share: find the encoders (and on Windows each screen's plan) now, in the
     * background, so the picker is complete and the first share starts at once.
     */
    fun prewarm() {
        if (!available) return
        thread("DreamDisplays-screen-share-probe") {
            val ffmpeg = FFmpegBinary.getPath() ?: return@thread
            if (ScreenCapture.sourceForThisOs() == ScreenCapture.Source.DDAGRAB) {
                val screens = ScreenCapture.windowsScreens(ffmpeg) ?: ScreenCapture.probeWindowsScreens(ffmpeg)
                if (preparing.compareAndSet(false, true)) {
                    try {
                        screens.forEach { screen ->
                            runCatching {
                                val plan = ScreenCapture.choose(ffmpeg, screen)?.plan ?: return@runCatching
                                // The 60 fps encode test too (~2 s once per encoder), so the first share starts at once
                                ScreenCapture.chooseFps(ffmpeg, plan.source, plan.encoder, ScreenCapture.Settings().maxHeight, 0)
                            }
                        }
                    } finally {
                        preparing.set(false)
                    }
                }
                ScreenCapture.windowCapture(ffmpeg)
            } else {
                ScreenCapture.pickEncoder(ffmpeg)
            }
        }
    }

    /** Asks the server to let this client share [target] with [audio] to [displayId]; capture starts once it agrees. */
    fun start(displayId: UUID, target: ScreenCapture.Target, audio: ShareAudio.Choice) {
        if (!available) return
        refreshGlfwScreens()
        val nonce = newNonce()
        pending[displayId] = Pick(target, audio, DisplayRegistry.screens[displayId]?.videoUrl, nonce)
        Initializer.sendPacket(ScreenShareRequest(displayId, start = true, nonce = nonce))
    }

    /** Stops sharing: the capture is killed first, then the server ends the share. */
    fun stop() {
        pending.clear()
        running?.let { stop(it, StopReason.USER_STOP) }
    }

    /** The server's answer or a change it made (share ended, replaced, display gone). */
    fun onState(state: ScreenShareState) {
        soundOffByServer = state.noSound
        // A nonce of 0 is an older server: everything about the display is about this share, as before
        fun same(nonce: Long) = ScreenShareServerRules.sameShare(nonce, state.nonce)
        if (state.active && state.publishUrl.isNotEmpty()) {
            val current = running
            if (current != null && current.displayId == state.id && !current.stopped && same(current.nonce)) {
                pending.remove(state.id) // Already encoding to it
                // The server shares screens without sound now: a share that may be sending sound ends (its capture
                // cannot drop the sound in place); the sharer can share again, silent
                if (state.noSound && current.withAudio) fail(current, "the server turned off screen share sound")
                return
            }
            val asked = pending[state.id]?.takeIf { same(it.nonce) }
            if (asked == null) {
                // A share nobody here asked for (any more): end it on the server instead of leaving it waiting
                Initializer.sendPacket(ScreenShareRequest(state.id, start = false, nonce = state.nonce))
                return
            }
            pending.remove(state.id)
            current?.let { stop(it, StopReason.SERVER_ENDED) }
            val settings = ScreenCapture.Settings().limitedTo(state.maxFps, state.maxHeight).withServerBitrate(state.bitrateKbps)
            // The server's sound lever wins over the sharer's pick
            val audio = if (state.noSound) ShareAudio.Choice.Off else asked.audio
            if (state.noSound && asked.audio != ShareAudio.Choice.Off) {
                notify(ClientMessage.SHARE_AUDIO_SERVER_OFF.component().withStyle(ChatFormatting.YELLOW))
            }
            launch(
                Running(
                    state.id, asked.target, asked.urlBefore, state.encoder == ScreenShareState.ENCODER_SOFTWARE, settings,
                    audio, state.maxFps, state.maxHeight, state.bitrateKbps,
                ).also { it.nonce = asked.nonce },
                state.publishUrl,
            )
            return
        }
        // An "inactive" about an earlier share (the answer to its stop or to a heartbeat in flight) leaves a newer
        // start or capture on the same display alone
        val askedHere = pending[state.id]?.takeIf { same(it.nonce) }
        val runningHere = running?.takeIf { it.displayId == state.id && same(it.nonce) }
        if (askedHere == null && runningHere == null && state.nonce != 0L) return
        if (askedHere != null) pending.remove(state.id)
        runningHere?.let { stop(it, StopReason.SERVER_ENDED) }
        if (state.error.isNotEmpty()) notify(ClientMessage.SHARE_REFUSED.component(state.error).withStyle(ChatFormatting.RED))
    }

    /** Disconnected or switched server: the capture dies now; the server ends the share on its own. */
    fun onServerLeft() {
        pending.clear()
        soundOffByServer = false
        running?.let { stop(it, StopReason.SERVER_LEFT) }
        ScreenCaptureProcesses.killAll()
    }

    /** The client world changed (dimension change, proxy server switch): the capture dies and the share ends. */
    fun onLevelChanged() {
        pending.clear()
        running?.let { stop(it, StopReason.LEVEL_CHANGED) }
    }

    /** The game is quitting: kill every capture now (the JVM shutdown hook is the last resort behind this). */
    fun onShutdown() {
        pending.clear()
        running?.let { stop(it, StopReason.SHUTDOWN) }
        ScreenCaptureProcesses.killAll()
    }

    /**
     * Once per client tick: the capture watchdog (kills any capture process that outlived its share), the
     * display / dimension / range checks of [ScreenShareCaptureRules], and the heartbeat to the server.
     */
    fun tick(mc: Minecraft) {
        runCatching { tickChecks(mc) }.onFailure { e ->
            logger.warn("Screen share check failed: {}", e.toString())
            // Fail closed: a share whose checks cannot run is ended
            running?.let { stop(it, StopReason.CAPTURE_ERROR) }
            runCatching { ScreenCaptureProcesses.reap(sharing = false, current = null) }
        }
    }

    private fun tickChecks(mc: Minecraft) {
        val r = running
        if (r != null || pending.isNotEmpty()) refreshGlfwScreens()
        // Under the lock a capture is started, registered and made current under: a process started meanwhile is
        // never taken for an orphan
        synchronized(lock) {
            val now = running
            ScreenCaptureProcesses.reap(sharing = now != null && !now.stopped, current = now?.process)
        }
        // Sound capture that outlived its share (it lives in this process, not FFmpeg's)
        if ((r == null || r.stopped) && audioCaptures.isNotEmpty()) audioCaptures.toList().forEach(::stopCapture)
        if (r == null || r.stopped) return

        val screen = DisplayRegistry.screens[r.displayId]
        val known = screen != null || DisplayRegistry.unloadedScreens.containsKey(r.displayId)
        val dimension = screen?.dimensionKey?.takeIf { it.isNotEmpty() }
            ?: DisplayRegistry.unloadedScreens[r.displayId]?.dimensionKey?.takeIf { it.isNotEmpty() }
        val here = DisplayLifecycleManager.currentDimensionKey().takeIf { it.isNotEmpty() }
        val playerPos = mc.player?.blockPosition()
        val observation = ScreenShareCaptureRules.Observation(
            displayKnown = known,
            displayUrl = screen?.videoUrl,
            shareUrl = r.shareUrl,
            sameDimension = if (dimension != null && here != null) dimension == here else null,
            distanceBlocks = if (screen != null && playerPos != null) screen.getDistanceToScreen(playerPos) else null,
            urlBeforeShare = r.urlBeforeShare,
            inWorld = mc.level != null && playerPos != null,
        )
        r.shareUrl = ScreenShareCaptureRules.shareUrlAfter(observation)
        ScreenShareCaptureRules.check(observation.copy(shareUrl = r.shareUrl))?.let { reason ->
            stop(r, reason)
            return
        }

        // Sound capture that ended on its own (its device went away, or the default output moved)
        r.capture?.let { c -> c.ended?.let { why -> restartAudio(r, c, why) } }

        val now = System.currentTimeMillis()
        if (now - r.lastHeartbeatMs >= ScreenShareServerRules.HEARTBEAT_INTERVAL_MS &&
            ClientPacketManager.serverSnapshot.hasFeature(ServerFeature.SCREEN_SHARE_HEARTBEAT)
        ) {
            r.lastHeartbeatMs = now
            Initializer.sendPacket(ScreenShareRequest(r.displayId, start = false, heartbeat = true, nonce = r.nonce))
        }
    }

    private fun launch(r: Running, publishUrl: String) {
        running = r
        thread("DreamDisplays-screen-share") {
            val ffmpeg = FFmpegBinary.getPath()
            if (ffmpeg == null || ScreenCapture.sourceForThisOs() == null) {
                fail(r, "FFmpeg is not available")
                return@thread
            }
            if (!ScreenCapture.publishTargetAllowed(publishUrl)) {
                logger.warn("Refusing to share to the relay the server named: {}", LiveStreamUrls.redactCredentials(publishUrl).take(120))
                fail(r, "the server's relay address is not allowed")
                return@thread
            }
            when (val target = r.target) {
                is ScreenCapture.Window -> shareWindow(r, ffmpeg, target, publishUrl)
                is ScreenCapture.Monitor -> shareMonitor(r, ffmpeg, target, publishUrl)
            }
        }
    }

    private fun shareMonitor(r: Running, ffmpeg: String, monitor: ScreenCapture.Monitor, publishUrl: String) {
        // Only a screen that can be told apart from the others is ever captured (see ScreenCapture.sameScreen)
        if (!ScreenCapture.identifiable(monitor)) {
            fail(r, SCREEN_UNIDENTIFIED)
            return
        }
        val choice = ScreenCapture.choose(ffmpeg, monitor, r.softwareOnly)
        if (r.stopped) return
        if (choice == null) {
            fail(r, SCREEN_GONE)
            return
        }
        r.settings = settingsFor(r, ffmpeg, choice.plan)
        // The plan only came after something failed on this PC: worth knowing, though the player sees nothing
        if (choice.attempts.any { !it.ok } && plannedFallbacksReported.add(ffmpeg + choice.monitor.deviceName + choice.plan)) {
            report(r, choice, choice.plan, "", "planned_fallback", final = false,
                "screen share needed a fallback: ${choice.plan} after ${choice.attempts.count { !it.ok }} failed test(s)")
        }
        val first = runScreen(r, ffmpeg, choice, publishUrl, announce = true) ?: return
        // Died at once because of the sound input: the same plan again, without sound
        if (retryWithoutSound(r, first)) {
            val again = runScreen(r, ffmpeg, choice, publishUrl, announce = false) ?: return
            report(r, choice, choice.plan, again.output, "failed", final = true, again.reason)
            fail(r, again.reason)
            return
        }
        // Died at once: one quiet retry on the safe path (x264, and GDI capture unless only the encoder failed)
        val encoderFailed = ScreenCapture.isEncoderOpenFailure(first.output)
        val safe = if (first.ranMs < EARLY_EXIT_MS) ScreenCapture.safePlan(choice.plan, choice.monitor, encoderFailed) else null
        if (safe == null) {
            report(r, choice, choice.plan, first.output, "failed", final = true, first.reason)
            fail(r, first.reason)
            return
        }
        logger.warn("Screen share with {} ended at once ({}); retrying with {}.", choice.plan, first.reason, safe)
        r.settings = settingsFor(r, ffmpeg, safe)
        val retry = runScreen(r, ffmpeg, choice.copy(plan = safe), publishUrl, announce = false, onSettled = {
            ScreenCapture.rememberChoice(ffmpeg, monitor, r.softwareOnly, ScreenCapture.Choice(choice.monitor, safe, choice.attempts))
            report(r, choice, safe, first.output, "fallback_ok", final = false,
                "screen share ${choice.plan} failed at once (${first.reason}); ${safe} works")
        }) ?: return
        if (retry.ranMs < EARLY_EXIT_MS) {
            report(r, choice, safe, first.output + "\n--- retry with $safe ---\n" + retry.output, "fallback_failed",
                final = true, retry.reason)
        } else {
            report(r, choice, safe, retry.output, "failed", final = true, retry.reason)
        }
        fail(r, retry.reason)
    }

    /**
     * Captures [choice]'s screen with its plan until a run ends in a way that says something about the plan; null
     * when the share was settled meanwhile (stopped, or ended with the player told). Every launch, the first and
     * every retry, goes to the screen as it is found right then (see [awaitScreen]), never to an index or rectangle
     * remembered from before. On the way: a relay that could not be reached at once gets one more try (see
     * [relayRetry]), and a capture that lost its screen (Desktop Duplication "access lost": a UAC prompt, a mode
     * change, a hot-plug; or a rectangle capture whose screen moved, see [screenWatch]) is started again on that same
     * screen, every second for up to [LOST_RECOVERY_MS] (viewers see the share's "no picture" until it is back).
     */
    private fun runScreen(
        r: Running, ffmpeg: String, choice: ScreenCapture.Choice, publishUrl: String, announce: Boolean,
        onSettled: (() -> Unit)? = null,
    ): Ended? {
        var current = awaitScreen(r, ffmpeg, choice, System.currentTimeMillis() + START_WAIT_MS, null) ?: return null
        var ended = runShare(r, ffmpeg, current.monitor, current.plan, publishUrl, announce, onSettled) ?: return null
        // While set, the last run was a recapture: one that dies at once is tried again until then
        var recoverUntil = 0L
        while (!r.stopped) {
            // A capture killed because its screen moved says nothing about the relay
            val relay = if (ended.screenChanged) null else ScreenCapture.relayFailure(ended.output)
            if (relay != null) {
                if (!relayRetry(r, current.plan, relay, ended)) return null
                current = awaitScreen(r, ffmpeg, current, System.currentTimeMillis() + START_WAIT_MS, ended) ?: return null
            } else if (lostScreen(ended) || (recoverUntil != 0L && ended.ranMs < EARLY_EXIT_MS)) {
                if (recoverUntil == 0L) {
                    logger.warn("Screen capture lost its screen ({}); capturing it again.", ended.reason)
                    recoverUntil = System.currentTimeMillis() + LOST_RECOVERY_MS
                } else if (System.currentTimeMillis() >= recoverUntil) {
                    report(r, current, current.plan, ended.output, "lost", final = true, "screen share lost its screen: ${ended.reason}")
                    fail(r, "the screen could not be captured again")
                    return null
                }
                runCatching { Thread.sleep(1_000L) }
                current = awaitScreen(r, ffmpeg, current, recoverUntil, ended) ?: return null
            } else {
                return ended
            }
            if (r.stopped) return null
            ended = runShare(r, ffmpeg, current.monitor, current.plan, publishUrl, announce = false) ?: return null
            if (ended.ranMs >= EARLY_EXIT_MS) recoverUntil = 0L
        }
        return null
    }

    /** [ended] lost its screen: Desktop Duplication's "access lost" after running, or [screenWatch] saw it move. */
    private fun lostScreen(ended: Ended): Boolean =
        ended.screenChanged || (ended.ranMs >= EARLY_EXIT_MS && ScreenCapture.isCaptureLost(ended.output))

    /**
     * [choice] bound to its screen as it is now, once that screen is positively found (see [locateNow]); waits for
     * it until [deadline] (a secure desktop refuses Desktop Duplication meanwhile). Null when the share was settled:
     * stopped, or ended because the screen is gone ([GONE_CHECKS] looks in a row) or could not be found in time.
     * [last] is the run before, for the report.
     */
    private fun awaitScreen(r: Running, ffmpeg: String, choice: ScreenCapture.Choice, deadline: Long, last: Ended?): ScreenCapture.Choice? {
        var gone = 0
        while (true) {
            if (r.stopped) return null
            when (val found = locateNow(ffmpeg, choice)) {
                is Found.Ready -> return found.choice
                Found.Gone -> if (++gone >= GONE_CHECKS) {
                    logger.warn("The shared screen {} is gone; ending the share.", describe(choice.monitor))
                    report(r, choice, choice.plan, last?.output.orEmpty(), "lost", final = true, "the shared screen is gone")
                    fail(r, SCREEN_GONE)
                    return null
                }
                Found.NotNow -> gone = 0
            }
            if (System.currentTimeMillis() >= deadline) {
                report(r, choice, choice.plan, last?.output.orEmpty(), "lost", final = true,
                    "screen share could not find its screen again" + (last?.let { ": ${it.reason}" } ?: ""))
                fail(r, if (last == null) SCREEN_UNIDENTIFIED else "the screen could not be captured again")
                return null
            }
            runCatching { Thread.sleep(1_000L) }
        }
    }

    /** Where a share's screen is right now (see [locateNow]). */
    private sealed interface Found {
        /** Positively found; [choice] reaches it now. */
        data class Ready(val choice: ScreenCapture.Choice) : Found

        /** The screen list was read and the screen is not on it. */
        data object Gone : Found

        /** Not now: the list could not be read, the plan's adapter does not reach it, or Desktop Duplication refused it. */
        data object NotNow : Found
    }

    /**
     * [choice]'s screen as the OS lists it right now, bound for its plan: Windows by its screen name and monitors
     * (Desktop Duplication on an adapter `FFmpeg` might number differently is opened once to confirm it), macOS by
     * its display id (avfoundation's index for it right now), Linux by its output name (its rectangle right now).
     */
    private fun locateNow(ffmpeg: String, choice: ScreenCapture.Choice): Found {
        val m = choice.monitor
        return when (choice.plan.source) {
            ScreenCapture.Source.DDAGRAB, ScreenCapture.Source.GDIGRAB -> when (val l = ScreenCapture.locateOnWindows(m)) {
                ScreenCapture.Located.Unknown -> Found.NotNow
                ScreenCapture.Located.Gone -> Found.Gone
                is ScreenCapture.Located.Here -> {
                    val bound = ScreenCapture.rebind(choice, l.ways)
                    when {
                        // A rectangle copy needs no adapter: any way to the screen gives its rectangle now
                        choice.plan.source == ScreenCapture.Source.GDIGRAB ->
                            Found.Ready(bound ?: choice.copy(monitor = l.ways.first()))
                        bound == null -> Found.NotNow
                        bound.monitor.byVendor || ScreenCapture.confirm(ffmpeg, bound.monitor) -> Found.Ready(bound)
                        else -> Found.NotNow
                    }
                }
            }

            ScreenCapture.Source.AVFOUNDATION -> {
                val ids = MacDisplays.activeIds() ?: return Found.NotNow
                val index = ScreenCapture.avfoundationIndex(m, ids) ?: return Found.Gone
                Found.Ready(choice.copy(monitor = m.copy(index = index)))
            }

            ScreenCapture.Source.X11GRAB -> when (val l = ScreenCapture.locate(m, glfwScreens)) {
                ScreenCapture.Located.Unknown -> Found.NotNow
                ScreenCapture.Located.Gone -> Found.Gone
                is ScreenCapture.Located.Here -> Found.Ready(choice.copy(monitor = l.ways.first()))
            }

            ScreenCapture.Source.GFXCAPTURE -> Found.NotNow
        }
    }

    /**
     * Whether a run that ended on the relay is tried once more: a refused publish (the relay's per-share key, a share
     * it already ended) ends the share saying so, and so does one that lost the relay after running; one that could
     * not reach it at once gets one more try, after a second. The relay says nothing about the plan, so it never
     * leads to a fallback or a remembered plan.
     */
    private fun relayRetry(r: Running, plan: ScreenCapture.Plan, failure: ScreenCapture.RelayFailure, ended: Ended): Boolean {
        when (failure) {
            ScreenCapture.RelayFailure.REFUSED -> {
                logger.warn("The relay refused the screen share: {}", ended.reason)
                fail(r, "the relay refused the stream")
                return false
            }
            ScreenCapture.RelayFailure.UNREACHABLE -> {
                if (ended.ranMs >= EARLY_EXIT_MS || r.relayRetried) {
                    logger.warn("Screen share lost the relay: {}", ended.reason)
                    fail(r, "lost the connection to the relay")
                    return false
                }
                r.relayRetried = true
                logger.warn("Screen share could not reach the relay ({}); trying {} once more.", ended.reason, plan)
                runCatching { Thread.sleep(1_000L) }
                return !r.stopped
            }
        }
    }

    /**
     * One window: Windows Graphics Capture (else GDI) with the best encoder; a quick death retries once on x264, or
     * without sound when the sound input was what broke.
     */
    private fun shareWindow(r: Running, ffmpeg: String, window: ScreenCapture.Window, publishUrl: String) {
        if (!WinWindows.exists(window)) {
            fail(r, "that window is closed")
            return
        }
        WinWindows.unminimize(window)
        val plan = ScreenCapture.windowPlan(ffmpeg, r.softwareOnly)
        r.settings = settingsFor(r, ffmpeg, plan)
        if (r.stopped) return
        val started = runShare(r, ffmpeg, window, plan, publishUrl, announce = true) ?: return
        if (r.stopped) return
        val first = afterRelay(r, ffmpeg, window, plan, publishUrl, started) ?: return
        if (windowClosed(r, window)) return
        val retryPlan = when {
            retryWithoutSound(r, first) -> plan
            first.ranMs < EARLY_EXIT_MS && plan.encoder != ScreenCapture.Encoder.X264 ->
                ScreenCapture.Plan(plan.source, ScreenCapture.Encoder.X264)
            else -> null
        }
        if (retryPlan == null) {
            fail(r, first.reason)
            return
        }
        logger.warn("Window share with {} ended at once ({}); retrying with {}.", plan, first.reason, retryPlan)
        r.settings = settingsFor(r, ffmpeg, retryPlan)
        val retry = runShare(r, ffmpeg, window, retryPlan, publishUrl, announce = false) ?: return
        if (r.stopped || windowClosed(r, window)) return
        fail(r, retry.reason)
    }

    /** True (and the share ended, saying so) when the shared [window] is gone: its capture ends with it. */
    private fun windowClosed(r: Running, window: ScreenCapture.Window): Boolean {
        if (WinWindows.exists(window)) return false
        fail(r, "the shared window was closed")
        return true
    }

    /** [ended] as the run to judge a window's plan by, once the relay is ruled out (see [relayRetry]); null when that settled the share. */
    private fun afterRelay(
        r: Running, ffmpeg: String, target: ScreenCapture.Target, plan: ScreenCapture.Plan, publishUrl: String, ended: Ended,
    ): Ended? {
        var last = ended
        while (true) {
            val failure = ScreenCapture.relayFailure(last.output) ?: return last
            if (!relayRetry(r, plan, failure, last)) return null
            last = runShare(r, ffmpeg, target, plan, publishUrl, announce = false) ?: return null
            if (r.stopped) return null
        }
    }

    private const val LOST_RECOVERY_MS = 60_000L

    /** How long a launch waits for its screen to be found (a secure desktop may be up). */
    private const val START_WAIT_MS = 10_000L

    /** Looks a second apart that must all miss the screen before the share ends as gone. */
    private const val GONE_CHECKS = 3

    private const val SCREEN_GONE = "the shared screen was disconnected or replaced"
    private const val SCREEN_UNIDENTIFIED = "the screen could not be identified (try sharing a window)"

    /**
     * What [r] captures at with [plan]: 60 fps where the plan's encoder measured fast enough and the server allows it
     * (else 30), the bitrate default for that rate (or the server's), within the server's caps.
     */
    private fun settingsFor(r: Running, ffmpeg: String, plan: ScreenCapture.Plan): ScreenCapture.Settings {
        val fps = runCatching { ScreenCapture.chooseFps(ffmpeg, plan.source, plan.encoder, r.settings.maxHeight, r.serverMaxFps) }
            .getOrDefault(30)
        return ScreenCapture.Settings.forFps(fps).limitedTo(r.serverMaxFps, r.serverMaxHeight).withServerBitrate(r.serverBitrateKbps)
    }

    /** True (and sound turned off for the rest of [r]) when the run with sound died at once on its sound input. */
    private fun retryWithoutSound(r: Running, ended: Ended): Boolean {
        if (r.audio == null || ended.ranMs >= EARLY_EXIT_MS || !SOUND_INPUT_FAILURE.containsMatchIn(ended.output)) return false
        logger.warn("Screen share sound ({}) broke FFmpeg at once; sharing without sound: {}", r.audio, ended.reason)
        r.withAudio = false
        return true
    }

    private val SOUND_INPUT_FAILURE = Regex("""(?i)/wav @|/pulse @|dreamdisplays-share-|@DEFAULT_MONITOR@|pulse""")

    private const val EARLY_EXIT_MS = 5_000L

    /** Plans already reported as needing a fallback this session. */
    private val plannedFallbacksReported = Collections.synchronizedSet(HashSet<String>())

    /** One run's end; [screenChanged]: [screenWatch] killed it because its target was no longer what was picked. */
    private class Ended(val code: Int, val output: String, val reason: String, val ranMs: Long, val screenChanged: Boolean = false)

    /**
     * Runs one capture of [monitor] with [plan] until it ends or the share stops; null when it never started (the
     * player was told) or was stopped. [onSettled] runs once the capture has run for [EARLY_EXIT_MS].
     */
    private fun runShare(
        r: Running, ffmpeg: String, target: ScreenCapture.Target, plan: ScreenCapture.Plan, publishUrl: String,
        announce: Boolean, onSettled: (() -> Unit)? = null,
    ): Ended? {
        // One instant on both clocks: the wall clock FFmpeg stamps by, System.nanoTime the sound pipe and the
        // server's clock (via the clock sync) run on. The stream's timestamps count from a server-clock epoch, so each
        // one says when its frame was captured (see ShareTimeline); without the clock sync, from now.
        val wallMicros = ScreenCapture.wallClockMicros()
        val baseNanos = System.nanoTime()
        val baseMicros = ShareClock.offsetNanos?.let { offset ->
            val server = ShareTimeline.serverMicros(baseNanos, offset)
            wallMicros - (server - ShareTimeline.epoch(server))
        } ?: wallMicros
        if (!r.withAudio) r.audio = null
        val input = if (r.withAudio) startAudio(r, baseNanos) else null
        val windowCapture = (target as? ScreenCapture.Window)?.let { ScreenCapture.windowCapture(ffmpeg) }
            ?: ScreenCapture.WindowCapture(ScreenCapture.Source.GFXCAPTURE, false)
        val args = ScreenCapture.args(
            ffmpeg, plan.source, target, plan.encoder, publishUrl, r.settings, input, baseMicros, windowCapture,
            audioStartMicros = wallMicros,
        )
        val proc = synchronized(lock) {
            // Started and registered under the lock, so a stop racing this (first run or retry) either sees the
            // process or makes this skip the start
            if (r.stopped) {
                stopAudio(r)
                return null
            }
            // Under CaptureLifetime: the capture dies with the game even when the JVM is killed outright
            runCatching { ProcessBuilder(CaptureLifetime.command(args)).redirectErrorStream(true).start() }.getOrElse {
                stopAudio(r)
                fail(r, LiveStreamUrls.redactCredentials(it.message ?: "could not start FFmpeg"))
                return null
            }.also { p ->
                ScreenCaptureProcesses.register(p)
                r.process = p
            }
        }
        r.pipe?.start()
        val started = System.currentTimeMillis()
        if (announce) notify(ClientMessage.SHARE_STARTED.component().withStyle(ChatFormatting.GREEN))
        logger.info("Sharing {} with {} at {} fps, up to {}p, sound: {}.", describe(target), plan, r.settings.fps,
            r.settings.maxHeight, r.audio ?: "none")
        val changed = java.util.concurrent.atomic.AtomicBoolean()
        screenWatch(target, plan.source)?.let { check -> watch(r, proc, check, changed) }
        if (onSettled != null) thread("DreamDisplays-screen-share-settle") {
            runCatching { Thread.sleep(EARLY_EXIT_MS) }
            if (proc.isAlive && !r.stopped) onSettled()
        }
        val tail = ArrayDeque<String>()
        runCatching {
            proc.inputStream.bufferedReader().forEachLine { line ->
                if (tail.size >= 15) tail.removeFirst()
                // FFmpeg names the publish URL in its errors; its credentials (and stream id) never reach a log or the chat
                tail.addLast(scrubUrls(line))
            }
        }
        val code = runCatching { proc.waitFor() }.getOrDefault(-1)
        stopAudio(r)
        val ranMs = System.currentTimeMillis() - started
        if (r.stopped) return null
        val output = tail.joinToString("\n")
        logger.warn("Screen capture ({}) ended after {} ms (exit {}): {}", plan, ranMs, code, tail.toList().takeLast(6).joinToString(" | ").take(400))
        if (changed.get()) logger.warn("Screen capture ({}) stopped: {} is no longer where it was captured.", plan, describe(target))
        return Ended(code, output, tail.lastOrNull()?.take(120) ?: "capture ended (exit $code)", ranMs, changed.get())
    }

    /**
     * The check that [target], captured with [source], is still what the capture holds (true), is not (false), or
     * cannot be told (null); none where the capture follows its target itself (Desktop Duplication fails with
     * "access lost" on any change, Windows Graphics Capture ends with its window). A rectangle capture (GDI, x11grab)
     * copies fixed desktop coordinates whatever screen is there now; avfoundation's display may go away.
     */
    private fun screenWatch(target: ScreenCapture.Target, source: ScreenCapture.Source): (() -> Boolean?)? {
        val m = target as? ScreenCapture.Monitor ?: return null
        val check: () -> Boolean? = when (source) {
            ScreenCapture.Source.GDIGRAB -> {
                // Compared with the screen at that rectangle as the same API saw it at the start (one coordinate space)
                val baseline = runCatching { WinScreens.screenAt(m.x, m.y, m.width, m.height) }.getOrNull()
                fun now(): Boolean = ScreenCapture.rectStillShows(m, baseline, WinScreens.screenAt(m.x, m.y, m.width, m.height))
                ::now
            }
            ScreenCapture.Source.X11GRAB -> fun(): Boolean? = glfwScreens?.let { ScreenCapture.stillAt(m, it) }
            ScreenCapture.Source.AVFOUNDATION ->
                fun(): Boolean? = MacDisplays.activeIds()?.let { ScreenCapture.avfoundationIndex(m, it) != null }
            else -> return null
        }
        return check
    }

    /**
     * Kills [proc] (and sets [changed]) as soon as [check] says its target is no longer what was picked, or cannot
     * be told [WATCH_UNKNOWN_LIMIT] times in a row: a moved screen must not stream for longer than a look or two.
     */
    private fun watch(r: Running, proc: Process, check: () -> Boolean?, changed: java.util.concurrent.atomic.AtomicBoolean) =
        thread("DreamDisplays-screen-share-watch") {
            var unknown = 0
            while (proc.isAlive && !r.stopped) {
                val ok = runCatching(check).getOrNull()
                if (ok == true) {
                    unknown = 0
                } else if (ok == false || ++unknown >= WATCH_UNKNOWN_LIMIT) {
                    changed.set(true)
                    ScreenCaptureProcesses.kill(proc)
                    return@thread
                }
                runCatching { Thread.sleep(WATCH_INTERVAL_MS) }
            }
        }

    private const val WATCH_INTERVAL_MS = 50L
    private const val WATCH_UNKNOWN_LIMIT = 3

    /** Starts capturing [r]'s sound per its plans, best first; null (picture only) when none works. */
    private fun startAudio(r: Running, baseNanos: Long): ShareAudio.Input? {
        for (plan in ShareAudio.plans(r.audioChoice, audioOs, selfPid)) {
            when (plan) {
                is ShareAudio.Plan.Pulse -> {
                    r.audio = plan
                    return ShareAudio.Input.Pulse(plan.device)
                }

                else -> {
                    var capture: WinAudio.Capture? = null
                    try {
                        capture = WinAudio.start(plan) { nanos, data -> r.pipe?.offer(nanos, data) }
                        audioCaptures += capture
                        val pipe = AudioPipe(capture.format, baseNanos)
                        r.pipe = pipe
                        r.capture = capture
                        r.audio = plan
                        return ShareAudio.Input.Pipe(pipe.path, capture.format)
                    } catch (t: Throwable) {
                        capture?.let(::stopCapture)
                        logger.warn("Share sound {} unavailable: {}", plan, t.toString())
                    }
                }
            }
        }
        r.audio = null
        // Sound was picked but none could be captured: the badge says "no sound", and the sharer is told once
        if (ShareAudio.plans(r.audioChoice, audioOs, selfPid).isNotEmpty() && !r.toldNoSound) {
            r.toldNoSound = true
            notify(ClientMessage.SHARE_AUDIO_UNAVAILABLE.component().withStyle(ChatFormatting.YELLOW))
        }
        return null
    }

    /**
     * [dead] (a share's sound capture) ended on its own for [why]: it is started again on the device as it is now,
     * feeding the same pipe, so the stream's sound carries on; when that cannot be done (another sample format, no
     * device, too many tries), the share goes on without sound, the badge says so and the sharer is told.
     */
    private fun restartAudio(r: Running, dead: WinAudio.Capture, why: String) {
        r.capture = null
        stopCapture(dead)
        val pipe = r.pipe
        if (pipe == null || r.audioRestarts >= MAX_AUDIO_RESTARTS) {
            soundLost(r, why)
            return
        }
        r.audioRestarts++
        logger.warn("Share sound capture ended ({}); capturing it again.", why)
        thread("DreamDisplays-share-audio-restart") {
            val fresh = runCatching { WinAudio.start(dead.plan) { nanos, data -> r.pipe?.offer(nanos, data) } }
                .onFailure { logger.warn("Share sound could not be captured again: {}", it.toString()) }
                .getOrNull()
            if (fresh != null) {
                audioCaptures += fresh
                val kept = synchronized(lock) {
                    (fresh.format == dead.format && !r.stopped && r.pipe === pipe).also { if (it) r.capture = fresh }
                }
                if (kept) return@thread
                stopCapture(fresh)
            }
            // Stopped, or the capture was relaunched with sound of its own meanwhile: nothing was lost
            if (r.stopped || r.pipe !== pipe) return@thread
            soundLost(r, why)
        }
    }

    private fun soundLost(r: Running, why: String) {
        if (r.stopped) return
        logger.warn("Share sound lost for the rest of this run: {}", why)
        r.audio = null
        notify(ClientMessage.SHARE_AUDIO_LOST.component().withStyle(ChatFormatting.YELLOW))
    }

    private const val MAX_AUDIO_RESTARTS = 5

    private fun stopCapture(capture: WinAudio.Capture) {
        capture.stop()
        audioCaptures -= capture
    }

    /** Ends [r]'s sound capture and pipe (idempotent). */
    private fun stopAudio(r: Running) {
        r.capture?.let(::stopCapture)
        r.capture = null
        r.pipe?.close()
        r.pipe = null
    }

    private fun describe(target: ScreenCapture.Target): String = when (target) {
        is ScreenCapture.Monitor -> "${target.name} (${target.deviceName.ifEmpty { "#${target.index}" }})"
        is ScreenCapture.Window -> "a window of ${target.app}"
    }

    private val RELAY_URL = Regex("""(?i)\b(?:srt|rtmps?|rtsp)://\S+""")

    private fun scrubUrls(line: String): String = RELAY_URL.replace(LiveStreamUrls.redactCredentials(line), "<relay-url>")

    /** Sends a `share` client error report (servers that take them): what was tried, what ran, on what hardware. */
    private fun report(
        r: Running, choice: ScreenCapture.Choice, ran: ScreenCapture.Plan, stderr: String, action: String,
        final: Boolean, message: String,
    ) {
        runCatching {
            val m = choice.monitor
            val metrics = LinkedHashMap<String, String>()
            metrics["screen"] = "${m.name} ${m.deviceName} ${m.x},${m.y} ${m.width}x${m.height}".trim()
            metrics["adapter"] = listOf(if (m.adapter >= 0) "#${m.adapter}" else "default", m.adapterPciId, m.adapterName,
                if (m.byVendor) "by vendor" else "").filter { it.isNotEmpty() }.joinToString(" ")
            metrics["planned"] = choice.plan.toString()
            metrics["ran"] = ran.toString()
            metrics["limits"] = "${r.settings.fps}fps ${r.settings.maxHeight}p ${r.settings.bitrateKbps}k" +
                    if (r.softwareOnly) " software-only" else ""
            ScreenCapture.gpuNames().forEachIndexed { i, g -> metrics["gpu$i"] = g }
            ScreenCapture.screensSummary().take(8).forEachIndexed { i, s -> metrics["dxgi$i"] = s }
            val attempts = choice.attempts.map {
                ResolveAttemptReport(source = it.plan.toString(), client = it.adapter, ok = it.ok, error = it.error,
                    elapsedMs = it.elapsedMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            }
            ClientErrorReporter.reportShare(r.displayId, message, "share", action, final, stderr, attempts, metrics)
        }.onFailure { logger.debug("Could not build a screen share report.", it) }
    }

    /** Capture failed or died on its own: kill what is left, end the share on the server, tell the player. */
    private fun fail(r: Running, why: String) {
        if (r.stopped) return
        stop(r, StopReason.CAPTURE_ERROR)
        notify(ClientMessage.SHARE_FAILED.component(why).withStyle(ChatFormatting.RED))
    }

    /**
     * Ends [r] for [reason]: the capture process tree is force-killed first, on this thread, then the server is told
     * (when [ScreenShareCaptureRules.tellServer]); a background thread confirms the processes are gone.
     */
    private fun stop(r: Running, reason: StopReason) {
        val proc = synchronized(lock) {
            if (r.stopped) return
            r.stopped = true
            if (running === r) running = null
            r.process
        }
        val handles = proc?.let(ScreenCaptureProcesses::kill)
        stopAudio(r)
        if (ScreenShareCaptureRules.tellServer(reason)) {
            runCatching { Initializer.sendPacket(ScreenShareRequest(r.displayId, start = false, nonce = r.nonce)) }
        }
        logger.info("Screen share to display {} stopped ({}).", r.displayId, reason.name.lowercase(Locale.ROOT))
        if (proc != null && handles != null && reason != StopReason.SHUTDOWN) {
            thread("DreamDisplays-screen-share-stop") {
                if (!ScreenCaptureProcesses.awaitGone(handles)) {
                    logger.error("Screen capture process {} survived a forced kill; retrying.", proc.pid())
                    ScreenCaptureProcesses.killAndVerify(proc)
                }
            }
        }
    }

    private fun thread(name: String, body: () -> Unit) =
        Thread(body, name).apply { isDaemon = true }.start()

    private fun notify(message: net.minecraft.network.chat.Component) = DisplayQueues.notifyLocal(message)

    /**
     * The "Sharing your screen" badge at the top of the HUD (and under any menu), while a share runs or any capture
     * process this game started is still alive.
     */
    fun renderIndicator(mc: Minecraft, g: GuiGraphicsCompat) {
        val r = running
        if (r == null && ScreenCaptureProcesses.alive().isEmpty()) return
        val font = mc.font
        val sound = when {
            r == null -> ""
            // The server shares screens without sound
            r.audioChoice == ShareAudio.Choice.Off && soundOffByServer -> " · " + ClientMessage.SHARE_AUDIO_SERVER_OFF_BADGE.text()
            r.audio != null -> " · " + ClientMessage.SHARE_AUDIO_ON.text()
            else -> " · " + ClientMessage.SHARE_AUDIO_OFF.text()
        }
        val text = "● " + ClientMessage.SHARE_INDICATOR.text() + sound
        val w = font.width(text) + 8
        val x = (mc.window.guiScaledWidth - w) / 2
        val y = 4
        g.fill(x, y, x + w, y + font.lineHeight + 5, 0xA0000000.toInt())
        // The dot pulses so the pill reads as live, not as a stuck label
        val pulse = ((System.currentTimeMillis() / 600L) % 2L == 0L)
        g.drawText(font, text, x + 4, y + 3, if (pulse) 0xFFFF5050.toInt() else 0xFFFFA0A0.toInt(), false)
    }
}
