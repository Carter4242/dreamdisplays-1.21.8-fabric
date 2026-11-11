package com.dreamdisplays.media.player.process

/** Which top-level windows the share picker offers (Windows): the ones a person would call "a window". */
object ShareWindows {
    /** What the window list reads about one top-level window. */
    data class Facts(
        val visible: Boolean,
        val title: String,
        val exStyle: Int,
        val cloaked: Boolean,
        val owned: Boolean,
        val pid: Long,
        val width: Int,
        val height: Int,
        val minimized: Boolean,
    )

    private const val WS_EX_TOOLWINDOW = 0x80
    private const val WS_EX_NOACTIVATE = 0x08000000
    private const val WS_EX_APPWINDOW = 0x40000

    /** Smallest window (either side, in pixels) worth sharing; minimized ones report their pre-minimize size. */
    private const val MIN_SIDE = 64

    /**
     * True for a window worth offering: shown and titled, not cloaked (another virtual desktop, a suspended UWP app),
     * not owned by another window (dialogs, popups) and not a tool window or palette (unless it asks for a taskbar
     * button), not tiny, and not this game's own.
     */
    fun offer(w: Facts, selfPid: Long): Boolean {
        if (!w.visible || w.cloaked || w.owned || w.pid == selfPid || w.title.isBlank()) return false
        val appWindow = w.exStyle and WS_EX_APPWINDOW != 0
        if (!appWindow && (w.exStyle and WS_EX_TOOLWINDOW != 0 || w.exStyle and WS_EX_NOACTIVATE != 0)) return false
        if (!w.minimized && (w.width < MIN_SIDE || w.height < MIN_SIDE)) return false
        return w.title != "Program Manager"
    }

    /** True when [exePath] is ApplicationFrameHost, which draws the frame of every Store (UWP) app's window. */
    fun isFrameHostExe(exePath: String): Boolean =
        exePath.substringAfterLast('\\').substringAfterLast('/').equals("ApplicationFrameHost.exe", ignoreCase = true)

    /** A program's friendly name from its file description, else its executable's name without `.exe`. */
    fun appName(description: String?, exePath: String?): String {
        description?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val file = exePath.orEmpty().substringAfterLast('\\').substringAfterLast('/')
        return file.removeSuffix(".exe").removeSuffix(".EXE").ifBlank { "App" }
    }
}
