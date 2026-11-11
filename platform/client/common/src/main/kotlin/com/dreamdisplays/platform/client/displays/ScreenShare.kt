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
import com.dreamdisplays.media.player.process.FFmpegBinary
import com.dreamdisplays.media.player.process.ScreenCapture
import com.dreamdisplays.media.player.process.ScreenCaptureProcesses
import com.dreamdisplays.media.player.process.ShareAudio
import com.dreamdisplays.media.player.process.win.AudioPipe
import com.dreamdisplays.media.player.process.win.WinAudio
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
        val settings: ScreenCapture.Settings = ScreenCapture.Settings(),
        val audioChoice: ShareAudio.Choice = ShareAudio.Choice.Off,
    ) {
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
    }

    private val lock = Any()

    @Volatile
    private var running: Running? = null

    /** What was asked for per display, until the server answers. */
    private data class Pick(val target: ScreenCapture.Target, val audio: ShareAudio.Choice, val urlBefore: String?)

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

    /** The app owning [window], as a "Just this app" choice (its program's topmost process). */
    fun windowApp(window: ScreenCapture.Window): ShareAudio.Choice.App =
        ShareAudio.Choice.App(runCatching { WinAudio.programRoot(window.pid) }.getOrDefault(window.pid), window.app)

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
        val handles = GLFW.glfwGetMonitors() ?: return []
        return (0 until handles.limit()).mapNotNull { i ->
            val h = handles.get(i)
            val mode = GLFW.glfwGetVideoMode(h) ?: return@mapNotNull null
            val x = IntArray(1)
            val y = IntArray(1)
            GLFW.glfwGetMonitorPos(h, x, y)
            ScreenCapture.Monitor(i, GLFW.glfwGetMonitorName(h).orEmpty(), x[0], y[0], mode.width(), mode.height())
        }
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
                        screens.forEach { runCatching { ScreenCapture.choose(ffmpeg, it) } }
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
        pending[displayId] = Pick(target, audio, DisplayRegistry.screens[displayId]?.videoUrl)
        Initializer.sendPacket(ScreenShareRequest(displayId, start = true))
    }

    /** Stops sharing: the capture is killed first, then the server ends the share. */
    fun stop() {
        pending.clear()
        running?.let { stop(it, StopReason.USER_STOP) }
    }

    /** The server's answer or a change it made (share ended, replaced, display gone). */
    fun onState(state: ScreenShareState) {
        if (state.active && state.publishUrl.isNotEmpty()) {
            val current = running
            if (current != null && current.displayId == state.id && !current.stopped) {
                pending.remove(state.id) // Already encoding to it
                return
            }
            val asked = pending.remove(state.id)
            if (asked == null) {
                // A share nobody here asked for (any more): end it on the server instead of leaving it waiting
                Initializer.sendPacket(ScreenShareRequest(state.id, start = false))
                return
            }
            current?.let { stop(it, StopReason.SERVER_ENDED) }
            val settings = ScreenCapture.Settings().limitedTo(state.maxFps, state.maxHeight)
            launch(
                Running(state.id, asked.target, asked.urlBefore, state.encoder == ScreenShareState.ENCODER_SOFTWARE, settings, asked.audio),
                state.publishUrl,
            )
            return
        }
        pending.remove(state.id)
        running?.takeIf { it.displayId == state.id }?.let { stop(it, StopReason.SERVER_ENDED) }
        if (state.error.isNotEmpty()) notify(ClientMessage.SHARE_REFUSED.component(state.error).withStyle(ChatFormatting.RED))
    }

    /** Disconnected or switched server: the capture dies now; the server ends the share on its own. */
    fun onServerLeft() {
        pending.clear()
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
        ScreenCaptureProcesses.reap(sharing = r != null && !r.stopped, current = r?.process)
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

        val now = System.currentTimeMillis()
        if (now - r.lastHeartbeatMs >= ScreenShareServerRules.HEARTBEAT_INTERVAL_MS &&
            ClientPacketManager.serverSnapshot.hasFeature(ServerFeature.SCREEN_SHARE_HEARTBEAT)
        ) {
            r.lastHeartbeatMs = now
            Initializer.sendPacket(ScreenShareRequest(r.displayId, start = false, heartbeat = true))
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
        val choice = ScreenCapture.choose(ffmpeg, monitor, r.softwareOnly)
        if (r.stopped) return
        // The plan only came after something failed on this PC: worth knowing, though the player sees nothing
        if (choice.attempts.any { !it.ok } && plannedFallbacksReported.add(ffmpeg + choice.monitor.deviceName + choice.plan)) {
            report(r, choice, choice.plan, "", "planned_fallback", final = false,
                "screen share needed a fallback: ${choice.plan} after ${choice.attempts.count { !it.ok }} failed test(s)")
        }
        val first = runShare(r, ffmpeg, choice.monitor, choice.plan, publishUrl, announce = true) ?: return
        if (r.stopped) return
        // Died at once because of the sound input: the same plan again, without sound
        if (retryWithoutSound(r, first)) {
            val again = runShare(r, ffmpeg, choice.monitor, choice.plan, publishUrl, announce = false) ?: return
            if (r.stopped) return
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
        val retry = runShare(r, ffmpeg, choice.monitor, safe, publishUrl, announce = false, onSettled = {
            ScreenCapture.rememberChoice(ffmpeg, monitor, r.softwareOnly, ScreenCapture.Choice(choice.monitor, safe, choice.attempts))
            report(r, choice, safe, first.output, "fallback_ok", final = false,
                "screen share ${choice.plan} failed at once (${first.reason}); ${safe} works")
        }) ?: return
        if (r.stopped) return
        if (retry.ranMs < EARLY_EXIT_MS) {
            report(r, choice, safe, first.output + "\n--- retry with $safe ---\n" + retry.output, "fallback_failed",
                final = true, retry.reason)
        } else {
            report(r, choice, safe, retry.output, "failed", final = true, retry.reason)
        }
        fail(r, retry.reason)
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
        if (r.stopped) return
        val first = runShare(r, ffmpeg, window, plan, publishUrl, announce = true) ?: return
        if (r.stopped) return
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
        val retry = runShare(r, ffmpeg, window, retryPlan, publishUrl, announce = false) ?: return
        if (r.stopped) return
        fail(r, retry.reason)
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

    private class Ended(val code: Int, val output: String, val reason: String, val ranMs: Long)

    /**
     * Runs one capture of [monitor] with [plan] until it ends or the share stops; null when it never started (the
     * player was told) or was stopped. [onSettled] runs once the capture has run for [EARLY_EXIT_MS].
     */
    private fun runShare(
        r: Running, ffmpeg: String, target: ScreenCapture.Target, plan: ScreenCapture.Plan, publishUrl: String,
        announce: Boolean, onSettled: (() -> Unit)? = null,
    ): Ended? {
        val baseMicros = ScreenCapture.wallClockMicros()
        val baseNanos = System.nanoTime()
        if (!r.withAudio) r.audio = null
        val input = if (r.withAudio) startAudio(r, baseNanos) else null
        val windowCapture = (target as? ScreenCapture.Window)?.let { ScreenCapture.windowCapture(ffmpeg) }
            ?: ScreenCapture.WindowCapture(ScreenCapture.Source.GFXCAPTURE, false)
        val args = ScreenCapture.args(
            ffmpeg, plan.source, target, plan.encoder, publishUrl, r.settings, input, baseMicros, windowCapture,
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
        return Ended(code, output, tail.lastOrNull()?.take(120) ?: "capture ended (exit $code)", ranMs)
    }

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
        return null
    }

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
            runCatching { Initializer.sendPacket(ScreenShareRequest(r.displayId, start = false)) }
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
