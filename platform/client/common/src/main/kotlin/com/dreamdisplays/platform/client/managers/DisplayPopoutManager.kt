package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.api.media.model.FramePixelFormat
import com.dreamdisplays.shared.playback.FullscreenMode
import com.dreamdisplays.core.runtime.getOrNull
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.popout.PopoutEvent
import com.dreamdisplays.platform.client.popout.PopoutManager
import com.dreamdisplays.platform.client.render.toUploadFormat
import com.dreamdisplays.platform.client.ui.*
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

/**
 * Manages window, PiP, and fullscreen popout surfaces for one display; wires frames to all sinks.
 */
class DisplayPopoutManager(
    private val displayScreen: DisplayScreen,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private var popoutWindow: VideoPopoutWindow? = null
    private var windowActive = false
    private var pipOverlay: PipOverlay? = null
    private var fullscreenOverlay: FullscreenOverlay? = null
    // The PiP a forced fullscreen was minimized into: it belongs to that session and ends with it
    private var pipFromFullscreen = false
    private var currentPlayer: MediaPlayer? = null
    private var contentAspect: () -> Double = { 16.0 / 9.0 }

    /** True while a window, PiP overlay, or fullscreen overlay is active. */
    val isActive: Boolean
        get() = windowActive || (popoutWindow?.isOpen == true) || (pipOverlay != null) || (fullscreenOverlay != null)

    /** True while the separate OS window is open. */
    val isWindowOpen: Boolean get() = windowActive || (popoutWindow?.isOpen == true)

    /** True once any popout surface of this display has been opened. */
    var wasEverActive: Boolean = false
        private set

    /** True while this display has a PiP overlay (not one that is fading out). */
    val isPipActive: Boolean get() = pipOverlay != null

    /** True while the fullscreen overlay is active. */
    val isFullscreenActive: Boolean get() = fullscreenOverlay != null

    /** Re-attaches all active popout frame sinks to [player] after a player swap. */
    fun attachTo(player: MediaPlayer, contentAspect: () -> Double) {
        currentPlayer = player
        this.contentAspect = contentAspect
        rewireSink()
    }

    private fun rewireSink() {
        val player = currentPlayer ?: return
        val aspect = contentAspect
        val targets = buildList<(ByteBuffer, Int, Int, FramePixelFormat) -> Unit> {
            popoutWindow?.takeIf { windowActive }?.let { win ->
                add { buf, fw, fh, format -> win.updateFrame(buf, fw, fh, aspect(), format.toUploadFormat()) }
            }
            pipOverlay?.let { overlay ->
                add { buf, fw, fh, format -> overlay.updateFrame(buf, fw, fh, aspect(), format.toUploadFormat()) }
            }
            fullscreenOverlay?.let { overlay ->
                add { buf, fw, fh, format -> overlay.updateFrame(buf, fw, fh, aspect(), format.toUploadFormat()) }
            }
        }
        when (targets.size) {
            0 -> player.setPopoutSink(null)
            1 -> player.setPopoutSink(targets[0])
            else -> player.setPopoutSink { buf, fw, fh, format -> for (t in targets) t(buf, fw, fh, format) }
        }
    }

    /** Draws the latest frame into the popout window, if one is open. */
    fun renderFrame() {
        popoutWindow?.renderFrame()
    }

    /** Opens (or re-sizes) the separate window, closing any active PiP overlay first. */
    fun activateWindowMode(player: MediaPlayer, textureWidth: Int, textureHeight: Int, contentAspect: () -> Double) {
        if (!VideoPopoutWindow.isAvailable) return
        currentPlayer = player
        this.contentAspect = contentAspect
        closePipOverlay()

        val w = textureWidth.takeIf { it > 0 } ?: 1280
        val h = textureHeight.takeIf { it > 0 } ?: 720
        val win = popoutWindow

        if (win != null && win.isOpen) {
            // TODO: modify window size?
            win.open(w, h)
            windowActive = true
            rewireSink()
            return
        }

        runCatching {
            val newWin = win ?: createWindow().also { created ->
                popoutWindow = created
                created.on { event -> emit(event) }
            }
            newWin.open(w, h)
        }.onSuccess {
            windowActive = true
            wasEverActive = true
            rewireSink()
        }.onFailure { e ->
            logger.warn("Could not open window: ${e.message}.")
            emit(PopoutEvent.BackendFailed(displayScreen.uuid.toString(), e.message))
        }
    }

    /**
     * A new OS window. Its close is reported from the window's own thread, possibly after this manager already let it
     * go (a cleared display, an ended share) and opened another: only the current window's close counts.
     */
    private fun createWindow(): VideoPopoutWindow {
        lateinit var created: VideoPopoutWindow
        created = VideoPopoutWindow(displayScreen.uuid.toString()) {
            Minecraft.getInstance().execute {
                if (popoutWindow === created) {
                    windowActive = false
                    rewireSink()
                }
            }
        }
        return created
    }

    /** Closes the OS window, including one whose creation is still queued (it then never opens). */
    private fun closeWindow() {
        popoutWindow?.close()
        windowActive = false
    }

    /** Opens an in-game PiP overlay at [corner], closing any open window and fullscreen overlay first. */
    fun activatePipMode(
        player: MediaPlayer,
        corner: PipCorner = PipCorner.BOTTOM_RIGHT,
        interactive: Boolean = true,
        initialSizeFraction: Float = 0.33f,
        contentAspect: () -> Double,
    ) {
        currentPlayer = player
        this.contentAspect = contentAspect
        pipFromFullscreen = false
        closeWindow()
        closeFullscreenOverlay()
        closePipOverlay()
        val overlay = PipOverlay(displayScreen, corner, interactive, initialSizeFraction)
        val added = PipOverlayManager.add(overlay)
        if (added) {
            pipOverlay = overlay
            wasEverActive = true
            rewireSink()
            emit(PopoutEvent.Opened(displayScreen.uuid.toString()))
        } else {
            rewireSink()
            logger.warn("No PiP corners available.")
        }
    }

    /** Opens the fullscreen overlay, closing any PiP overlay of this display first. */
    fun activateFullscreenMode(
        player: MediaPlayer,
        mode: FullscreenMode,
        forced: Boolean = false,
        sessionId: String? = null,
        contentAspect: () -> Double,
    ) {
        currentPlayer = player
        this.contentAspect = contentAspect
        closePipOverlay()
        closeFullscreenOverlay()
        fullscreenOverlay = FullscreenOverlayManager.open(displayScreen, mode, forced, sessionId)
        wasEverActive = true
        rewireSink()
        emit(PopoutEvent.Opened(displayScreen.uuid.toString()))
    }

    /** Closes the fullscreen overlay (and the PiP it was minimized into), keeping other window / PiP surfaces alive. */
    fun deactivateFullscreen(player: MediaPlayer?) {
        player?.let { currentPlayer = it }
        if (fullscreenOverlay == null && !pipFromFullscreen) return
        closeFullscreenOverlay()
        if (pipFromFullscreen) closePipOverlay()
        rewireSink()
    }

    /** Swaps the active fullscreen overlay for a PiP overlay in one step, at double the normal PiP size. */
    fun minimizeFullscreenToPip(player: MediaPlayer?, interactive: Boolean = true) {
        val mp = player ?: currentPlayer ?: return
        if (fullscreenOverlay == null) return
        activatePipMode(
            mp,
            PipCorner.BOTTOM_RIGHT,
            interactive,
            initialSizeFraction = 0.33f,
            contentAspect = contentAspect
        )
        pipFromFullscreen = pipOverlay != null
    }

    /** Closes every popout mode and detaches the frame sink. */
    fun deactivate(player: MediaPlayer?) {
        player?.setPopoutSink(null)
        closeWindow()
        closeFullscreenOverlay()
        closePipOverlay()
    }

    /**
     * A private stream stopped delivering: its picture goes from every surface at once, but the viewer's PiP and
     * fullscreen (forced ones included) stay open, blank, and show the stream again when it resumes. The separate OS
     * window holds its last frame in its own buffers, so it is closed (a later popout gets a new window).
     */
    fun blankPrivatePicture() {
        closeWindow()
        popoutWindow = null
        rewireSink()
        val mc = Minecraft.getInstance()
        val blank = {
            pipOverlay?.blankPicture(mc)
            fullscreenOverlay?.blankPicture(mc)
        }
        if (mc.isSameThread) blank() else mc.execute { blank() }
    }

    /**
     * Closes every popout of this display at once, with no closing animation (which would keep showing the last
     * frame while it fades), and detaches the frame sink. For a screen share that ended: nothing of it may stay up.
     */
    fun closeImmediately(player: MediaPlayer?) {
        player?.setPopoutSink(null)
        currentPlayer?.setPopoutSink(null)
        val hadOverlay = pipOverlay != null || fullscreenOverlay != null
        closeWindow()
        // The window holds its last frame in its buffers: a later popout gets a new window, never this one
        popoutWindow = null
        PipOverlayManager.removeNow(displayScreen)
        FullscreenOverlayManager.removeNow(displayScreen)
        pipOverlay = null
        fullscreenOverlay = null
        if (hadOverlay) emit(PopoutEvent.Closed(displayScreen.uuid.toString()))
    }

    /** Tears down all popout state when the display is unregistered. */
    fun unregister(player: MediaPlayer?) {
        player?.setPopoutSink(null)
        closeWindow()
        popoutWindow = null
        PipOverlayManager.remove(displayScreen)
        FullscreenOverlayManager.remove(displayScreen)
        fullscreenOverlay = null
        pipFromFullscreen = false
        if (pipOverlay != null) {
            pipOverlay = null
            emit(PopoutEvent.Closed(displayScreen.uuid.toString()))
        }
    }

    private fun closePipOverlay() {
        pipFromFullscreen = false
        val overlay = pipOverlay ?: return
        overlay.startClose()
        pipOverlay = null
        emit(PopoutEvent.Closed(displayScreen.uuid.toString()))
    }

    private fun closeFullscreenOverlay() {
        val overlay = fullscreenOverlay ?: return
        overlay.startClose()
        fullscreenOverlay = null
        emit(PopoutEvent.Closed(displayScreen.uuid.toString()))
    }

    private fun emit(event: PopoutEvent) {
        DreamServices.registry.getOrNull<PopoutManager>()?.emit(event)
    }
}
