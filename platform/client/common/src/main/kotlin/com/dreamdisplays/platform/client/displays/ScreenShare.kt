package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.media.source.LiveStreamUrls
import com.dreamdisplays.core.protocol.common.hasFeature
import com.dreamdisplays.core.protocol.common.packets.ScreenShareRequest
import com.dreamdisplays.core.protocol.common.packets.ScreenShareState
import com.dreamdisplays.media.player.process.FFmpegBinary
import com.dreamdisplays.media.player.process.MediaProcess
import com.dreamdisplays.media.player.process.ScreenCapture
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.managers.ClientPacketManager
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
 * monitor publishing to the relay URL the server hands back (see [ScreenCapture]). Video only. Everything but [start]
 * may be called from any thread.
 */
object ScreenShare {
    private val logger = LoggerFactory.getLogger(javaClass)

    private class Running(val displayId: UUID, val monitor: ScreenCapture.Monitor) {
        @Volatile
        var process: Process? = null

        @Volatile
        var stopped = false
    }

    @Volatile
    private var running: Running? = null

    /** Monitor asked for per display, until the server answers. */
    private val pending = Collections.synchronizedMap(HashMap<UUID, ScreenCapture.Monitor>())

    /** True when this server lets this player share, and this OS has a capture source. */
    val available: Boolean
        get() = ClientPacketManager.serverSnapshot.hasFeature(ServerFeature.SCREEN_SHARE) &&
                ScreenCapture.sourceForThisOs() != null

    /** The display this client is sharing to, or null. */
    val sharingTo: UUID? get() = running?.displayId

    /** True while this client shares to [displayId]. */
    fun isSharing(displayId: UUID): Boolean = running?.displayId == displayId

    /**
     * What may be shared, primary first. On Windows the screens Desktop Duplication found across every graphics
     * adapter (see [prewarm]); elsewhere, and until that probe is done, the monitors GLFW sees. Render thread only.
     */
    fun monitors(): List<ScreenCapture.Monitor> {
        if (ScreenCapture.sourceForThisOs() == ScreenCapture.Source.DDAGRAB) {
            ScreenCapture.knownWindowsScreens(FFmpegBinary.getPathIfResolved())?.takeIf { it.isNotEmpty() }?.let { return it }
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

    /**
     * The server lets this player share: find the encoder (and on Windows the screens) now, in the background, so the
     * picker is complete and the first share starts at once.
     */
    fun prewarm() {
        if (!available) return
        thread("DreamDisplays-screen-share-probe") {
            val ffmpeg = FFmpegBinary.getPath() ?: return@thread
            if (ScreenCapture.sourceForThisOs() == ScreenCapture.Source.DDAGRAB) ScreenCapture.probeWindowsScreens(ffmpeg)
            ScreenCapture.pickEncoder(ffmpeg)
        }
    }

    /** Asks the server to let this client share [monitor] to [displayId]; capture starts once it agrees. */
    fun start(displayId: UUID, monitor: ScreenCapture.Monitor) {
        if (!available) return
        pending[displayId] = monitor
        Initializer.sendPacket(ScreenShareRequest(displayId, start = true))
    }

    /** Stops sharing (encoder and server share both). */
    fun stop() {
        val r = running ?: return
        stopCapture(r)
        Initializer.sendPacket(ScreenShareRequest(r.displayId, start = false))
    }

    /** The server's answer or a change it made (share replaced, display gone). */
    fun onState(state: ScreenShareState) {
        if (state.active && state.publishUrl.isNotEmpty()) {
            val current = running
            if (current != null && current.displayId == state.id) {
                pending.remove(state.id) // Already encoding to it
                return
            }
            val monitor = pending.remove(state.id) ?: return
            current?.let(::stopCapture)
            launch(Running(state.id, monitor), state.publishUrl)
            return
        }
        pending.remove(state.id)
        running?.takeIf { it.displayId == state.id }?.let(::stopCapture)
        if (state.error.isNotEmpty()) notify(ClientMessage.SHARE_REFUSED.component(state.error).withStyle(ChatFormatting.RED))
    }

    /** Disconnected or quitting: the server ends the share on its own. */
    fun onServerLeft() {
        pending.clear()
        running?.let(::stopCapture)
    }

    private fun launch(r: Running, publishUrl: String) {
        running = r
        thread("DreamDisplays-screen-share") {
            val ffmpeg = FFmpegBinary.getPath()
            val source = ScreenCapture.sourceForThisOs()
            if (ffmpeg == null || source == null) {
                fail(r, "FFmpeg is not available")
                return@thread
            }
            val encoder = ScreenCapture.pickEncoder(ffmpeg)
            if (r.stopped) return@thread
            val args = ScreenCapture.args(ffmpeg, source, r.monitor, encoder, publishUrl)
            val proc = runCatching { ProcessBuilder(args).redirectErrorStream(true).start() }.getOrElse {
                fail(r, LiveStreamUrls.redactCredentials(it.message ?: "could not start FFmpeg"))
                return@thread
            }
            r.process = proc
            if (r.stopped) {
                MediaProcess.gracefulDestroy(proc); return@thread
            }
            notify(ClientMessage.SHARE_STARTED.component().withStyle(ChatFormatting.GREEN))
            logger.info("Sharing monitor {} ({}) with {}.", r.monitor.index + 1, r.monitor.name, encoder.ffmpegName)
            val tail = ArrayDeque<String>()
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    if (tail.size >= 6) tail.removeFirst()
                    // FFmpeg names the publish URL in its errors; its credentials never reach a log or the chat
                    tail.addLast(LiveStreamUrls.redactCredentials(line))
                }
            }
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            if (!r.stopped) {
                logger.warn("Screen capture ended (exit {}): {}", code, tail.joinToString(" | ").take(400))
                fail(r, tail.lastOrNull()?.take(120) ?: "capture ended (exit $code)")
            }
        }
    }

    /** Capture died on its own: tell the player and end the share on the server. */
    private fun fail(r: Running, why: String) {
        if (r.stopped) return
        stopCapture(r)
        Initializer.sendPacket(ScreenShareRequest(r.displayId, start = false))
        notify(ClientMessage.SHARE_FAILED.component(why).withStyle(ChatFormatting.RED))
    }

    private fun stopCapture(r: Running) {
        r.stopped = true
        if (running === r) running = null
        val proc = r.process ?: return
        thread("DreamDisplays-screen-share-stop") { quit(proc) }
    }

    /**
     * Asks `FFmpeg` to finish (`q` on stdin), so it closes the relay connection properly and viewers see the end at
     * once rather than after the relay's idle timeout; killed if it does not exit within a second and a half.
     */
    private fun quit(proc: Process) {
        runCatching {
            proc.outputStream.write('q'.code)
            proc.outputStream.flush()
        }
        val exited = runCatching { proc.waitFor(1_500, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!exited) MediaProcess.gracefulDestroy(proc)
    }

    private fun thread(name: String, body: () -> Unit) =
        Thread(body, name).apply { isDaemon = true }.start()

    private fun notify(message: net.minecraft.network.chat.Component) = DisplayQueues.notifyLocal(message)

    /** The "you are sharing your screen" pill at the top of the HUD, while a share runs. */
    fun renderIndicator(mc: Minecraft, g: GuiGraphicsCompat) {
        if (running == null) return
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
