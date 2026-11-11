package com.dreamdisplays.media.player.process

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary

/**
 * The screens macOS draws to now, in `CGGetActiveDisplayList` order: the order `FFmpeg`'s avfoundation numbers its
 * `Capture screen N` devices in (GLFW keeps its own order, which drifts from it after a hot-plug or with mirroring).
 */
object MacDisplays {
    private const val MAX = 32

    /** Every active display's CGDirectDisplayID, in avfoundation's order; null when CoreGraphics cannot be asked. */
    fun activeIds(): List<Int>? = runCatching {
        val cg = runCatching { NativeLibrary.getInstance("CoreGraphics") }
            .getOrElse { NativeLibrary.getInstance("ApplicationServices") }
        val ids = Memory(4L * MAX).apply { clear() }
        val count = Memory(4).apply { clear() }
        val err = cg.getFunction("CGGetActiveDisplayList").invokeInt(arrayOf(MAX, ids, count))
        check(err == 0) { "CGGetActiveDisplayList failed: $err" }
        List(count.getInt(0).coerceIn(0, MAX)) { ids.getInt(4L * it) }
    }.getOrNull()
}
