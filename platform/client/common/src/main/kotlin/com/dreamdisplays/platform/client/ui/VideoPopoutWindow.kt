package com.dreamdisplays.platform.client.ui

import com.dreamdisplays.api.media.sink.VideoFrameSink
import com.dreamdisplays.platform.client.popout.PopoutEvent
import com.dreamdisplays.platform.client.popout.PopoutWindow
import com.dreamdisplays.platform.client.popout.WindowBackend
import com.dreamdisplays.platform.client.popout.WindowConfig
import com.dreamdisplays.platform.client.render.AsyncTextureUploader
import com.dreamdisplays.platform.client.render.FrameHandoff
import com.dreamdisplays.platform.client.render.OverlayFrame
import com.dreamdisplays.platform.client.render.UploadPixelFormat
import com.dreamdisplays.util.OsInfo
import kotlinx.atomicfu.atomic
import net.minecraft.client.Minecraft
//? if >=26.3 {
import org.lwjgl.sdl.SDLError
import org.lwjgl.sdl.SDLEvents
import org.lwjgl.sdl.SDLPixels
import org.lwjgl.sdl.SDLRender
import org.lwjgl.sdl.SDLScancode
import org.lwjgl.sdl.SDLVideo
import org.lwjgl.sdl.SDL_Event
import org.lwjgl.sdl.SDL_EventFilter
import org.lwjgl.sdl.SDL_FRect
import org.lwjgl.sdl.SDL_Texture
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
//?}
//? if <26.3 {
import org.lwjgl.glfw.GLFW
//?}
import org.lwjgl.opengl.*
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

// TODO: rewrite

/**
 * Detached window that mirrors the decoded video.
 */
class VideoPopoutWindow(
    private val displayId: String,
    private val onClose: () -> Unit,
) : PopoutWindow {

    private val eventListeners = java.util.concurrent.CopyOnWriteArrayList<(PopoutEvent) -> Unit>()

    private fun emitEvent(event: PopoutEvent) = eventListeners.forEach { it(event) }

    private val impl: PopoutBackend =
        //? if >=26.3 {
        if (IS_MACOS) SdlBackend { emitEvent(PopoutEvent.Closed(displayId)); onClose() }
        else AwtBackend { emitEvent(PopoutEvent.Closed(displayId)); onClose() }
        //?} else
        /*if (IS_MACOS) GlfwBackend { emitEvent(PopoutEvent.Closed(displayId)); onClose() }
        else AwtBackend { emitEvent(PopoutEvent.Closed(displayId)); onClose() }*/

    override val backend: WindowBackend =
        //? if >=26.3 {
        if (IS_MACOS) WindowBackend.SDL else WindowBackend.AWT
        //?} else
        /*if (IS_MACOS) WindowBackend.GLFW else WindowBackend.AWT*/
    override val isOpen: Boolean get() = impl.isOpen
    override val width: Int get() = impl.width
    override val height: Int get() = impl.height

    override fun open(config: WindowConfig): VideoFrameSink {
        impl.open(config.initialWidth, config.initialHeight)
        emitEvent(PopoutEvent.Opened(displayId))
        return VideoFrameSink { frame ->
            impl.updateFrame(
                ByteBuffer.wrap(frame.data), frame.width, frame.height,
                frame.width.toDouble() / frame.height,
                UploadPixelFormat.RGB24,
            )
        }
    }

    override fun on(listener: (PopoutEvent) -> Unit): AutoCloseable {
        eventListeners += listener
        return AutoCloseable { eventListeners -= listener }
    }

    /** Updates the current frame buffer. Safe to call from any thread. */
    fun updateFrame(
        buf: ByteBuffer,
        w: Int,
        h: Int,
        aspect: Double,
        format: UploadPixelFormat = UploadPixelFormat.RGB24
    ) =
        impl.updateFrame(buf, w, h, aspect, format)

    /** No-op on AWT (self-driven repaints). On GLFW, renders the current frame to the window. */
    fun renderFrame() = impl.renderFrame()

    /** Opens (or focuses) the window. Safe to call from any thread. */
    fun open(videoW: Int, videoH: Int) {
        val wasOpen = impl.isOpen
        impl.open(videoW, videoH)
        if (!wasOpen) emitEvent(PopoutEvent.Opened(displayId))
    }

    override fun close() = impl.close()

    private interface PopoutBackend {
        val isOpen: Boolean
        val width: Int
        val height: Int
        fun updateFrame(buf: ByteBuffer, w: Int, h: Int, aspect: Double, format: UploadPixelFormat)
        fun renderFrame()
        fun open(videoW: Int, videoH: Int)
        fun close()
    }

    //? if <26.3 {
    private class GlfwBackend(private val onClose: () -> Unit) : PopoutBackend {

        /** Frames from the decode thread; the render thread borrows the newest one while it uploads it. */
        private val frames = FrameHandoff<OverlayFrame>()

        /** The frame now in the window's texture, redrawn every frame until a newer one arrives. */
        private var shownMeta: OverlayFrame? = null
        private var uploadedVersion = 0L

        @Volatile
        private var windowHandle = 0L
        private val winW = atomic(0)
        private val winH = atomic(0)
        private var renderer: QuadRenderer? = null
        private var popoutCaps: GLCapabilities? = null

        @Volatile
        private var fullscreen = false
        private var savedX = 0
        private var savedY = 0
        private var savedW = 0
        private var savedH = 0

        // Set by the window's own GLFW callbacks, acted on from renderFrame: GLFW forbids destroying a window from
        // inside one of its callbacks (on macOS the event handler keeps using the window after the callback returns)
        @Volatile
        private var closeRequested = false

        @Volatile
        private var fullscreenToggleRequested = false

        override val isOpen: Boolean get() = windowHandle != 0L
        override val width: Int get() = winW.value
        override val height: Int get() = winH.value

        override fun updateFrame(buf: ByteBuffer, w: Int, h: Int, aspect: Double, format: UploadPixelFormat) {
            if (windowHandle == 0L) return
            frames.publish(buf, w * h * format.bytesPerPixel, OverlayFrame(w, h, format, aspect))
        }

        override fun renderFrame() {
            if (closeRequested) {
                closeRequested = false
                destroyWindow()
                return
            }
            val handle = windowHandle
            if (handle == 0L) return
            //? if >=1.21.2 {
            // Watching the popout is not being idle: Minecraft's idle frame limit (30 fps after a minute without input
            // to its own window, 10 after ten) would also be this window's frame rate, as it is drawn in Minecraft's frame
            Minecraft.getInstance().framerateLimitTracker.onInputReceived()
            //?}
            if (fullscreenToggleRequested) {
                fullscreenToggleRequested = false
                toggleFullscreen(handle)
            }
            val vw = winW.value
            val vh = winH.value
            if (vw <= 0 || vh <= 0) return
            val frame = frames.borrowNewest(uploadedVersion)
            try {
                val meta = frame?.meta ?: shownMeta ?: return
                val fw = meta.width
                val fh = meta.height
                if (fw <= 0 || fh <= 0) return

                val prevCtx = GLFW.glfwGetCurrentContext()
                // Only restore capabilities when there is an active GL context to restore.
                // Skipping setCapabilities(null) avoids corrupting LWJGL state for mods that
                // replace the GL pipeline (e.g., Vulkan-based renderers).
                val prevCaps = if (prevCtx != 0L) runCatching { GL.getCapabilities() }.getOrNull() else null

                GLFW.glfwMakeContextCurrent(handle)
                val caps = popoutCaps
                if (caps == null) popoutCaps = GL.createCapabilities() else GL.setCapabilities(caps)

                val r = renderer ?: QuadRenderer().also { renderer = it }
                if (frame != null) {
                    r.upload(frame.buffer, fw, fh, meta.format)
                    uploadedVersion = frame.version
                    shownMeta = meta
                }
                r.draw(vw, vh, contentRect(fw, fh, meta.aspect), fw, fh)
                GLFW.glfwSwapBuffers(handle)

                GLFW.glfwMakeContextCurrent(prevCtx)
                if (prevCaps != null) GL.setCapabilities(prevCaps)
            } finally {
                if (frame != null) frames.release()
            }
        }

        override fun open(videoW: Int, videoH: Int) {
            Minecraft.getInstance().execute {
                if (windowHandle != 0L) {
                    GLFW.glfwShowWindow(windowHandle)
                    GLFW.glfwFocusWindow(windowHandle)
                    return@execute
                }
                createWindow(videoW, videoH)
            }
        }

        override fun close() {
            Minecraft.getInstance().execute { destroyWindow() }
        }

        private fun createWindow(videoW: Int, videoH: Int) {
            val w = videoW.coerceIn(480, 1280)
            val h = videoH.coerceIn(270, 720)

            GLFW.glfwDefaultWindowHints()
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API)
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_TRUE)
            GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2)
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GL11.GL_TRUE)

            val handle = GLFW.glfwCreateWindow(w, h, "Dream Displays", 0L, 0L)
            if (handle == 0L) {
                logger.warn("glfwCreateWindow failed"); return
            }

            windowHandle = handle
            winW.value = w; winH.value = h

            GLFW.glfwSetWindowCloseCallback(handle) { _ -> closeRequested = true }
            GLFW.glfwSetKeyCallback(handle) { _, key, _, action, _ ->
                if (action == GLFW.GLFW_PRESS) when (key) {
                    GLFW.GLFW_KEY_ESCAPE -> closeRequested = true
                    GLFW.GLFW_KEY_F -> fullscreenToggleRequested = true
                }
            }
            GLFW.glfwSetFramebufferSizeCallback(handle) { _, fw, fh -> winW.value = fw; winH.value = fh }
            val wa = IntArray(1)
            val ha = IntArray(1)
            GLFW.glfwGetFramebufferSize(handle, wa, ha)
            if (wa[0] > 0 && ha[0] > 0) {
                winW.value = wa[0]; winH.value = ha[0]
            }
        }

        private fun destroyWindow() {
            val handle = windowHandle
            windowHandle = 0L
            if (handle != 0L) {
                if (renderer != null) {
                    val prevCtx = GLFW.glfwGetCurrentContext()
                    val prevCaps = if (prevCtx != 0L) runCatching { GL.getCapabilities() }.getOrNull() else null
                    GLFW.glfwMakeContextCurrent(handle)
                    GL.setCapabilities(popoutCaps ?: GL.createCapabilities())
                    renderer?.cleanup(); renderer = null; popoutCaps = null
                    GLFW.glfwMakeContextCurrent(prevCtx)
                    if (prevCaps != null) GL.setCapabilities(prevCaps)
                }
                GLFW.glfwDestroyWindow(handle)
            }
            shownMeta = null
            frames.clear()
            // Nothing to report for a window that was never open (a close with no window, or one closed already)
            if (handle != 0L) onClose()
        }

        private fun toggleFullscreen(handle: Long) {
            if (!fullscreen) {
                val xa = IntArray(1)
                val ya = IntArray(1)
                val wa = IntArray(1)
                val ha = IntArray(1)
                GLFW.glfwGetWindowPos(handle, xa, ya)
                // Window size in screen coordinates: the framebuffer size is in pixels (twice as large on Retina)
                GLFW.glfwGetWindowSize(handle, wa, ha)
                val monitor = monitorUnder(xa[0], ya[0], wa[0], ha[0])
                if (monitor == 0L) return
                val mode = GLFW.glfwGetVideoMode(monitor) ?: return
                savedX = xa[0]; savedY = ya[0]; savedW = wa[0]; savedH = ha[0]
                GLFW.glfwSetWindowMonitor(handle, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate())
                fullscreen = true
            } else {
                GLFW.glfwSetWindowMonitor(handle, 0L, savedX, savedY, savedW, savedH, GLFW.GLFW_DONT_CARE)
                fullscreen = false
            }
        }

        /** The monitor the window at ([x], [y], [w] x [h]) is on, so fullscreen opens there; the primary as a fallback. */
        private fun monitorUnder(x: Int, y: Int, w: Int, h: Int): Long {
            val monitors = GLFW.glfwGetMonitors() ?: return GLFW.glfwGetPrimaryMonitor()
            val handles = LongArray(monitors.limit()) { monitors.get(it) }
            val rects = handles.map { m ->
                val mx = IntArray(1)
                val my = IntArray(1)
                GLFW.glfwGetMonitorPos(m, mx, my)
                val mode = GLFW.glfwGetVideoMode(m)
                MonitorPick.Rect(mx[0], my[0], mode?.width() ?: 0, mode?.height() ?: 0)
            }
            val index = MonitorPick.indexFor(x + w / 2, y + h / 2, rects)
            return if (index >= 0) handles[index] else GLFW.glfwGetPrimaryMonitor()
        }

        companion object {
            private val logger = LoggerFactory.getLogger(javaClass)
            private val EMPTY_DIRECT = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

            private fun contentRect(frameW: Int, frameH: Int, contentAspect: Double): ContentRect {
                if (frameW <= 0 || frameH <= 0) return ContentRect(0, 0, frameW, frameH)
                if (contentAspect <= 0.0 || !contentAspect.isFinite()) return ContentRect(0, 0, frameW, frameH)
                val frameAspect = frameW / frameH.toDouble()
                return if (contentAspect > frameAspect) {
                    val h = (frameW / contentAspect).toInt().coerceIn(1, frameH)
                    ContentRect(0, (frameH - h) / 2, frameW, h)
                } else {
                    val w = (frameH * contentAspect).toInt().coerceIn(1, frameW)
                    ContentRect((frameW - w) / 2, 0, w, frameH)
                }
            }
        }
    }
    //?}

    //? if >=26.3 {
    private class SdlBackend(private val onClose: () -> Unit) : PopoutBackend {
        @Volatile
        private var frontBuf: ByteBuffer = EMPTY_DIRECT
        private var backBuf: ByteBuffer = EMPTY_DIRECT

        @Volatile
        private var frameW = 0

        @Volatile
        private var frameH = 0

        @Volatile
        private var frameFormat = UploadPixelFormat.RGB24

        @Volatile
        private var contentAspect = 0.0
        private val frameVersion = atomic(0L)
        private var uploadedVersion = 0L

        @Volatile
        private var windowHandle = 0L
        private var renderer = 0L
        private var texture: SDL_Texture? = null
        private var textureW = 0
        private var textureH = 0
        private var textureFormat = 0
        private val winW = atomic(0)
        private val winH = atomic(0)

        @Volatile
        private var fullscreen = false
        private var eventFilter: SDL_EventFilter? = null

        override val isOpen: Boolean get() = windowHandle != 0L
        override val width: Int get() = winW.value
        override val height: Int get() = winH.value

        override fun updateFrame(buf: ByteBuffer, w: Int, h: Int, aspect: Double, format: UploadPixelFormat) {
            if (windowHandle == 0L) return
            val size = w * h * format.bytesPerPixel
            if (size <= 0 || buf.remaining() < size) return
            var back = backBuf
            if (back.capacity() < size) back = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            back.clear()
            val savedLimit = buf.limit()
            val savedPos = buf.position()
            buf.limit(savedPos + size)
            back.put(buf)
            buf.limit(savedLimit)
            buf.position(savedPos)
            back.flip()
            val prev = frontBuf
            frontBuf = back
            backBuf = if (prev.capacity() >= size) prev
            else ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            contentAspect = aspect
            frameFormat = format
            frameW = w
            frameH = h
            frameVersion.incrementAndGet()
        }

        override fun renderFrame() {
            val window = windowHandle
            val gpu = renderer
            if (window == 0L || gpu == 0L) return
            val fw = frameW
            val fh = frameH
            val buf = frontBuf
            val format = frameFormat
            val vw = winW.value
            val vh = winH.value
            if (fw <= 0 || fh <= 0 || buf.remaining() < fw * fh * format.bytesPerPixel || vw <= 0 || vh <= 0) return

            val sdlFormat = if (format == UploadPixelFormat.RGBA32) {
                SDLPixels.SDL_PIXELFORMAT_ABGR8888
            } else {
                SDLPixels.SDL_PIXELFORMAT_RGB24
            }
            var tex = texture
            if (tex == null || textureW != fw || textureH != fh || textureFormat != sdlFormat) {
                if (tex != null) SDLRender.SDL_DestroyTexture(tex)
                tex = SDLRender.SDL_CreateTexture(gpu, sdlFormat, SDLRender.SDL_TEXTUREACCESS_STREAMING, fw, fh)
                if (tex == null) return
                texture = tex
                textureW = fw
                textureH = fh
                textureFormat = sdlFormat
            }

            val version = frameVersion.value
            if (version != uploadedVersion) {
                val pitch = fw * format.bytesPerPixel
                SDLRender.nSDL_UpdateTexture(tex.address(), 0L, MemoryUtil.memAddress(buf), pitch)
                uploadedVersion = version
            }

            val box = letterbox(fw, fh, contentAspect)
            val scale = minOf(vw / box.w.toFloat(), vh / box.h.toFloat())
            val dw = box.w * scale
            val dh = box.h * scale
            val dx = (vw - dw) / 2f
            val dy = (vh - dh) / 2f

            SDLRender.SDL_SetRenderDrawColor(gpu, 0, 0, 0, 255.toByte())
            SDLRender.SDL_RenderClear(gpu)
            MemoryStack.stackPush().use { stack ->
                val dest = SDL_FRect.malloc(stack).set(dx, dy, dw, dh)
                SDLRender.SDL_RenderTexture(gpu, tex, null, dest)
            }
            SDLRender.SDL_RenderPresent(gpu)
        }

        override fun open(videoW: Int, videoH: Int) {
            Minecraft.getInstance().execute {
                if (windowHandle != 0L) {
                    SDLVideo.SDL_ShowWindow(windowHandle)
                    SDLVideo.SDL_RaiseWindow(windowHandle)
                    return@execute
                }
                createWindow(videoW, videoH)
            }
        }

        override fun close() {
            Minecraft.getInstance().execute { destroyWindow() }
        }

        private fun createWindow(videoW: Int, videoH: Int) {
            val w = videoW.coerceIn(480, 1280)
            val h = videoH.coerceIn(270, 720)
            val flags = SDLVideo.SDL_WINDOW_RESIZABLE or SDLVideo.SDL_WINDOW_HIGH_PIXEL_DENSITY
            val window = SDLVideo.SDL_CreateWindow("Dream Displays", w, h, flags)
            if (window == 0L) {
                logger.warn("SDL_CreateWindow failed: {}", SDLError.SDL_GetError())
                return
            }
            var gpu = SDLRender.nSDL_CreateRenderer(window, 0L)
            if (gpu == 0L) {
                gpu = SDLRender.SDL_CreateRenderer(window, "metal")
            }
            if (gpu == 0L) {
                gpu = SDLRender.SDL_CreateRenderer(window, "software")
            }
            if (gpu == 0L) {
                logger.warn("SDL_CreateRenderer failed: {}", SDLError.SDL_GetError())
                SDLVideo.SDL_DestroyWindow(window)
                return
            }
            SDLRender.SDL_SetRenderVSync(gpu, 1)
            windowHandle = window
            renderer = gpu
            refreshSize()
            val filter = SDL_EventFilter.create { _, eventPtr ->
                val event = SDL_Event.create(eventPtr)
                if (SDLEvents.SDL_GetWindowFromEvent(event) != windowHandle) true
                else {
                    onPopoutEvent(event)
                    false
                }
            }
            eventFilter = filter
            SDLEvents.SDL_SetEventFilter(filter, 0L)
        }

        private fun onPopoutEvent(event: SDL_Event) {
            when (event.type()) {
                SDLEvents.SDL_EVENT_WINDOW_CLOSE_REQUESTED ->
                    Minecraft.getInstance().execute { destroyWindow() }
                SDLEvents.SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED,
                SDLEvents.SDL_EVENT_WINDOW_RESIZED -> refreshSize()
                SDLEvents.SDL_EVENT_KEY_DOWN -> {
                    when (event.key().scancode()) {
                        SDLScancode.SDL_SCANCODE_ESCAPE ->
                            Minecraft.getInstance().execute { destroyWindow() }
                        SDLScancode.SDL_SCANCODE_F -> {
                            fullscreen = !fullscreen
                            SDLVideo.SDL_SetWindowFullscreen(windowHandle, fullscreen)
                        }
                    }
                }
            }
        }

        private fun refreshSize() {
            val window = windowHandle
            if (window == 0L) return
            MemoryStack.stackPush().use { stack ->
                val w = stack.mallocInt(1)
                val h = stack.mallocInt(1)
                if (SDLVideo.SDL_GetWindowSizeInPixels(window, w, h) && w.get(0) > 0 && h.get(0) > 0) {
                    winW.value = w.get(0)
                    winH.value = h.get(0)
                }
            }
        }

        private fun destroyWindow() {
            val window = windowHandle
            val gpu = renderer
            windowHandle = 0L
            renderer = 0L
            eventFilter?.let { filter ->
                SDLEvents.nSDL_SetEventFilter(0L, 0L)
                filter.free()
                eventFilter = null
            }
            texture?.let { SDLRender.SDL_DestroyTexture(it); texture = null }
            if (gpu != 0L) SDLRender.SDL_DestroyRenderer(gpu)
            if (window != 0L) SDLVideo.SDL_DestroyWindow(window)
            onClose()
        }

        private fun letterbox(frameW: Int, frameH: Int, contentAspect: Double): ContentRect {
            if (frameW <= 0 || frameH <= 0) return ContentRect(0, 0, frameW, frameH)
            if (contentAspect <= 0.0 || !contentAspect.isFinite()) return ContentRect(0, 0, frameW, frameH)
            val frameAspect = frameW / frameH.toDouble()
            return if (contentAspect > frameAspect) {
                val ch = (frameW / contentAspect).toInt().coerceIn(1, frameH)
                ContentRect(0, (frameH - ch) / 2, frameW, ch)
            } else {
                val cw = (frameH * contentAspect).toInt().coerceIn(1, frameW)
                ContentRect((frameW - cw) / 2, 0, cw, frameH)
            }
        }

        companion object {
            private val logger = LoggerFactory.getLogger(javaClass)
            private val EMPTY_DIRECT = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
        }
    }
    //?}

    private class AwtBackend(private val onClose: () -> Unit) : PopoutBackend {

        /**
         * Frames from the decode thread, only copied there: the per-pixel conversion runs on the EDT when it paints, so
         * it never holds up the in-world picture, and the image it paints is never rewritten while being drawn.
         */
        private val frames = FrameHandoff<OverlayFrame>()

        /** The converted picture and its description: EDT only. */
        private var image: BufferedImage? = null
        private var shownVersion = 0L
        private var contentAspect = 0.0
        private var scratch = ByteArray(0)

        /** Open asked for and not closed since: frames are kept from then on, even before the window exists. */
        @Volatile
        private var wanted = false

        @Volatile
        private var frame: JFrame? = null

        @Volatile
        private var panel: VideoPanel? = null
        private val logger = LoggerFactory.getLogger(javaClass)

        override val isOpen: Boolean get() = frame?.isDisplayable == true
        override val width: Int get() = frame?.width ?: 0
        override val height: Int get() = frame?.height ?: 0

        override fun updateFrame(buf: ByteBuffer, w: Int, h: Int, aspect: Double, format: UploadPixelFormat) {
            // Also while the window is still being created: the frame a paused video replays on attach is its only one
            if (!wanted || w <= 0 || h <= 0 || format.bytesPerPixel < 3) return
            if (frames.publish(buf, w * h * format.bytesPerPixel, OverlayFrame(w, h, format, aspect))) panel?.repaint()
        }

        override fun renderFrame() = Unit

        /** Open and close requests in the order they were made: the EDT only carries out the latest one. */
        private val requests = PopoutRequests()

        override fun open(videoW: Int, videoH: Int) {
            wanted = true
            val request = requests.next()
            SwingUtilities.invokeLater {
                // A close (or another open) asked for since: that one decides, an older close never cancels a newer open
                if (!requests.isLatest(request)) return@invokeLater
                val existing = frame
                if (existing != null && existing.isDisplayable) {
                    existing.toFront(); existing.requestFocus(); return@invokeLater
                }
                // Closed again before the EDT got here (cleared display, ended share): never open late
                if (!wanted) return@invokeLater
                createFrame(videoW, videoH)
            }
        }

        override fun close() {
            wanted = false
            val request = requests.next()
            SwingUtilities.invokeLater { if (requests.isLatest(request)) destroyFrame() }
        }

        private fun createFrame(videoW: Int, videoH: Int) {
            runCatching {
                val w = videoW.coerceIn(480, 1280)
                val h = videoH.coerceIn(270, 720)

                val p = VideoPanel().also { panel = it }

                JFrame("Dream Displays").apply {
                    frame = this
                    defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
                    contentPane = p
                    setSize(w, h)
                    setLocationRelativeTo(null)

                    addWindowListener(object : WindowAdapter() {
                        override fun windowClosing(e: WindowEvent) = destroyFrame()
                    })

                    val keys = object : KeyAdapter() {
                        override fun keyPressed(e: KeyEvent) {
                            when (e.keyCode) {
                                KeyEvent.VK_ESCAPE -> destroyFrame()
                                KeyEvent.VK_F -> extendedState =
                                    if (extendedState and JFrame.MAXIMIZED_BOTH != 0) JFrame.NORMAL
                                    else JFrame.MAXIMIZED_BOTH
                            }
                        }
                    }
                    // Key events go to the focus owner only, and that is the (focusable) video panel, not the frame
                    addKeyListener(keys)
                    p.addKeyListener(keys)

                    isVisible = true
                }
            }.onFailure { e ->
                logger.error("Failed to create AWT popout window.", e)
                wanted = false
                frame = null
                panel = null
                frames.clear()
                onClose()
            }
        }

        private fun destroyFrame() {
            wanted = false
            val f = frame
            frame = null
            panel = null
            image = null
            frames.clear()
            f?.dispose()
            // Nothing to report when it never opened (closed while its creation was still queued)
            if (f != null) onClose()
        }

        /** Converts the newest frame, if a new one came, into [image]. EDT. */
        private fun takeNewestFrame() {
            val f = frames.borrowNewest(shownVersion) ?: return
            try {
                val meta = f.meta
                val w = meta.width
                val h = meta.height
                val bpp = meta.format.bytesPerPixel
                val size = w * h * bpp
                if (w <= 0 || h <= 0 || bpp < 3 || f.buffer.remaining() < size) return
                val img = image?.takeIf { it.width == w && it.height == h }
                    ?: BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                if (scratch.size < size) scratch = ByteArray(size)
                val src = scratch
                f.buffer.duplicate().get(src, 0, size)
                val pixels = (img.raster.dataBuffer as DataBufferInt).data
                var s = 0
                for (i in 0 until w * h) {
                    pixels[i] = ((src[s].toInt() and 0xFF) shl 16) or
                            ((src[s + 1].toInt() and 0xFF) shl 8) or
                            (src[s + 2].toInt() and 0xFF)
                    s += bpp
                }
                image = img
                contentAspect = meta.aspect
                shownVersion = f.version
            } finally {
                frames.release()
            }
        }

        inner class VideoPanel : JPanel() {
            init {
                background = Color.BLACK; isFocusable = true
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                takeNewestFrame()
                val img = image ?: return
                val aspect = contentAspect
                val vw = width
                val vh = height
                if (vw <= 0 || vh <= 0) return
                val drawW: Int;
                val drawH: Int;
                val ox: Int;
                val oy: Int
                if (aspect > 0.0 && aspect.isFinite()) {
                    val panelAspect = vw.toDouble() / vh
                    if (aspect > panelAspect) {
                        drawW = vw
                        drawH = (vw / aspect).toInt().coerceIn(1, vh)
                        ox = 0; oy = (vh - drawH) / 2
                    } else {
                        drawW = (vh * aspect).toInt().coerceIn(1, vw)
                        drawH = vh
                        ox = (vw - drawW) / 2; oy = 0
                    }
                } else {
                    drawW = vw; drawH = vh; ox = 0; oy = 0
                }
                g.drawImage(img, ox, oy, drawW, drawH, null)
            }
        }
    }

    companion object {
        private val IS_MACOS = OsInfo.isMac

        /** True when a popout window can be opened. */
        val isAvailable: Boolean by lazy {
            //? if >=26.3 {
            IS_MACOS || try {
                !GraphicsEnvironment.isHeadless()
            } catch (_: Exception) {
                false
            }
            //?} else
            /*IS_MACOS || try {
                !GraphicsEnvironment.isHeadless()
            } catch (_: Exception) {
                false
            }*/
        }
    }
}

private data class ContentRect(val x: Int, val y: Int, val w: Int, val h: Int)

private class QuadRenderer {
    private val texId = GL11.glGenTextures()
    private val vao = GL30.glGenVertexArrays()
    private val vbo = GL15.glGenBuffers()
    private val program = buildProgram()
    private val uploader = AsyncTextureUploader(stateCache = false)
    private var texW = 0;
    private var texH = 0

    init {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        GL30.glBindVertexArray(vao)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo)
        GL15.glBufferData(
            GL15.GL_ARRAY_BUFFER, floatArrayOf(
                -1f, 1f, 0f, 0f,
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, -1f, 1f, 1f,
                1f, 1f, 1f, 0f,
            ), GL15.GL_STATIC_DRAW
        )
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0L)
        GL20.glEnableVertexAttribArray(0)
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8L)
        GL20.glEnableVertexAttribArray(1)
        GL30.glBindVertexArray(0)
    }

    fun upload(buf: ByteBuffer, w: Int, h: Int, format: UploadPixelFormat) {
        if (texW != w || texH != h) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId)
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, format.unpackAlignment)
            val sp = buf.position();
            val sl = buf.limit()
            buf.limit(sp + w * h * format.bytesPerPixel)
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D, 0, format.glFormat, w, h, 0,
                format.glFormat, GL11.GL_UNSIGNED_BYTE, buf
            )
            buf.limit(sl); buf.position(sp)
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
            texW = w; texH = h
            return
        }
        uploader.upload(texId, buf, w, h, format)
    }

    fun draw(vw: Int, vh: Int, content: ContentRect, fw: Int, fh: Int) {
        val scale = minOf(vw.toFloat() / content.w, vh.toFloat() / content.h)
        val dw = (content.w * scale).toInt();
        val dh = (content.h * scale).toInt()
        val ox = (vw - dw) / 2;
        val oy = (vh - dh) / 2
        GL11.glClearColor(0f, 0f, 0f, 1f)
        GL11.glViewport(0, 0, vw, vh); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
        GL11.glViewport(ox, oy, dw, dh)
        GL20.glUseProgram(program)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId)
        val loc = GL20.glGetUniformLocation(program, "crop")
        if (loc >= 0) GL20.glUniform4f(
            loc,
            content.x / fw.toFloat(), content.y / fh.toFloat(),
            content.w / fw.toFloat(), content.h / fh.toFloat()
        )
        GL30.glBindVertexArray(vao)
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6)
        GL30.glBindVertexArray(0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        GL20.glUseProgram(0)
    }

    fun cleanup() {
        uploader.cleanup()
        GL11.glDeleteTextures(texId)
        GL15.glDeleteBuffers(vbo)
        GL30.glDeleteVertexArrays(vao)
        GL20.glDeleteProgram(program)
    }

    companion object {
        private fun buildProgram(): Int {
            fun shader(type: Int, src: String): Int =
                GL20.glCreateShader(type).also { GL20.glShaderSource(it, src); GL20.glCompileShader(it) }

            val vs = shader(
                GL20.GL_VERTEX_SHADER, """
                #version 150
                in vec2 pos; in vec2 uv; out vec2 vUv;
                void main() { gl_Position = vec4(pos, 0.0, 1.0); vUv = uv; }
            """.trimIndent()
            )
            val fs = shader(
                GL20.GL_FRAGMENT_SHADER, """
                #version 150
                uniform sampler2D tex; uniform vec4 crop;
                in vec2 vUv; out vec4 fragColor;
                void main() { fragColor = texture(tex, crop.xy + vUv * crop.zw); }
            """.trimIndent()
            )
            return GL20.glCreateProgram().also { p ->
                GL20.glAttachShader(p, vs); GL20.glAttachShader(p, fs)
                GL20.glBindAttribLocation(p, 0, "pos")
                GL20.glBindAttribLocation(p, 1, "uv")
                GL20.glLinkProgram(p)
                GL20.glDetachShader(p, vs); GL20.glDeleteShader(vs)
                GL20.glDetachShader(p, fs); GL20.glDeleteShader(fs)
            }
        }
    }
}

/**
 * Numbers a popout window's open / close requests, made on the game thread and carried out later on another (the EDT):
 * only the latest may act, so a close still queued behind a newer open never cancels that open.
 */
internal class PopoutRequests {
    private val latest = java.util.concurrent.atomic.AtomicLong()

    /** A new request; every earlier one is superseded. */
    fun next(): Long = latest.incrementAndGet()

    fun isLatest(request: Long): Boolean = latest.get() == request
}
