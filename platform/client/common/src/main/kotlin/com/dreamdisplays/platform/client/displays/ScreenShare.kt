package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.media.source.LiveStreamUrls
import com.dreamdisplays.core.protocol.common.hasFeature
import com.dreamdisplays.core.protocol.common.packets.ScreenShareRequest
import com.dreamdisplays.core.protocol.common.packets.ResolveAttemptReport
import com.dreamdisplays.core.protocol.common.packets.ScreenShareState
import com.dreamdisplays.core.share.ScreenShareCaptureRules
import com.dreamdisplays.core.share.ScreenShareCaptureRules.StopReason
import com.dreamdisplays.core.share.ScreenShareServerRules
import com.dreamdisplays.media.player.process.FFmpegBinary
import com.dreamdisplays.media.player.process.ScreenCapture
import com.dreamdisplays.media.player.process.ScreenCaptureProcesses
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.managers.ClientPacketManager
import com.dreamdisplays.platform.client.managers.DisplayLifecycleManager
import com.dreamdisplays.platform.client.ui.ClientMessage
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
import net.minecraft.client.Minecraft
import com.dreamdisplays.shared.capability.ServerFeature
import net.minecraft.ChatFormatting
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory
import java.util.*

/**
 * The built-in "Share screen": asks the server for a share on a display, then runs an `FFmpeg` capture of the picked
 * monitor publishing to the relay URL the server hands back (see [ScreenCapture]). Video only.
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
        val monitor: ScreenCapture.Monitor,
        /** What the display showed when the share was asked for (see [ScreenShareCaptureRules.Observation]). */
        val urlBeforeShare: String?,
        /** The server asked for x264 only. */
        val softwareOnly: Boolean = false,
        val settings: ScreenCapture.Settings = ScreenCapture.Settings(),
    ) {
        @Volatile
        var process: Process? = null

        @Volatile
        var stopped = false

        /** The share's URL as first seen on the display (see [ScreenShareCaptureRules.shareUrlAfter]). */
        var shareUrl: String? = null

        var lastHeartbeatMs = 0L
    }

    private val lock = Any()

    @Volatile
    private var running: Running? = null

    /** Monitor asked for per display, and what the display showed then, until the server answers. */
    private val pending = Collections.synchronizedMap(HashMap<UUID, Pair<ScreenCapture.Monitor, String?>>())

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
            } else {
                ScreenCapture.pickEncoder(ffmpeg)
            }
        }
    }

    /** Asks the server to let this client share [monitor] to [displayId]; capture starts once it agrees. */
    fun start(displayId: UUID, monitor: ScreenCapture.Monitor) {
        if (!available) return
        pending[displayId] = monitor to DisplayRegistry.screens[displayId]?.videoUrl
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
                Running(state.id, asked.first, asked.second, state.encoder == ScreenShareState.ENCODER_SOFTWARE, settings),
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
            val choice = ScreenCapture.choose(ffmpeg, r.monitor, r.softwareOnly)
            if (r.stopped) return@thread
            // The plan only came after something failed on this PC: worth knowing, though the player sees nothing
            if (choice.attempts.any { !it.ok } && plannedFallbacksReported.add(ffmpeg + choice.monitor.deviceName + choice.plan)) {
                report(r, choice, choice.plan, "", "planned_fallback", final = false,
                    "screen share needed a fallback: ${choice.plan} after ${choice.attempts.count { !it.ok }} failed test(s)")
            }
            val first = runShare(r, ffmpeg, choice.monitor, choice.plan, publishUrl, announce = true) ?: return@thread
            if (r.stopped) return@thread
            // Died at once: one quiet retry on the safe path (x264, and GDI capture unless only the encoder failed)
            val encoderFailed = ScreenCapture.isEncoderOpenFailure(first.output)
            val safe = if (first.ranMs < EARLY_EXIT_MS) ScreenCapture.safePlan(choice.plan, choice.monitor, encoderFailed) else null
            if (safe == null) {
                report(r, choice, choice.plan, first.output, "failed", final = true, first.reason)
                fail(r, first.reason)
                return@thread
            }
            logger.warn("Screen share with {} ended at once ({}); retrying with {}.", choice.plan, first.reason, safe)
            val retry = runShare(r, ffmpeg, choice.monitor, safe, publishUrl, announce = false, onSettled = {
                ScreenCapture.rememberChoice(ffmpeg, r.monitor, r.softwareOnly, ScreenCapture.Choice(choice.monitor, safe, choice.attempts))
                report(r, choice, safe, first.output, "fallback_ok", final = false,
                    "screen share ${choice.plan} failed at once (${first.reason}); ${safe} works")
            }) ?: return@thread
            if (r.stopped) return@thread
            if (retry.ranMs < EARLY_EXIT_MS) {
                report(r, choice, safe, first.output + "\n--- retry with $safe ---\n" + retry.output, "fallback_failed",
                    final = true, retry.reason)
            } else {
                report(r, choice, safe, retry.output, "failed", final = true, retry.reason)
            }
            fail(r, retry.reason)
        }
    }

    private const val EARLY_EXIT_MS = 5_000L

    /** Plans already reported as needing a fallback this session. */
    private val plannedFallbacksReported = Collections.synchronizedSet(HashSet<String>())

    private class Ended(val code: Int, val output: String, val reason: String, val ranMs: Long)

    /**
     * Runs one capture of [monitor] with [plan] until it ends or the share stops; null when it never started (the
     * player was told) or was stopped. [onSettled] runs once the capture has run for [EARLY_EXIT_MS].
     */
    private fun runShare(
        r: Running, ffmpeg: String, monitor: ScreenCapture.Monitor, plan: ScreenCapture.Plan, publishUrl: String,
        announce: Boolean, onSettled: (() -> Unit)? = null,
    ): Ended? {
        val args = ScreenCapture.args(ffmpeg, plan.source, monitor, plan.encoder, publishUrl, r.settings)
        val proc = synchronized(lock) {
            // Started and registered under the lock, so a stop racing this (first run or retry) either sees the
            // process or makes this skip the start
            if (r.stopped) return null
            runCatching { ProcessBuilder(args).redirectErrorStream(true).start() }.getOrElse {
                fail(r, LiveStreamUrls.redactCredentials(it.message ?: "could not start FFmpeg"))
                return null
            }.also { p ->
                ScreenCaptureProcesses.register(p)
                r.process = p
            }
        }
        val started = System.currentTimeMillis()
        if (announce) notify(ClientMessage.SHARE_STARTED.component().withStyle(ChatFormatting.GREEN))
        logger.info("Sharing {} ({}) with {} at {} fps, up to {}p.", monitor.name, monitor.deviceName.ifEmpty { "#${monitor.index}" },
            plan, r.settings.fps, r.settings.maxHeight)
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
        val ranMs = System.currentTimeMillis() - started
        if (r.stopped) return null
        val output = tail.joinToString("\n")
        logger.warn("Screen capture ({}) ended after {} ms (exit {}): {}", plan, ranMs, code, tail.toList().takeLast(6).joinToString(" | ").take(400))
        return Ended(code, output, tail.lastOrNull()?.take(120) ?: "capture ended (exit $code)", ranMs)
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
        if (running == null && ScreenCaptureProcesses.alive().isEmpty()) return
        val font = mc.font
        val text = "● " + ClientMessage.SHARE_INDICATOR.text()
        val w = font.width(text) + 8
        val x = (mc.window.guiScaledWidth - w) / 2
        val y = 4
        g.fill(x, y, x + w, y + font.lineHeight + 5, 0xA0000000.toInt())
        // The dot pulses so the pill reads as live, not as a stuck label
        val pulse = ((System.currentTimeMillis() / 600L) % 2L == 0L)
        g.drawText(font, text, x + 4, y + 3, if (pulse) 0xFFFF5050.toInt() else 0xFFFFA0A0.toInt(), false)
    }
}
