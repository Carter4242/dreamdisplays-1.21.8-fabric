package com.dreamdisplays.platform.client.ui.kit

//? if >=1.21.6 <1.21.11 {
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

/**
 * GLFW-backed stand-in for the cursor API vanilla only gained in 1.21.9 (`GuiGraphics.requestCursor` +
 * `CursorTypes`). Widgets call [request] while drawing, from the same spots that call `requestCursor` on newer
 * versions; the last request in a frame wins, as in vanilla. [UiScreenBase] calls [applyFrame] after every frame,
 * which shows the requested shape or restores the default arrow, and [reset] when the screen is removed, so no
 * shape outlives our screens. The window cursor is only touched after we have set a shape ourselves, so other
 * screens and mods keep whatever cursor they manage.
 */
enum class LegacyCursor(private val glfwShape: Int) {
    IBEAM(GLFW.GLFW_IBEAM_CURSOR),
    POINTING_HAND(GLFW.GLFW_POINTING_HAND_CURSOR),
    RESIZE_EW(GLFW.GLFW_RESIZE_EW_CURSOR),
    RESIZE_NS(GLFW.GLFW_RESIZE_NS_CURSOR),
    NOT_ALLOWED(GLFW.GLFW_NOT_ALLOWED_CURSOR);

    private var handle = 0L
    private var created = false

    /** Requests this shape for the current frame. */
    fun request() {
        requested = this
    }

    // Created once on the render thread and reused for the session; 0 (default arrow) if the platform lacks it.
    private fun handle(): Long {
        if (!created) {
            created = true
            handle = GLFW.glfwCreateStandardCursor(glfwShape)
        }
        return handle
    }

    companion object {
        private var requested: LegacyCursor? = null
        private var applied = 0L

        /** Shows the shape requested this frame, or the default arrow if nothing asked for one. */
        fun applyFrame() {
            val shape = requested
            requested = null
            select(shape?.handle() ?: 0L)
        }

        /** Drops any pending request and restores the default arrow. */
        fun reset() {
            requested = null
            select(0L)
        }

        private fun select(handle: Long) {
            if (handle == applied) return
            applied = handle
            GLFW.glfwSetCursor(Minecraft.getInstance().window.window, handle)
        }
    }
}
//?}
